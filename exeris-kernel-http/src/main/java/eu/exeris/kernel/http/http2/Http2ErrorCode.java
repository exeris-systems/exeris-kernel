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
 * RFC 7540 §7 — HTTP/2 Error Codes.
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-7">RFC 7540 §7</a>
 */
public enum Http2ErrorCode {

    NO_ERROR(0x00),
    PROTOCOL_ERROR(0x01),
    INTERNAL_ERROR(0x02),
    FLOW_CONTROL_ERROR(0x03),
    SETTINGS_TIMEOUT(0x04),
    STREAM_CLOSED(0x05),
    FRAME_SIZE_ERROR(0x06),
    REFUSED_STREAM(0x07),
    CANCEL(0x08),
    COMPRESSION_ERROR(0x09),
    CONNECT_ERROR(0x0A),
    ENHANCE_YOUR_CALM(0x0B),
    INADEQUATE_SECURITY(0x0C),
    HTTP_1_1_REQUIRED(0x0D);

    private final int code;

    Http2ErrorCode(int code) {
        this.code = code;
    }

    /** Returns the 32-bit error code (RFC 7540 §7). */
    public int code() {
        return code;
    }

    /**
     * Resolves an error code from its wire value.
     *
     * @param code 32-bit error code
     * @return the matching enum value, or {@code null} for unknown codes
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public static Http2ErrorCode fromCode(int code) {
        return switch (code) {
            case 0x00 -> NO_ERROR;
            case 0x01 -> PROTOCOL_ERROR;
            case 0x02 -> INTERNAL_ERROR;
            case 0x03 -> FLOW_CONTROL_ERROR;
            case 0x04 -> SETTINGS_TIMEOUT;
            case 0x05 -> STREAM_CLOSED;
            case 0x06 -> FRAME_SIZE_ERROR;
            case 0x07 -> REFUSED_STREAM;
            case 0x08 -> CANCEL;
            case 0x09 -> COMPRESSION_ERROR;
            case 0x0A -> CONNECT_ERROR;
            case 0x0B -> ENHANCE_YOUR_CALM;
            case 0x0C -> INADEQUATE_SECURITY;
            case 0x0D -> HTTP_1_1_REQUIRED;
            default   -> null;
        };
    }
}
