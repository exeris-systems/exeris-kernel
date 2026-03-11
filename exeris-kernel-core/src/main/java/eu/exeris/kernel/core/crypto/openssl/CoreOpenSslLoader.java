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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Core: "Pure function" that loads OpenSSL and resolves the common TLS 1.3 method handles.
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
public final class CoreOpenSslLoader {

    /** {@code SSL_FILETYPE_PEM = 1} from {@code openssl/ssl.h}. */
    public static final int SSL_FILETYPE_PEM   = 1;
    /** {@code SSL_VERIFY_NONE = 0} — no peer certificate verification. */
    public static final int SSL_VERIFY_NONE    = 0;
    /** {@code SSL_ERROR_WANT_READ = 2}. */
    public static final int SSL_ERROR_WANT_READ  = 2;
    /** {@code SSL_ERROR_WANT_WRITE = 3}. */
    public static final int SSL_ERROR_WANT_WRITE = 3;
    /** {@code SSL_ERROR_ZERO_RETURN = 6} — clean peer close. */
    public static final int SSL_ERROR_ZERO_RETURN = 6;

    private static final String PROVIDER = "CoreOpenSslLoader";

    /** Minimum OPENSSL_VERSION_NUMBER for OpenSSL 3.0.0 ({@code 0x30000000L}). */
    private static final long OPENSSL_3_MIN_VERSION = 0x30000000L;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String[] SSL_CANDIDATES_LINUX = {
        "/opt/openssl-3.5/lib64/libssl.so.3",
        "/opt/openssl-3.5/lib/libssl.so.3",
        "/usr/local/lib/libssl.so.3",
        "/usr/lib/x86_64-linux-gnu/libssl.so.3",
        "libssl.so.3"
    };
    private static final String[] CRYPTO_CANDIDATES_LINUX = {
        "/opt/openssl-3.5/lib64/libcrypto.so.3",
        "/opt/openssl-3.5/lib/libcrypto.so.3",
        "/usr/local/lib/libcrypto.so.3",
        "/usr/lib/x86_64-linux-gnu/libcrypto.so.3",
        "libcrypto.so.3"
    };
    private static final String[] SSL_CANDIDATES_WINDOWS = {
        "libssl-3-x64.dll",
        "libssl-3.dll",
        "ssleay64.dll"
    };
    private static final String[] CRYPTO_CANDIDATES_WINDOWS = {
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
     * Loads OpenSSL into {@code arena} and resolves the standard TLS 1.3 method handles.
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
     *   <li>Built-in OS candidate lists for both libraries.</li>
     * </ol>
     *
     * @param arena the arena whose scope governs the lifetime of the loaded symbols
     * @return immutable {@link CoreSslHandles} record containing all resolved handles
     * @throws CryptoBootstrapException if libssl cannot be found or a required symbol is missing
     */
    public static CoreSslHandles load(Arena arena) {
        SymbolLookup crypto = resolveCrypto(arena);
        SymbolLookup ssl    = resolveSsl(arena);

        if (crypto == null || ssl == null) {
            throw new CryptoBootstrapException(PROVIDER,
                    "OpenSSL 3.x not found. Set EXERIS_OPENSSL_SSL_PATH and "
                    + "EXERIS_OPENSSL_CRYPTO_PATH (or EXERIS_OPENSSL_PATH for libcrypto) "
                    + "or install libssl3.");
        }

        SymbolLookup lookup = ssl.or(crypto);
        Linker linker       = Linker.nativeLinker();

        verifyOpenSslVersion(linker, lookup);

        CoreSslHandles.CtxHandles ctx = new CoreSslHandles.CtxHandles(
                req(linker, lookup, "TLS_server_method",
                        FunctionDescriptor.of(JAVA_LONG)),
                req(linker, lookup, "TLS_client_method",
                        FunctionDescriptor.of(JAVA_LONG)),
                req(linker, lookup, "SSL_CTX_new",
                        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),
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
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT)));

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

        return new CoreSslHandles(ctx, handshake, ioHandles);
    }

    // =========================================================================
    // Version gate — rejects OpenSSL < 3.0.0
    // =========================================================================

    /**
     * Resolves {@code OpenSSL_version_num()} and asserts the loaded library is
     * OpenSSL 3.x ({@code OPENSSL_VERSION_NUMBER >= 0x30000000L}).
     *
     * <p>This guard catches the case where an unversioned system name (e.g. resolved
     * via {@code EXERIS_OPENSSL_CRYPTO_PATH} pointing at an old path) inadvertently
     * loads OpenSSL 1.1.x, whose ABI differs in struct layouts and function signatures.
     *
     * @throws CryptoBootstrapException if {@code OpenSSL_version_num} cannot be resolved
     *                                  or the version is older than 3.0.0
     */
    private static void verifyOpenSslVersion(Linker linker, SymbolLookup lookup) {
        MethodHandle versionNum = opt(linker, lookup, "OpenSSL_version_num",
                FunctionDescriptor.of(JAVA_LONG));
        if (versionNum == null) {
            throw new CryptoBootstrapException(PROVIDER,
                    "OpenSSL_version_num symbol not found — library may not be OpenSSL 3.x.");
        }
        long version;
        try {
            version = (long) versionNum.invokeExact();
        } catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            FfmErrors.rethrowIfError(throwable);
            throw new CryptoBootstrapException(PROVIDER,
                    "OpenSSL_version_num invocation failed", throwable);
        }
        if (version < OPENSSL_3_MIN_VERSION) {
            throw new CryptoBootstrapException(PROVIDER,
                    "OpenSSL 3.x required (OPENSSL_VERSION_NUMBER >= 0x30000000), "
                    + "but found: 0x" + Long.toHexString(version)
                    + ". Set EXERIS_OPENSSL_SSL_PATH / EXERIS_OPENSSL_CRYPTO_PATH "
                    + "to point at an OpenSSL 3.x installation.");
        }
    }

    // =========================================================================
    // Library resolution helpers
    // =========================================================================

    /**
     * Resolves {@code libcrypto} using environment overrides with OS candidate fallback.
     *
     * <p>Discovery order:
     * <ol>
     *   <li>{@code EXERIS_OPENSSL_CRYPTO_PATH} — explicit path.</li>
     *   <li>{@code EXERIS_OPENSSL_PATH} — legacy single-lib override (backward compat).</li>
     *   <li>Built-in {@link #CRYPTO_CANDIDATES} list.</li>
     * </ol>
     */
    private static SymbolLookup resolveCrypto(Arena arena) {
        SymbolLookup lookup = tryLoadFromEnv("EXERIS_OPENSSL_CRYPTO_PATH", arena);
        if (lookup == null) {
            lookup = tryLoadFromEnv("EXERIS_OPENSSL_PATH", arena);
        }
        return lookup != null ? lookup : tryLoadAll(CRYPTO_CANDIDATES, arena);
    }

    /**
     * Resolves {@code libssl} using an environment override with OS candidate fallback.
     *
     * <p>Discovery order:
     * <ol>
     *   <li>{@code EXERIS_OPENSSL_SSL_PATH} — explicit path.</li>
     *   <li>Built-in {@link #SSL_CANDIDATES} list.</li>
     * </ol>
     */
    private static SymbolLookup resolveSsl(Arena arena) {
        SymbolLookup lookup = tryLoadFromEnv("EXERIS_OPENSSL_SSL_PATH", arena);
        return lookup != null ? lookup : tryLoadAll(SSL_CANDIDATES, arena);
    }

    private static SymbolLookup tryLoadFromEnv(String envVar, Arena arena) {
        String path = System.getenv(envVar);
        return (path != null && !path.isBlank()) ? tryLoad(path, arena) : null;
    }

    private static SymbolLookup tryLoadAll(String[] candidates, Arena arena) {
        for (String candidate : candidates) {
            SymbolLookup found = tryLoad(candidate, arena);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static SymbolLookup tryLoad(String path, Arena arena) {
        try {
            return SymbolLookup.libraryLookup(path, arena);
        } catch (IllegalArgumentException _) {
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
