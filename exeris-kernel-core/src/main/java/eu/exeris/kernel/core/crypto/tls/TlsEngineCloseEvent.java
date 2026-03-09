/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
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
 * @since 0.5.0
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
        TlsEngineCloseEvent event = new TlsEngineCloseEvent();
        if (event.isEnabled()) {
            event.sslPtr   = sslPtr;
            event.graceful = graceful;
            event.finalPhase  = finalPhase;
            event.commit();
        }
    }
}
