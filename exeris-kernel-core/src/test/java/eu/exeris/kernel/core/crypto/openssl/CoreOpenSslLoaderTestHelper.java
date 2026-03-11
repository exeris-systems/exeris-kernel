/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Test-scope bridge into {@link CoreOpenSslLoader}'s package-private resolution logic.
 *
 * <p>Resides in the same package as {@link CoreOpenSslLoader} (test source set) to access
 * the package-private {@code resolveSsl(Arena)} method. Callers such as
 * {@code OffHeapTlsEngineLoopbackIT} use this to resolve additional symbols
 * (e.g. {@code SSL_set_fd}) from the <em>exact same</em> library instance that
 * {@link CoreOpenSslLoader#load(Arena)} used — preventing an ABI mismatch that
 * would occur if the IT independently called
 * {@code SymbolLookup.libraryLookup("libssl.so.3", ...)} and resolved a different
 * system libssl than the one loaded via the candidate list or env override.
 *
 * <p>This class is <strong>not</strong> part of the production SPI and must never
 * be referenced from main source sets.
 *
 * @since 0.5.0
 */
public final class CoreOpenSslLoaderTestHelper {

    private CoreOpenSslLoaderTestHelper() {}

    /**
     * Resolves {@code SSL_set_fd(SSL*, int)} from the same {@code libssl} instance that
     * {@link CoreOpenSslLoader#load(Arena)} would select.
     *
     * <p>Discovery order mirrors {@link CoreOpenSslLoader}: {@code EXERIS_OPENSSL_SSL_PATH}
     * env override → OS candidate list. Passing the same {@link Arena} ensures the loaded
     * library shares its lifetime with the rest of the Core OpenSSL bootstrap.
     *
     * @param arena arena whose scope governs the resolved symbol's lifetime
     * @return downcall handle for {@code int SSL_set_fd(SSL*, int)},
     *         or {@code null} if {@code libssl} cannot be found
     */
    public static MethodHandle resolveSslSetFd(Arena arena) {
        SymbolLookup sslLookup = CoreOpenSslLoader.resolveSsl(arena);
        if (sslLookup == null) {
            return null;
        }
        return sslLookup.find("SSL_set_fd")
                .map(addr -> Linker.nativeLinker().downcallHandle(
                        addr,
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT)))
                .orElse(null);
    }
}
