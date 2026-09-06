/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http2;

/**
 * RFC 7540 §4 — HTTP/2 Frame Types.
 *
 * @since 0.5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-4">RFC 7540 §4</a>
 */
public enum Http2FrameType {

    /** Carries an arbitrary, variable-length sequence of octets associated with a stream (RFC 7540 §6.1). */
    DATA(0x00),

    /** Opens a stream and carries a header block fragment, optionally with priority and padding (RFC 7540 §6.2). */
    HEADERS(0x01),

    /** Advises the sender's priority for a stream (RFC 7540 §6.3). */
    PRIORITY(0x02),

    /** Terminates a stream immediately, carrying the {@link Http2ErrorCode} that caused it (RFC 7540 §6.4). */
    RST_STREAM(0x03),

    /** Conveys connection-level configuration parameters between the two endpoints (RFC 7540 §6.5). */
    SETTINGS(0x04),

    /** Notifies the peer, ahead of time, of a stream the sender intends to initiate (RFC 7540 §6.6). */
    PUSH_PROMISE(0x05),

    /** Measures round-trip time and checks whether an idle connection is still functional (RFC 7540 §6.7). */
    PING(0x06),

    /** Initiates shutdown of a connection or reports a connection-level error (RFC 7540 §6.8). */
    GOAWAY(0x07),

    /** Carries a flow-control window increment for a stream or for the whole connection (RFC 7540 §6.9). */
    WINDOW_UPDATE(0x08),

    /**
     * Continues a header block fragment that a preceding HEADERS or PUSH_PROMISE frame left
     * unterminated (RFC 7540 §6.10).
     */
    CONTINUATION(0x09);

    private final int code;

    Http2FrameType(int code) {
        this.code = code;
    }

    /**
     * Returns the 8-bit wire value assigned to this frame type by RFC 7540 §4.
     *
     * @return the wire-format frame type code
     */
    public int code() {
        return code;
    }

    /**
     * Resolves a frame type from its wire code.
     *
     * @param code 8-bit frame type code
     * @return the matching enum value, or {@code null} for unknown/extension types
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public static Http2FrameType fromCode(int code) {
        return switch (code) {
            case 0x00 -> DATA;
            case 0x01 -> HEADERS;
            case 0x02 -> PRIORITY;
            case 0x03 -> RST_STREAM;
            case 0x04 -> SETTINGS;
            case 0x05 -> PUSH_PROMISE;
            case 0x06 -> PING;
            case 0x07 -> GOAWAY;
            case 0x08 -> WINDOW_UPDATE;
            case 0x09 -> CONTINUATION;
            default   -> null;
        };
    }
}
