/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.http2;

/**
 * RFC 7540 §4 — HTTP/2 Frame Types.
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-4">RFC 7540 §4</a>
 */
public enum Http2FrameType {

    DATA(0x00),
    HEADERS(0x01),
    PRIORITY(0x02),
    RST_STREAM(0x03),
    SETTINGS(0x04),
    PUSH_PROMISE(0x05),
    PING(0x06),
    GOAWAY(0x07),
    WINDOW_UPDATE(0x08),
    CONTINUATION(0x09);

    private final int code;

    Http2FrameType(int code) {
        this.code = code;
    }

    /** Returns the 8-bit frame type code (RFC 7540 §4). */
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
