/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a {@link NativeCipherContext} is successfully created —
 * i.e., when {@code SSL_new} allocates a native {@code SSL*} handle.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Zero overhead when JFR is not recording ({@link #isEnabled()} guard).
 * Emitted exactly once per session context allocation, never on the hot path.
 *
 * <h2>Diagnostic Value</h2>
 * <p>Enables session-level memory accounting in JFR recordings:
 * counting events shows active TLS session cardinality; the {@code sizeBytes} field
 * cross-references with {@code WatermarkManager} JFR events to correlate allocation
 * pressure with TLS session ramp-up.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.crypto.ContextAlloc")
@Label("Crypto Context Alloc")
@Description("Emitted when NativeCipherContext allocates a native SSL* handle via SSL_new")
@Category({"Exeris Kernel", "Crypto", "OpenSSL"})
@StackTrace(false)
final class CryptoContextAllocEvent extends Event {

    @Label("SSL Pointer")
    /* default */ long sslPtr;

    @Label("SSL CTX Pointer")
    /* default */ long sslCtxPtr;

    @Label("Provider Name")
    /* default */ String providerName;

    @Label("Size Bytes")
    /* default */ long sizeBytes;

    /**
     * Emits a context-allocation event.
     *
     * @param sslPtr       raw address of the newly allocated {@code SSL*}
     * @param sslCtxPtr    raw address of the shared {@code SSL_CTX*}
     * @param providerName logical name of the provider requesting the context
     * @param sizeBytes    estimated size of the native SSL session structure (informational)
     */
    /* default */ static void emit(long sslPtr, long sslCtxPtr, String providerName, long sizeBytes) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CryptoContextAllocEvent event = new CryptoContextAllocEvent();
        if (event.isEnabled()) {
            event.sslPtr       = sslPtr;
            event.sslCtxPtr    = sslCtxPtr;
            event.providerName = providerName;
            event.sizeBytes    = sizeBytes;
            event.commit();
        }
    }
}
