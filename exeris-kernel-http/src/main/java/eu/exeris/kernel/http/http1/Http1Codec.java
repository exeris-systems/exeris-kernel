/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http1;

import java.lang.foreign.MemorySegment;

/**
 * RFC 9112 — HTTP/1.1 Connection Codec.
 *
 * <h2>Responsibility</h2>
 * <p>Coordinates {@link Http1RequestParser}, {@link Http1ResponseEncoder}, and
 * {@link Http1ChunkedEncoder} across the lifetime of a single persistent
 * HTTP/1.1 connection. Tracks connection state (keep-alive vs. close),
 * whether a request body is expected, and detects HTTP/1.1 → HTTP/2
 * cleartext upgrade requests (h2c, RFC 7540 §3.2).
 *
 * <h2>h2c Upgrade Detection</h2>
 * <p>After {@link #parseHeaders} returns a positive position, callers MUST
 * check {@link #upgradeState()}:
 * <ul>
 *   <li>{@link UpgradeState#NONE} — standard HTTP/1.1 request, no upgrade.</li>
 *   <li>{@link UpgradeState#H2C_REQUESTED} — the request carries a valid
 *       {@code Upgrade: h2c} + {@code HTTP2-Settings} header. The raw
 *       base64url-encoded SETTINGS payload is available via
 *       {@link #h2cSettingsPayload()}. The transport layer MUST:
 *       <ol>
 *         <li>Call {@link #writeH2cSwitchingProtocols} to emit
 *             {@code 101 Switching Protocols}.</li>
 *         <li>Base64url-decode {@link #h2cSettingsPayload()} and apply it as
 *             the initial remote SETTINGS frame.</li>
 *         <li>Send the server connection preface (SETTINGS frame) and then
 *             respond to the original request over HTTP/2 stream 1.</li>
 *       </ol>
 *   </li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. Each HTTP/1.1 connection must own exactly one instance.
 *
 * @since 0.5.0
 * @see Http1RequestParser
 * @see Http1ResponseEncoder
 * @see Http1ChunkedEncoder
 */
public final class Http1Codec {

    /** Sentinel value meaning the request carries no declared body. */
    public static final long NO_BODY = -1L;

    /**
     * HTTP/1.1 → HTTP/2 upgrade state after header parsing.
     *
     * @since 0.5.0
     */
    public enum UpgradeState {
        /** No upgrade header present — standard HTTP/1.1 request. */
        NONE,
        /**
         * Valid {@code Upgrade: h2c} + {@code HTTP2-Settings} headers present.
         * The transport layer MUST respond with 101 and switch protocols.
         */
        H2C_REQUESTED
    }

    private static final String HEADER_CONTENT_LENGTH   = "content-length";
    private static final String HEADER_CONNECTION       = "connection";
    private static final String HEADER_UPGRADE          = "upgrade";
    private static final String HEADER_HTTP2_SETTINGS   = "http2-settings";
    private static final String CONNECTION_CLOSE        = "close";
    private static final String UPGRADE_H2C             = "h2c";

    private boolean keepAlive;
    private long pendingContentLength;
    private UpgradeState upgradeState;
    private String h2cSettingsPayload;

    /**
     * Creates a codec with keep-alive enabled by default (HTTP/1.1 semantics).
     */
    public Http1Codec() {
        this.keepAlive = true;
        this.pendingContentLength = NO_BODY;
        this.upgradeState = UpgradeState.NONE;
    }

    /**
     * Returns {@code true} if the connection should persist after the current
     * request/response exchange.
     *
     * @return {@code true} for persistent connection
     */
    public boolean isKeepAlive() {
        return keepAlive;
    }

    /**
     * Returns the {@code Content-Length} declared by the most recently parsed
     * request, or {@link #NO_BODY} if the request carries no body.
     *
     * @return declared content length in bytes, or {@code -1}
     */
    public long pendingContentLength() {
        return pendingContentLength;
    }

    /**
     * Returns the h2c upgrade state detected during the last {@link #parseHeaders} call.
     *
     * @return upgrade state; {@link UpgradeState#NONE} until headers have been parsed
     */
    public UpgradeState upgradeState() {
        return upgradeState;
    }

    /**
     * Returns the raw base64url-encoded value of the {@code HTTP2-Settings} header
     * when {@link #upgradeState()} is {@link UpgradeState#H2C_REQUESTED}.
     *
     * <p>The transport layer MUST base64url-decode this value and treat it as the
     * initial SETTINGS payload from the client (RFC 7540 §3.2). The payload may be
     * empty (zero-length string), which is valid.
     *
     * @return base64url-encoded SETTINGS payload, or {@code null} if not an h2c upgrade
     */
    public String h2cSettingsPayload() {
        return h2cSettingsPayload;
    }

    /**
     * Parses the request-line from an inbound segment.
     *
     * @param seg    inbound data segment
     * @param offset start offset
     * @param length available bytes
     * @return parsed request-line, or {@code null} if incomplete
     */
    public Http1RequestParser.RequestLine parseRequestLine(MemorySegment seg,
                                                           long offset, long length) {
        return Http1RequestParser.parseRequestLine(seg, offset, length);
    }

    /**
     * Parses header fields, updating connection state and detecting h2c upgrade.
     *
     * <p>After this method returns a positive value, callers MUST check
     * {@link #upgradeState()} before proceeding with HTTP/1.1 processing.
     *
     * @param seg     inbound data segment
     * @param offset  offset to first header line (after the request-line CRLF)
     * @param length  available bytes
     * @return byte position after the terminal CRLF CRLF, or {@code -1} if incomplete
     */
    @SuppressWarnings("PMD.NullAssignment")
    public long parseHeaders(MemorySegment seg, long offset, long length) {
        pendingContentLength = NO_BODY;
        upgradeState = UpgradeState.NONE;
        h2cSettingsPayload = null;
        keepAlive = true;

        H2cDetectionContext ctx = new H2cDetectionContext();
        long end = Http1RequestParser.parseHeaders(seg, offset, length,
                (name, value) -> processHeader(name.strip(), value.strip(), ctx));

        if (ctx.isValidUpgrade()) {
            upgradeState = UpgradeState.H2C_REQUESTED;
        }
        return end;
    }

    private void processHeader(String name, String value, H2cDetectionContext ctx) {
        if (HEADER_CONTENT_LENGTH.equalsIgnoreCase(name)) {
            try {
                pendingContentLength = Long.parseLong(value);
            } catch (NumberFormatException _) {
                pendingContentLength = NO_BODY;
            }
        } else if (HEADER_CONNECTION.equalsIgnoreCase(name)) {
            processConnectionHeader(value, ctx);
        } else if (HEADER_UPGRADE.equalsIgnoreCase(name)) {
            if (headerHasToken(value, UPGRADE_H2C)) {
                ctx.upgrade = true;
            }
        } else if (HEADER_HTTP2_SETTINGS.equalsIgnoreCase(name)) {
            ctx.settings = true;
            h2cSettingsPayload = value;
        }
    }

    private void processConnectionHeader(String value, H2cDetectionContext ctx) {
        if (headerHasToken(value, CONNECTION_CLOSE)) {
            keepAlive = false;
        }
        if (headerHasToken(value, HEADER_UPGRADE)) {
            ctx.connectionUpgrade = true;
        }
        if (headerHasToken(value, HEADER_HTTP2_SETTINGS)) {
            ctx.connectionSettings = true;
        }
    }

    private static boolean headerHasToken(String headerValue, String token) {
        int start = 0;
        final int len = headerValue.length();
        while (start < len) {
            int comma = headerValue.indexOf(',', start);
            String t = (comma == -1
                    ? headerValue.substring(start)
                    : headerValue.substring(start, comma)).strip();
            if (token.equalsIgnoreCase(t)) {
                return true;
            }
            if (comma == -1) {
                break;
            }
            start = comma + 1;
        }
        return false;
    }

    /**
     * Writes an HTTP/1.1 status-line and a {@code Connection} header reflecting
     * the current keep-alive state.
     *
     * @param output     destination segment
     * @param offset     byte offset
     * @param statusCode HTTP status code
     * @param reason     reason phrase
     * @return new byte position after the Connection header CRLF
     */
    public long writeStatusAndConnection(MemorySegment output, long offset,
                                         int statusCode, String reason) {
        long pos = Http1ResponseEncoder.writeStatusLine(output, offset, statusCode, reason);
        String connectionValue = keepAlive ? "keep-alive" : CONNECTION_CLOSE;
        return Http1ResponseEncoder.writeHeader(output, pos, "Connection", connectionValue);
    }

    /**
     * Writes the {@code 101 Switching Protocols} response required to complete
     * an h2c upgrade (RFC 7540 §3.2).
     *
     * <p>The response includes:
     * <ul>
     *   <li>{@code HTTP/1.1 101 Switching Protocols\r\n}</li>
     *   <li>{@code Connection: Upgrade\r\n}</li>
     *   <li>{@code Upgrade: h2c\r\n}</li>
     *   <li>{@code \r\n} — blank line terminating the header block</li>
     * </ul>
     *
     * <p>After writing this response the transport layer MUST immediately switch
     * to HTTP/2 framing. No further HTTP/1.1 frames are valid on this connection.
     *
     * @param output destination segment
     * @param offset byte offset
     * @return new byte position after the terminal CRLF
     */
    public long writeH2cSwitchingProtocols(MemorySegment output, long offset) {
        long pos = Http1ResponseEncoder.writeStatusLine(output, offset, 101, "Switching Protocols");
        pos = Http1ResponseEncoder.writeHeader(output, pos, "Connection", "Upgrade");
        pos = Http1ResponseEncoder.writeHeader(output, pos, "Upgrade", "h2c");
        return Http1ResponseEncoder.writeHeaderEnd(output, pos);
    }

    private static final class H2cDetectionContext {
        boolean upgrade;
        boolean settings;
        boolean connectionUpgrade;
        boolean connectionSettings;

        boolean isValidUpgrade() {
            return upgrade && settings && connectionUpgrade && connectionSettings;
        }
    }
}
