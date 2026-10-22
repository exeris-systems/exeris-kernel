/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.contract.WorkloadAdmission;

/**
 * The header lines a WebSocket handshake response carries under a developer licence: the licence
 * marker on every status line the handshake writes, and the retry hint on a refusal by the workload gate.
 * Each is a complete header line, or empty when the response carries none.
 */
final class CommunityWebSocketLicence {

    private static final String CRLF = "\r\n";
    private static final String MARKER_LINE =
            WorkloadAdmission.LICENCE_HEADER + ": " + WorkloadAdmission.DEVELOPMENT_MARKER + CRLF;
    private static final String RETRY_AFTER_LINE = "Retry-After: 1" + CRLF;

    private CommunityWebSocketLicence() {
    }

    /**
     * The licence marker line.
     *
     * @param marker whether the response carries the marker
     * @return the header line including its CRLF, or the empty string
     */
    /* default */ static String markerLine(boolean marker) {
        return marker ? MARKER_LINE : "";
    }

    /**
     * The retry hint a throttled handshake carries.
     *
     * @param throttled whether the workload gate refused the handshake; a {@code 429} an application
     *                  handshake handler chose is its own and carries no hint of the kernel's
     * @return the header line including its CRLF when the gate refused, otherwise the empty string
     */
    /* default */ static String retryAfterLine(boolean throttled) {
        return throttled ? RETRY_AFTER_LINE : "";
    }
}
