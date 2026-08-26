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
 * JFR event emitted at the start and successful completion of a TLS handshake
 * performed by {@link OffHeapTlsEngine}.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Zero overhead when JFR is not initialized — the early
 * {@link FlightRecorder#isInitialized()} guard returns before any allocation.
 * When Flight Recorder is initialized, a single {@code TlsHandshakeEvent} instance
 * is allocated; fields are populated and the event is committed only when
 * {@link #isEnabled()} returns {@code true}. Emitted exactly twice per session:
 * once at handshake start ({@code durationNanos == 0}) and once at completion
 * ({@code durationNanos} holds the wall-clock duration).
 *
 * <h2>Cardinality</h2>
 * <p>One event pair per TLS session — never emitted on the cipher hot path.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.tls.Handshake")
@Label("TLS Handshake")
@Description("Emitted at TLS handshake start and completion by OffHeapTlsEngine")
@Category({"Exeris Kernel", "TLS"})
@StackTrace(false)
final class TlsHandshakeEvent extends Event {

    @Label("SSL Pointer")
    /* default */ long sslPtr;

    @Label("Mode")
    /* default */ String mode; // "SERVER" or "CLIENT"

    @Label("Protocol")
    /* default */ String protocol; // e.g. "TLSv1.3"

    @Label("Cipher")
    /* default */ String cipher; // e.g. "TLS_AES_256_GCM_SHA384"

    @Label("Negotiated ALPN")
    /* default */ String negotiatedAlpn; // e.g. "h2", "" if none

    @Label("Duration Nanos")
    /* default */ long durationNanos;

    /**
     * Emits a handshake-start event.
     *
     * @param sslPtr raw {@code SSL*} address
     * @param server {@code true} for server mode, {@code false} for client
     */
    /* default */ static void emitStart(long sslPtr, boolean server) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TlsHandshakeEvent event = new TlsHandshakeEvent();
        if (event.isEnabled()) {
            event.sslPtr        = sslPtr;
            event.mode          = server ? "SERVER" : "CLIENT";
            event.protocol      = "";
            event.cipher        = "";
            event.negotiatedAlpn = "";
            event.durationNanos = 0L;
            event.commit();
        }
    }

    /**
     * Emits a handshake-completion event.
     *
     * @param sslPtr             raw {@code SSL*} address
     * @param server             {@code true} for server mode
     * @param negotiatedAlpn     ALPN protocol negotiated (empty string if none)
     * @param cipherName         TLS cipher suite name from {@code SSL_CIPHER_get_name}
     *                           (empty string if unavailable)
     * @param durationNanos      wall-clock duration of the handshake in nanoseconds
     * @param negotiatedVersion  TLS version string from {@code SSL_get_version}
     *                           (e.g. {@code "TLSv1.3"}); empty string if the handle is not wired
     */
    /* default */ static void emitComplete(long sslPtr, boolean server,
                                           String negotiatedAlpn, String cipherName,
                                           long durationNanos, String negotiatedVersion) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TlsHandshakeEvent event = new TlsHandshakeEvent();
        if (event.isEnabled()) {
            event.sslPtr         = sslPtr;
            event.mode           = server ? "SERVER" : "CLIENT";
            event.protocol       = negotiatedVersion != null ? negotiatedVersion : "";
            event.cipher         = cipherName != null ? cipherName : "";
            event.negotiatedAlpn = negotiatedAlpn != null ? negotiatedAlpn : "";
            event.durationNanos  = durationNanos;
            event.commit();
        }
    }
}
