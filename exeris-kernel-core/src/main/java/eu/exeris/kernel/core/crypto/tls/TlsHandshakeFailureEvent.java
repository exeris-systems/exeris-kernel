/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a TLS handshake fails inside {@link OffHeapTlsEngine}.
 *
 * <h2>JFR-First Contract</h2>
 * <p>{@link StackTrace} is enabled ({@code true}) for this event because handshake
 * failures are exceptional, non-hot-path occurrences where the call stack adds
 * critical diagnostic value with no throughput cost.
 *
 * <h2>Mapping to Error Codes</h2>
 * <p>The {@code errorCode} field carries the Exeris error code string
 * (e.g., {@code "EX-NET-2002"}) to align with the binary Glass-Box telemetry contract
 * defined in {@code docs/subsystems/crypto.md}.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.tls.HandshakeFailure")
@Label("TLS Handshake Failure")
@Description("Emitted when OffHeapTlsEngine fails during the TLS handshake phase")
@Category({"Exeris Kernel", "TLS"})
@StackTrace(true)
final class TlsHandshakeFailureEvent extends Event {

    @Label("SSL Pointer")
    /* default */ long sslPtr;

    @Label("Mode")
    /* default */ String mode; // "SERVER" or "CLIENT"

    @Label("Error Code")
    /* default */ String errorCode; // e.g. "EX-NET-2002"

    @Label("Failure Reason")
    /* default */ String failureReason;

    @Label("SSL Error Code")
    /* default */ int sslErrorCode;

    /**
     * Emits a handshake-failure event.
     *
     * @param sslPtr        raw {@code SSL*} address
     * @param server        {@code true} for server mode, {@code false} for client
     * @param errorCode     Exeris error code from {@link KernelErrorCodes}
     *                      (e.g. {@link KernelErrorCodes#EX_NET_2002})
     * @param failureReason human-readable failure reason
     * @param sslErrorCode  raw {@code SSL_get_error()} result code
     */
    /* default */ static void emit(long sslPtr, boolean server,
                                   String errorCode, String failureReason, int sslErrorCode) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TlsHandshakeFailureEvent event = new TlsHandshakeFailureEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.sslPtr        = sslPtr;
        event.mode          = server ? "SERVER" : "CLIENT";
        event.errorCode     = errorCode;
        event.failureReason = failureReason;
        event.sslErrorCode  = sslErrorCode;
        event.commit();
    }
}
