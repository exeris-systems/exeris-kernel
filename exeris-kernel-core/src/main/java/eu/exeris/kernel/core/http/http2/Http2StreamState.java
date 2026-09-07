/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http2;

/**
 * RFC 7540 §5.1 — HTTP/2 Stream State Machine.
 *
 * @since 0.5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-5.1">RFC 7540 §5.1</a>
 */
public enum Http2StreamState {

    IDLE,
    RESERVED_LOCAL,
    RESERVED_REMOTE,
    OPEN,
    HALF_CLOSED_LOCAL,
    HALF_CLOSED_REMOTE,
    CLOSED
}
