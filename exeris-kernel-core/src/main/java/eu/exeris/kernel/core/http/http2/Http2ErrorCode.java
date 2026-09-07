/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http2;

/**
 * RFC 7540 §7 — HTTP/2 Error Codes.
 *
 * @since 0.5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-7">RFC 7540 §7</a>
 */
public enum Http2ErrorCode {

    /** The condition reported alongside this code is not itself an error, for example a graceful GOAWAY. */
    NO_ERROR(0x00),

    /** An unspecified protocol error was detected; used only when no more specific code applies. */
    PROTOCOL_ERROR(0x01),

    /** The endpoint encountered an unexpected internal error unrelated to the peer's behaviour. */
    INTERNAL_ERROR(0x02),

    /** The peer violated the flow-control protocol described by RFC 7540 §5.2. */
    FLOW_CONTROL_ERROR(0x03),

    /** The peer sent a SETTINGS frame but did not acknowledge one sent to it within the expected time. */
    SETTINGS_TIMEOUT(0x04),

    /** A frame was received for a stream that is already closed. */
    STREAM_CLOSED(0x05),

    /** A frame was received with a size that is invalid for its type or exceeds the negotiated maximum. */
    FRAME_SIZE_ERROR(0x06),

    /** The stream was refused before any application processing took place, so it is safe to retry. */
    REFUSED_STREAM(0x07),

    /** The stream is no longer needed by the endpoint that requests its cancellation. */
    CANCEL(0x08),

    /** The endpoint is unable to maintain the HPACK header-compression context for the connection. */
    COMPRESSION_ERROR(0x09),

    /** The TCP connection established for a CONNECT request was reset or closed abnormally. */
    CONNECT_ERROR(0x0A),

    /** The peer is generating an excessive load; the endpoint is limiting the resulting damage. */
    ENHANCE_YOUR_CALM(0x0B),

    /** The negotiated TLS parameters do not meet the minimum security requirements of RFC 7540 §9.2. */
    INADEQUATE_SECURITY(0x0C),

    /** The endpoint requires that the peer use HTTP/1.1 for this request instead of HTTP/2. */
    HTTP_1_1_REQUIRED(0x0D);

    private final int code;

    Http2ErrorCode(int code) {
        this.code = code;
    }

    /**
     * Returns the 32-bit wire value assigned to this error code by RFC 7540 §7.
     *
     * @return the wire-format error code
     */
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
