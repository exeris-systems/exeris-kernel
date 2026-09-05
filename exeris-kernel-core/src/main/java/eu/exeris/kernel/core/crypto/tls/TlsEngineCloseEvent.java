/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when an {@code OffHeapTlsEngine} transitions to the
 * {@code CLOSED} state — either via graceful shutdown or forced close.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Zero overhead when JFR is not recording. Emitted exactly once per engine
 * lifecycle — never on the I/O hot path.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.tls.EngineClose")
@Label("TLS Engine Close")
@Description("Emitted when OffHeapTlsEngine releases its SSL* handle and transitions to CLOSED")
@Category({"Exeris Kernel", "TLS"})
@StackTrace(false)
final class TlsEngineCloseEvent extends Event {

    @Label("SSL Pointer")
    /* default */ long sslPtr;

    @Label("Graceful")
    /* default */ boolean graceful;

    @Label("Final Phase")
    /* default */ String finalPhase;

    /* default */ static void emit(long sslPtr, boolean graceful, String finalPhase) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TlsEngineCloseEvent event = new TlsEngineCloseEvent();
        if (event.isEnabled()) {
            event.sslPtr   = sslPtr;
            event.graceful = graceful;
            event.finalPhase  = finalPhase;
            event.commit();
        }
    }
}
