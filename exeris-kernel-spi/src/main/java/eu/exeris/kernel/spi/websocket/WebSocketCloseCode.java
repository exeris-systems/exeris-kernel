/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

/**
 * SPI: RFC 6455 §7.4.1 close codes, as far as this contract uses them.
 *
 * <p>Surfaced because a transport that can only say "closed" cannot distinguish a peer that went
 * away from a peer that broke the protocol, and those need different operator responses. The driving
 * case is specific (ADR-084 §8): a consumer needs an exit without a prior shutdown to be reportable
 * as a protocol error, the way a stdio-transported tool reports it with exit code 1.
 *
 * <p>Only codes this contract can produce or observe are modelled. RFC 6455 forbids 1005 and 1006 on
 * the wire — they describe a local observation — so they appear here for what a handler may be
 * <em>told</em>, never for what it may send. {@link #sendable()} carries that distinction rather
 * than leaving it to a comment nobody reads at the call site.
 *
 * @since 0.12
 */
public enum WebSocketCloseCode {

    /** 1000 — the purpose is fulfilled; the ordinary end of a session. */
    NORMAL_CLOSURE(1000, true),

    /** 1001 — the endpoint is going away: server shutting down, browser navigating away. */
    GOING_AWAY(1001, true),

    /** 1002 — a protocol error; what a binary frame on a text-only contract closes with. */
    PROTOCOL_ERROR(1002, true),

    /** 1003 — data the endpoint will not accept, as distinct from data it cannot parse. */
    UNSUPPORTED_DATA(1003, true),

    /**
     * 1007 — the payload was not what the frame's type promised. For a text frame that means the
     * bytes are not valid UTF-8, which RFC 6455 §8.1 requires closing on rather than substituting a
     * replacement character and handing the application something the peer did not send.
     *
     * <p>Added in the same milestone as the rest, once implementing the frame codec showed the enum
     * could not express a case the specification mandates.
     */
    INVALID_PAYLOAD_DATA(1007, true),

    /**
     * 1005 — the peer closed with no status. Never sent. This is the observation a consumer needs to
     * call an exit-without-shutdown a protocol fault rather than a clean goodbye.
     */
    NO_STATUS_RECEIVED(1005, false),

    /** 1006 — the connection was lost abnormally. Never sent; a local observation only. */
    ABNORMAL_CLOSURE(1006, false),

    /** 1009 — the message exceeded what this endpoint accepts (see {@code WebSocketConfig}). */
    MESSAGE_TOO_BIG(1009, true),

    /** 1011 — an unexpected condition the endpoint cannot recover from. */
    INTERNAL_ERROR(1011, true);

    private final int code;
    private final boolean sendable;

    WebSocketCloseCode(int code, boolean sendable) {
        this.code = code;
        this.sendable = sendable;
    }

    /**
     * Returns the wire value.
     *
     * @return the RFC 6455 close code
     */
    public int code() {
        return code;
    }

    /**
     * Returns whether this code may be sent, as opposed to only observed.
     *
     * @return {@code false} for 1005 and 1006, which RFC 6455 forbids on the wire
     */
    public boolean sendable() {
        return sendable;
    }
}
