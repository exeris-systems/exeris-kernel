/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Test-scope bridge into {@link CoreOpenSslRuntime}'s exact-library lookup.
 *
 * <p>Callers such as {@code OffHeapTlsEngineLoopbackIT} use this to resolve additional symbols
 * (e.g. {@code SSL_set_fd}) from the <em>exact same</em> library instance that
 * {@link CoreOpenSslLoader#load(Arena)} selected — preventing an ABI mismatch that would
 * occur if the IT independently resolved a different system {@code libssl}.
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
     * {@link CoreOpenSslLoader#load(Arena)} already selected.
     *
     * <p>The passed runtime encapsulates the exact bootstrap lookup, so no secondary library
     * discovery occurs here.
     *
     * @param runtime shared Core OpenSSL runtime selected during bootstrap
     * @return downcall handle for {@code int SSL_set_fd(SSL*, int)},
     *         or {@code null} if {@code libssl} cannot be found
     */
    public static MethodHandle resolveSslSetFd(CoreOpenSslRuntime runtime) {
        return runtime.optionalSslHandle("SSL_set_fd",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    }
}
