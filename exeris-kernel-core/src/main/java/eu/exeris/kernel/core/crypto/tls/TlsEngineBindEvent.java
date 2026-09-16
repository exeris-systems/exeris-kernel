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
 * JFR event emitted when an {@code OffHeapTlsEngine} is constructed and
 * enters {@code HANDSHAKE_IN_PROGRESS} (BIO already wired by the caller).
 *
 * <h2>JFR-First Contract</h2>
 * <p>When {@link FlightRecorder} is not initialized, {@link #emit(long, boolean)}
 * returns immediately without allocating an event instance. When Flight Recorder
 * is initialized, a single {@code TlsEngineBindEvent} instance is allocated and
 * only populated and committed if {@link #isEnabled()} returns {@code true}.
 * Never emitted on the I/O hot path — only at the one-time engine bind during
 * connection setup.
 *
 * <h2>Diagnostic Value</h2>
 * <p>Correlates a raw {@code SSL*} pointer (visible in native crash dumps and
 * OpenSSL error logs) with the engine role (SERVER/CLIENT), enabling post-mortem
 * analysis of TLS session bootstrap failures across both Community (fd-owner BIO)
 * and Enterprise (Memory-BIO / QUIC) transport tiers.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.tls.EngineBind")
@Label("TLS Engine Bind")
@Description("Emitted when OffHeapTlsEngine is constructed with BIO-ready SSL* and enters HANDSHAKE_IN_PROGRESS")
@Category({"Exeris Kernel", "TLS"})
@StackTrace(false)
final class TlsEngineBindEvent extends Event {

    @Label("SSL Pointer")
    /* default */ long sslPtr;

    @Label("Mode")
    /* default */ String mode; // "SERVER" or "CLIENT"

    /**
     * Emits the bind event.
     *
     * @param sslPtr raw {@code SSL*} address
     * @param server {@code true} for server mode, {@code false} for client
     */
    /* default */ static void emit(long sslPtr, boolean server) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TlsEngineBindEvent event = new TlsEngineBindEvent();
        if (event.isEnabled()) {
            event.sslPtr = sslPtr;
            event.mode   = server ? "SERVER" : "CLIENT";
            event.commit();
        }
    }
}
