/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http2;

/**
 * RFC 7540 §5.1 — HTTP/2 Stream State Machine.
 *
 * @since 0.5.0
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
