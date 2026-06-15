/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Core: "Pure function" that loads OpenSSL and resolves the shared TLS 1.3 runtime.
 *
 * <h2>Design — No State, No Arena Policy</h2>
 * <p>This class owns <strong>no arena</strong> and enforces <strong>no memory policy</strong>.
 * The caller decides where the native symbols live:
 * <ul>
 *   <li>Community passes {@link Arena#global()} — symbols live for the JVM lifetime.</li>
 *   <li>Enterprise passes an {@link Arena} carved from the
 *       {@code GlobalMemoryArbiter.INFRASTRUCTURE} partition — symbols live inside the
 *       single pre-allocated mmap block, exactly within its budget.</li>
 * </ul>
 *
 * <h2>The Wall</h2>
 * <p>This class is in {@code exeris-kernel-core} and has zero knowledge of Community,
 * Enterprise, io_uring, QUIC, or {@code GlobalMemoryArbiter}.
 *
 * <h2>SSL constants</h2>
 * <p>Shared OpenSSL constants are exposed as {@code public static final int} fields
 * so callers do not duplicate magic numbers.
 *
 * @since 0.5.0
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
public final class CoreOpenSslLoader {

    /** {@code SSL_FILETYPE_PEM = 1} from {@code openssl/ssl.h}. */
    public static final int SSL_FILETYPE_PEM   = 1;
    /** {@code SSL_VERIFY_NONE = 0} — no peer certificate verification. */
    public static final int SSL_VERIFY_NONE    = 0;
    /** {@code SSL_ERROR_SSL = 1} — fatal protocol error (e.g. non-TLS bytes on a TLS port). */
    public static final int SSL_ERROR_SSL        = 1;
    /** {@code SSL_ERROR_WANT_READ = 2}. */
    public static final int SSL_ERROR_WANT_READ  = 2;
    /** {@code SSL_ERROR_WANT_WRITE = 3}. */
    public static final int SSL_ERROR_WANT_WRITE = 3;
    /** {@code SSL_ERROR_SYSCALL = 5} — I/O error or unexpected peer EOF mid-operation. */
    public static final int SSL_ERROR_SYSCALL    = 5;
    /** {@code SSL_ERROR_ZERO_RETURN = 6} — clean peer close. */
    public static final int SSL_ERROR_ZERO_RETURN = 6;

    private static final String PROVIDER = "CoreOpenSslLoader";

    /** Minimum OPENSSL_VERSION_NUMBER for OpenSSL 3.0.0 ({@code 0x30000000L}). */
    private static final long OPENSSL_3_MIN_VERSION = 0x30000000L;

    /** Lowest accepted OpenSSL major version ({@code OPENSSL_version_major()}). */
    private static final int OPENSSL_MIN_KNOWN_MAJOR = 3;

    /**
     * Highest known/validated OpenSSL major version. Majors above this are rejected
     * fail-fast: their ABI (struct layouts, function signatures) is unproven against
     * these FFM bindings, so loading one silently would be unsafe.
     */
    private static final int OPENSSL_MAX_KNOWN_MAJOR = 4;

    /**
     * Index passed to {@code OpenSSL_version(int)} for the full version string
     * ({@code OPENSSL_VERSION = 0} from {@code openssl/crypto.h}).
     */
    private static final int OPENSSL_VERSION = 0;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // Newest-major-first ordering: an OpenSSL 4.x install present on the host is preferred
    // over a co-installed 3.x. Deterministic LTS pinning is still available via the
    // EXERIS_OPENSSL_*_PATH environment overrides, which bypass these lists entirely.
    private static final String[] SSL_CANDIDATES_LINUX = {
        "/opt/openssl-4.0/lib64/libssl.so.4",
        "/opt/openssl-4.0/lib/libssl.so.4",
        "/opt/openssl-3.5/lib64/libssl.so.3",
        "/opt/openssl-3.5/lib/libssl.so.3",
        "/usr/local/lib/libssl.so.4",
        "/usr/lib/x86_64-linux-gnu/libssl.so.4",
        "/usr/local/lib/libssl.so.3",
        "/usr/lib/x86_64-linux-gnu/libssl.so.3",
        "libssl.so.4",
        "libssl.so.3"
    };
    private static final String[] CRYPTO_CANDIDATES_LINUX = {
        "/opt/openssl-4.0/lib64/libcrypto.so.4",
        "/opt/openssl-4.0/lib/libcrypto.so.4",
        "/opt/openssl-3.5/lib64/libcrypto.so.3",
        "/opt/openssl-3.5/lib/libcrypto.so.3",
        "/usr/local/lib/libcrypto.so.4",
        "/usr/lib/x86_64-linux-gnu/libcrypto.so.4",
        "/usr/local/lib/libcrypto.so.3",
        "/usr/lib/x86_64-linux-gnu/libcrypto.so.3",
        "libcrypto.so.4",
        "libcrypto.so.3"
    };
    private static final String[] SSL_CANDIDATES_WINDOWS = {
        "libssl-4-x64.dll",
        "libssl-4.dll",
        "libssl-3-x64.dll",
        "libssl-3.dll",
        "ssleay64.dll"
    };
    private static final String[] CRYPTO_CANDIDATES_WINDOWS = {
        "libcrypto-4-x64.dll",
        "libcrypto-4.dll",
        "libcrypto-3-x64.dll",
        "libcrypto-3.dll",
        "libeay64.dll"
    };

    private static final String[] SSL_CANDIDATES =
            IS_WINDOWS ? SSL_CANDIDATES_WINDOWS : SSL_CANDIDATES_LINUX;
    private static final String[] CRYPTO_CANDIDATES =
            IS_WINDOWS ? CRYPTO_CANDIDATES_WINDOWS : CRYPTO_CANDIDATES_LINUX;

    private CoreOpenSslLoader() {
        // utility class
    }

    /**
     * Loads OpenSSL into {@code arena} and resolves the shared TLS 1.3 runtime.
     *
     * <p><b>Community</b>: pass {@code Arena.global()}.
     * <b>Enterprise</b>: pass an {@link Arena} whose scope covers a slab from
     * {@code GlobalMemoryArbiter.INFRASTRUCTURE} — ensuring symbols stay inside the
     * single pre-allocated memory block.
     *
     * <h2>Library Discovery Order</h2>
     * <ol>
     *   <li>{@code EXERIS_OPENSSL_CRYPTO_PATH} → explicit path for {@code libcrypto}.</li>
     *   <li>{@code EXERIS_OPENSSL_PATH} → legacy override; controls {@code libcrypto}
     *       for backward compatibility.</li>
     *   <li>{@code EXERIS_OPENSSL_SSL_PATH} → explicit path for {@code libssl}.</li>
     *   <li>Built-in OS candidate lists for both libraries, ordered <b>newest major
     *       first</b> ({@code .so.4} before {@code .so.3}): a co-installed OpenSSL 4.x is
     *       preferred over 3.x. Pinned LTS deployments override candidate ordering with
     *       {@code EXERIS_OPENSSL_*_PATH}, which always wins.</li>
     * </ol>
     *
     * @param arena the arena whose scope governs the lifetime of the loaded symbols
     * @return immutable {@link CoreOpenSslRuntime} containing the exact runtime lookup and resolved handles
     * @throws CryptoBootstrapException if libssl cannot be found or a required symbol is missing
     */
    public static CoreOpenSslRuntime load(Arena arena) {
        ResolvedLibrary crypto = resolveCrypto(arena);
        ResolvedLibrary ssl    = resolveSsl(arena);

        if (crypto == null || ssl == null) {
            throw new CryptoBootstrapException(PROVIDER,
                    "OpenSSL not found (3.x or 4.x). Set EXERIS_OPENSSL_SSL_PATH and "
                    + "EXERIS_OPENSSL_CRYPTO_PATH (or EXERIS_OPENSSL_PATH for libcrypto) "
                    + "or install libssl3/libssl4.");
        }

        SymbolLookup lookup = ssl.lookup().or(crypto.lookup());
        Linker linker       = Linker.nativeLinker();

        OpenSslVersion version = verifyOpenSslVersion(linker, lookup);
        assertSameMajor(linker, ssl.lookup(), crypto.lookup());

        CoreSslHandles.CtxHandles ctx = new CoreSslHandles.CtxHandles(
                req(linker, lookup, "TLS_server_method",
                        FunctionDescriptor.of(JAVA_LONG)),
                req(linker, lookup, "TLS_client_method",
                        FunctionDescriptor.of(JAVA_LONG)),
                req(linker, lookup, "SSL_CTX_new_ex",
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG)),
                req(linker, lookup, "SSL_CTX_free",
                        FunctionDescriptor.ofVoid(JAVA_LONG)),
                req(linker, lookup, "SSL_CTX_use_certificate_file",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
                req(linker, lookup, "SSL_CTX_use_PrivateKey_file",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
                req(linker, lookup, "SSL_CTX_check_private_key",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                req(linker, lookup, "SSL_CTX_set_verify",
                        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, ADDRESS)),
                opt(linker, lookup, "SSL_CTX_set_alpn_protos",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT)),
                opt(linker, lookup, "SSL_CTX_set_alpn_select_cb",
                        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG)));

        // Emit the "successful load" JFR event only AFTER SSL_CTX_new_ex is actually bound
        // (single-phase, guarded inside OpenSslLoadEvent). Emitting before req(...,
        // "SSL_CTX_new_ex", ...) could surface a "success" event ahead of a binding that
        // could (theoretically) throw CryptoBootstrapException for a missing symbol.
        OpenSslLoadEvent.emit(version.major(), version.minor(), version.text(),
                "SSL_CTX_new_ex", ssl.path(), crypto.path());

        CoreSslHandles.HandshakeHandles handshake = new CoreSslHandles.HandshakeHandles(
                req(linker, lookup, "SSL_new",
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),
                req(linker, lookup, "SSL_free",
                        FunctionDescriptor.ofVoid(JAVA_LONG)),
                req(linker, lookup, "SSL_accept",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                req(linker, lookup, "SSL_connect",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                req(linker, lookup, "SSL_do_handshake",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG)));

        CoreSslHandles.IoHandles ioHandles = new CoreSslHandles.IoHandles(
                req(linker, lookup, "SSL_read",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
                req(linker, lookup, "SSL_write",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
                req(linker, lookup, "SSL_shutdown",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                req(linker, lookup, "SSL_get_shutdown",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                req(linker, lookup, "SSL_get_error",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT)),
                opt(linker, lookup, "SSL_get0_alpn_selected",
                        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG)),
                opt(linker, lookup, "SSL_get_current_cipher",
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),
                opt(linker, lookup, "SSL_CIPHER_get_name",
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)));

        CoreSslHandles handles = new CoreSslHandles(ctx, handshake, ioHandles);
        return new CoreOpenSslRuntime(linker, ssl.lookup(), crypto.lookup(), handles);
    }

    /**
     * Transitional convenience for callers that only need the canonical Core handle carrier.
     *
     * @param arena the arena whose scope governs the lifetime of the loaded symbols
     * @return immutable {@link CoreSslHandles} resolved from the shared runtime
     */
    public static CoreSslHandles loadHandles(Arena arena) {
        return load(arena).handles();
    }

    // =========================================================================
    // Version gate — accepts OpenSSL 3.x and 4.x, rejects < 3.0 and > 4.x
    // =========================================================================

    /**
     * Resolved OpenSSL version triple captured during the bootstrap gate.
     *
     * @param major OpenSSL major version ({@code OPENSSL_version_major()})
     * @param minor OpenSSL minor version ({@code OPENSSL_version_minor()})
     * @param text  full human-readable version string ({@code OpenSSL_version(OPENSSL_VERSION)})
     * @since 0.9.0
     */
    /* package */ record OpenSslVersion(int major, int minor, String text) {
    }

    /**
     * Resolves the OpenSSL version symbols, asserts the band {@code 3 <= major <= 4}, and
     * returns the version triple for the {@link OpenSslLoadEvent}.
     *
     * <p>Three independent signals are consulted:
     * <ul>
     *   <li>{@code OpenSSL_version_num()} — the {@code 0xMNNFFPPS} packed integer, used as
     *       the belt-and-braces lower floor ({@code >= 0x30000000}). Catches a stray 1.1.x
     *       loaded via an env override pointing at an old path.</li>
     *   <li>{@code OPENSSL_version_major()} / {@code OPENSSL_version_minor()} — present since
     *       3.0; the authoritative band gate.</li>
     *   <li>{@code OpenSSL_version(OPENSSL_VERSION)} — the human-readable string for the JFR
     *       event (informational only).</li>
     * </ul>
     *
     * @throws CryptoBootstrapException if a required version symbol is missing or the
     *                                  version falls outside the supported band
     */
    private static OpenSslVersion verifyOpenSslVersion(Linker linker, SymbolLookup lookup) {
        long versionNum = invokeVersionNum(linker, lookup);
        int major       = invokeVersionInt(linker, lookup, "OPENSSL_version_major");
        int minor       = invokeVersionInt(linker, lookup, "OPENSSL_version_minor");
        assertSupported(major, minor, versionNum);
        return new OpenSslVersion(major, minor, readVersionText(linker, lookup));
    }

    /**
     * Pure band-check decision logic, extracted so it is unit-testable without a live library.
     *
     * <p>Accepts {@code 3 <= major <= 4} with {@code versionNum >= 0x30000000}. Rejects a
     * major below 3 (legacy ABI), a major above 4 (unproven future ABI), and any packed
     * version number below the 3.0 floor.
     *
     * @param major      reported OpenSSL major version
     * @param minor      reported OpenSSL minor version (included in the diagnostic message only)
     * @param versionNum packed {@code OPENSSL_version_num()} value
     * @throws CryptoBootstrapException if the version is outside the supported band
     * @since 0.9.0
     */
    /* package */ static void assertSupported(int major, int minor, long versionNum) {
        if (!isSupportedVersion(major, versionNum)) {
            throw new CryptoBootstrapException(PROVIDER,
                    "Unsupported OpenSSL version: major=" + major + " minor=" + minor
                    + " num=0x" + Long.toHexString(versionNum)
                    + ". Supported band is " + OPENSSL_MIN_KNOWN_MAJOR + ".x .. "
                    + OPENSSL_MAX_KNOWN_MAJOR + ".x (>= 0x30000000). "
                    + "Set EXERIS_OPENSSL_SSL_PATH / EXERIS_OPENSSL_CRYPTO_PATH to pin a "
                    + "supported installation.");
        }
    }

    /**
     * Pure predicate form of the band check.
     *
     * @param major      reported OpenSSL major version
     * @param versionNum packed {@code OPENSSL_version_num()} value
     * @return {@code true} iff {@code 3 <= major <= 4} and {@code versionNum >= 0x30000000}
     * @since 0.9.0
     */
    /* package */ static boolean isSupportedVersion(int major, long versionNum) {
        return major >= OPENSSL_MIN_KNOWN_MAJOR
                && major <= OPENSSL_MAX_KNOWN_MAJOR
                && versionNum >= OPENSSL_3_MIN_VERSION;
    }

    /**
     * SONAME cross-load guard: resolves {@code OPENSSL_version_major()} independently against
     * {@code libssl} and {@code libcrypto} and rejects a major mismatch (e.g. {@code libssl.so.4}
     * paired with {@code libcrypto.so.3}), which would silently mix incompatible ABIs.
     *
     * <p>{@code OPENSSL_version_major} physically lives in {@code libcrypto}. Resolving it via the
     * {@code libssl} handle works on glibc because {@code dlsym} follows {@code libssl}'s dependency
     * closure into the {@code libcrypto} it was linked against — which is precisely what makes this
     * cross-major check meaningful. On Windows DLLs and other non-transitive link topologies, the
     * ssl-side lookup may return empty even though the bootstrap is otherwise valid. In that case
     * the check is <strong>skipped</strong> (degrading to trust in the merged version gate, which
     * has already passed against the authoritative crypto-side major) rather than failing the load.
     * The mismatch exception is thrown only when the ssl-side major is resolvable <em>and</em>
     * disagrees with the crypto-side major.
     */
    private static void assertSameMajor(Linker linker, SymbolLookup ssl, SymbolLookup crypto) {
        OptionalInt sslMajor = optVersionInt(linker, ssl, "OPENSSL_version_major");
        int cryptoMajor      = invokeVersionInt(linker, crypto, "OPENSSL_version_major");
        if (sslMajor.isPresent()) {
            assertSameMajor(sslMajor.getAsInt(), cryptoMajor);
        }
    }

    /**
     * Pure cross-major decision logic, extracted so it is unit-testable without a live library.
     *
     * @param sslMajor    {@code OPENSSL_version_major()} resolved via the {@code libssl} handle
     * @param cryptoMajor {@code OPENSSL_version_major()} resolved via the {@code libcrypto} handle
     * @throws CryptoBootstrapException if the two majors disagree (mixed-ABI cross-load)
     * @since 0.9.0
     */
    /* package */ static void assertSameMajor(int sslMajor, int cryptoMajor) {
        if (!majorsAgree(sslMajor, cryptoMajor)) {
            throw new CryptoBootstrapException(PROVIDER,
                    "libssl/libcrypto major mismatch: ssl=" + sslMajor + " crypto=" + cryptoMajor);
        }
    }

    /**
     * Pure predicate form of the cross-major check.
     *
     * @param sslMajor    ssl-side {@code OPENSSL_version_major()}
     * @param cryptoMajor crypto-side {@code OPENSSL_version_major()}
     * @return {@code true} iff the two majors are equal
     * @since 0.9.0
     */
    /* package */ static boolean majorsAgree(int sslMajor, int cryptoMajor) {
        return sslMajor == cryptoMajor;
    }

    private static long invokeVersionNum(Linker linker, SymbolLookup lookup) {
        MethodHandle handle = opt(linker, lookup, "OpenSSL_version_num",
                FunctionDescriptor.of(JAVA_LONG));
        if (handle == null) {
            throw new CryptoBootstrapException(PROVIDER,
                    "OpenSSL_version_num symbol not found — library may not be OpenSSL 3.x/4.x.");
        }
        try {
            return (long) handle.invokeExact();
        } catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            FfmErrors.rethrowIfError(throwable);
            throw new CryptoBootstrapException(PROVIDER,
                    "OpenSSL_version_num invocation failed", throwable);
        }
    }

    /**
     * Graceful variant of {@link #invokeVersionInt}: returns {@link OptionalInt#empty()} when the
     * symbol is not findable via {@code lookup} (e.g. a non-transitive link topology where the
     * crypto-resident symbol is not visible through the ssl handle), instead of throwing.
     */
    private static OptionalInt optVersionInt(Linker linker, SymbolLookup lookup, String symbol) {
        MethodHandle handle = opt(linker, lookup, symbol, FunctionDescriptor.of(JAVA_INT));
        if (handle == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of((int) handle.invokeExact());
        } catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            FfmErrors.rethrowIfError(throwable);
            throw new CryptoBootstrapException(PROVIDER, symbol + " invocation failed", throwable);
        }
    }

    private static int invokeVersionInt(Linker linker, SymbolLookup lookup, String symbol) {
        MethodHandle handle = opt(linker, lookup, symbol, FunctionDescriptor.of(JAVA_INT));
        if (handle == null) {
            throw new CryptoBootstrapException(PROVIDER,
                    symbol + " symbol not found — library may not be OpenSSL 3.x/4.x.");
        }
        try {
            return (int) handle.invokeExact();
        } catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            FfmErrors.rethrowIfError(throwable);
            throw new CryptoBootstrapException(PROVIDER, symbol + " invocation failed", throwable);
        }
    }

    private static String readVersionText(Linker linker, SymbolLookup lookup) {
        MethodHandle handle = opt(linker, lookup, "OpenSSL_version",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        if (handle == null) {
            return "";
        }
        try {
            MemorySegment ptr = (MemorySegment) handle.invokeExact(OPENSSL_VERSION);
            if (MemorySegment.NULL.equals(ptr)) {
                return "";
            }
            return ptr.reinterpret(Long.MAX_VALUE).getString(0L);
        } catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            FfmErrors.rethrowIfError(throwable);
            return "";
        }
    }

    // =========================================================================
    // Library resolution helpers
    // =========================================================================

    /**
     * A resolved native library: the {@link SymbolLookup} plus the matched candidate path
     * (for diagnostics — reported by {@link OpenSslLoadEvent}).
     *
     * @param lookup the live symbol lookup
     * @param path   the candidate string that resolved (filesystem path or SONAME)
     * @since 0.9.0
     */
    /* package */ record ResolvedLibrary(SymbolLookup lookup, String path) {
    }

    /**
     * Resolves {@code libcrypto} using environment overrides with OS candidate fallback.
     *
     * <p>Discovery order:
     * <ol>
     *   <li>{@code EXERIS_OPENSSL_CRYPTO_PATH} — explicit path.</li>
     *   <li>{@code EXERIS_OPENSSL_PATH} — legacy single-lib override (backward compat).</li>
     *   <li>Built-in {@link #CRYPTO_CANDIDATES} list (newest major first).</li>
     * </ol>
     */
    private static ResolvedLibrary resolveCrypto(Arena arena) {
        ResolvedLibrary resolved = tryLoadFromEnv("EXERIS_OPENSSL_CRYPTO_PATH", arena);
        if (resolved == null) {
            resolved = tryLoadFromEnv("EXERIS_OPENSSL_PATH", arena);
        }
        return resolved != null ? resolved : tryLoadAll(CRYPTO_CANDIDATES, arena);
    }

    /**
     * Resolves {@code libssl} using an environment override with OS candidate fallback.
     *
     * <p>Discovery order:
     * <ol>
     *   <li>{@code EXERIS_OPENSSL_SSL_PATH} — explicit path.</li>
     *   <li>Built-in {@link #SSL_CANDIDATES} list (newest major first).</li>
     * </ol>
     */
    private static ResolvedLibrary resolveSsl(Arena arena) {
        ResolvedLibrary resolved = tryLoadFromEnv("EXERIS_OPENSSL_SSL_PATH", arena);
        return resolved != null ? resolved : tryLoadAll(SSL_CANDIDATES, arena);
    }

    private static ResolvedLibrary tryLoadFromEnv(String envVar, Arena arena) {
        String path = System.getenv(envVar);
        if (path == null || path.isBlank()) {
            return null;
        }
        SymbolLookup lookup = tryLoad(path, arena);
        return lookup != null ? new ResolvedLibrary(lookup, path) : null;
    }

    private static ResolvedLibrary tryLoadAll(String[] candidates, Arena arena) {
        for (String candidate : candidates) {
            SymbolLookup found = tryLoad(candidate, arena);
            if (found != null) {
                return new ResolvedLibrary(found, candidate);
            }
        }
        return null;
    }

    private static SymbolLookup tryLoad(String path, Arena arena) {
        try {
            return SymbolLookup.libraryLookup(path, arena);
        } catch (IllegalArgumentException | LinkageError _) {
            return null;
        }
    }

    // =========================================================================
    // Handle factory helpers
    // =========================================================================

    private static MethodHandle req(Linker linker, SymbolLookup lookup,
                                    String sym, FunctionDescriptor desc) {
        Optional<MemorySegment> addr = lookup.find(sym);
        if (addr.isEmpty()) {
            throw new CryptoBootstrapException(PROVIDER,
                    "Required OpenSSL symbol not found: " + sym);
        }
        return linker.downcallHandle(addr.get(), desc);
    }

    private static MethodHandle opt(Linker linker, SymbolLookup lookup,
                                    String sym, FunctionDescriptor desc) {
        return lookup.find(sym)
                .map(addr -> linker.downcallHandle(addr, desc))
                .orElse(null);
    }
}
