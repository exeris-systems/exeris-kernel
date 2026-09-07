/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

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
 * @since 0.5
 * @see Http1RequestParser
 * @see Http1ResponseEncoder
 * @see Http1ChunkedEncoder
 */
public final class Http1Codec {

    /** Sentinel value meaning the request carries no declared body. */
    public static final long NO_BODY = -1L;

    private static final long MIN_CONTENT_LENGTH = 0L;

    /**
     * HTTP/1.1 → HTTP/2 upgrade state after header parsing.
     *
     * @since 0.5
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
    private static final String MSG_INVALID_CONTENT_LENGTH = "HTTP/1.1: invalid Content-Length header";
    private static final String CONNECTION_CLOSE        = "close";
    private static final String UPGRADE_H2C             = "h2c";
    private static final String NO_H2C_SETTINGS         = "";

    /** Visitor for callers that want only the connection state this codec derives. */
    private static final Http1RequestParser.HeaderVisitor DISCARD_HEADERS = (name, value) -> { };

    private boolean keepAlive;
    private long pendingContentLength;
    private UpgradeState upgradeState;
    private String h2cSettingsPayload;

    private final int maxHeaders;
    private final int maxHeaderSize;

    /**
     * Codec enforcing explicit header limits, normally sourced from {@code HttpConfig}.
     *
     * @param maxHeaders    maximum number of header fields per request; must be &gt; 0
     * @param maxHeaderSize maximum byte size of a single header field; must be &gt; 0
     * @throws IllegalArgumentException if either bound is not positive — 0 here is not "unlimited",
     *                                  it refuses every request carrying a header, so it is refused
     *                                  at construction rather than at every request (ADR-071)
     */
    public Http1Codec(int maxHeaders, int maxHeaderSize) {
        if (maxHeaders <= 0) {
            throw new IllegalArgumentException("maxHeaders must be > 0, got: " + maxHeaders);
        }
        if (maxHeaderSize <= 0) {
            throw new IllegalArgumentException("maxHeaderSize must be > 0, got: " + maxHeaderSize);
        }
        this.maxHeaders = maxHeaders;
        this.maxHeaderSize = maxHeaderSize;
        this.keepAlive = true;
        this.pendingContentLength = NO_BODY;
        this.upgradeState = UpgradeState.NONE;
        this.h2cSettingsPayload = NO_H2C_SETTINGS;
    }


    /**
     * Creates a codec with keep-alive enabled by default (HTTP/1.1 semantics) and the parser's
     * built-in DoS limits — {@link Http1RequestParser#DEFAULT_MAX_HEADERS} and
     * {@link Http1RequestParser#DEFAULT_MAX_HEADER_SIZE}.
     *
     * <p>For callers with no {@code HttpConfig} in hand (tests, tooling). Production dispatch uses
     * {@link #Http1Codec(int, int)}, so the operator's configured limits are the ones enforced:
     * until v0.12 every call site used this constructor, which is how
     * {@code http.maxRequestHeaderCount} and {@code http.maxRequestHeaderSize} came to be read,
     * validated, carried on {@code HttpConfig} and enforced nowhere (ADR-071).
     */
    public Http1Codec() {
        this(Http1RequestParser.DEFAULT_MAX_HEADERS, Http1RequestParser.DEFAULT_MAX_HEADER_SIZE);
    }

    /**
     * Resets connection state to HTTP/1.1 defaults, ready for the next request.
     */
    public void reset() {
        this.keepAlive = true;
        this.pendingContentLength = NO_BODY;
        this.upgradeState = UpgradeState.NONE;
        this.h2cSettingsPayload = NO_H2C_SETTINGS;
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
        return h2cSettingsPayload.isEmpty() ? null : h2cSettingsPayload;
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
     * <p>A caller that also needs the header fields themselves should use
     * {@link #parseHeaders(MemorySegment, long, long, Http1RequestParser.HeaderVisitor)}
     * rather than parsing the same block a second time.
     *
     * @param seg     inbound data segment
     * @param offset  offset to first header line (after the request-line CRLF)
     * @param length  available bytes
     * @return byte position after the terminal CRLF CRLF, or {@code -1} if incomplete
     */
    public long parseHeaders(MemorySegment seg, long offset, long length) {
        return parseHeaders(seg, offset, length, DISCARD_HEADERS);
    }

    /**
     * Parses header fields in a single pass, updating connection state, detecting h2c upgrade,
     * and handing each field to {@code visitor}.
     *
     * <p>One pass, not two. The connection state this codec tracks and the header list a server
     * builds are both derived from the same bytes under the same bounds, so deriving them together
     * removes a full re-materialisation of every field. It also removes a hazard rather than only a
     * cost: with two passes the enforced limit depended on which pass reached it first unless both
     * were handed identical bounds (ADR-071), and a single pass cannot express that mistake.
     *
     * <p>{@code visitor} is invoked <em>after</em> the field has updated connection state, so a
     * field this codec rejects — a malformed {@code Content-Length}, say — never reaches it. Fields
     * seen before a limit violation or an incomplete block do reach it; the return value is what
     * says whether the block is usable, and a caller that gets {@code -1} must discard what it
     * collected.
     *
     * <p>The visitor is not retained beyond this call, so a caller may hand over one that closes
     * over a collection it intends to publish as immutable.
     *
     * @param seg     inbound data segment
     * @param offset  offset to first header line (after the request-line CRLF)
     * @param length  available bytes
     * @param visitor callback for each parsed header field; non-null
     * @return byte position after the terminal CRLF CRLF, or {@code -1} if incomplete
     */
    public long parseHeaders(MemorySegment seg, long offset, long length,
                             Http1RequestParser.HeaderVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        HeaderParseState state = new HeaderParseState(visitor);
        long end = Http1RequestParser.parseHeaders(seg, offset, length,
                maxHeaders, maxHeaderSize, state::processHeader);

        if (end >= 0) {
            keepAlive = state.keepAlive;
            pendingContentLength = state.pendingContentLength;
            upgradeState = state.upgradeState();
            h2cSettingsPayload = state.h2cSettingsPayload == null
                    ? NO_H2C_SETTINGS
                    : state.h2cSettingsPayload;
        }
        return end;
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
        private boolean upgrade;
        private boolean settings;
        private boolean connectionUpgrade;
        private boolean connectionSettings;

        private boolean isValidUpgrade() {
            return upgrade && settings && connectionUpgrade && connectionSettings;
        }
    }

    private static final class HeaderParseState {

        private boolean keepAlive = true;
        private long pendingContentLength = NO_BODY;
        private String h2cSettingsPayload;
        private final H2cDetectionContext h2c = new H2cDetectionContext();
        private final Http1RequestParser.HeaderVisitor delegate;

        private HeaderParseState(Http1RequestParser.HeaderVisitor delegate) {
            this.delegate = delegate;
        }

        private void processHeader(String name, String value) {
            updateConnectionState(name, value);
            delegate.onHeader(name, value);
        }

        private void updateConnectionState(String name, String value) {
            if (HEADER_CONTENT_LENGTH.equalsIgnoreCase(name)) {
                pendingContentLength = parseContentLength(value);
                return;
            }
            if (HEADER_CONNECTION.equalsIgnoreCase(name)) {
                processConnectionHeader(value);
                return;
            }
            if (HEADER_UPGRADE.equalsIgnoreCase(name)) {
                markUpgradeIfPresent(value);
                return;
            }
            if (HEADER_HTTP2_SETTINGS.equalsIgnoreCase(name)) {
                h2c.settings = true;
                h2cSettingsPayload = value;
            }
        }

        private long parseContentLength(String value) {
            return HeaderValueParser.parseDeclaredContentLength(value);
        }

        private void processConnectionHeader(String value) {
            if (HeaderValueParser.headerContainsToken(value, CONNECTION_CLOSE)) {
                keepAlive = false;
            }
            if (HeaderValueParser.headerContainsToken(value, HEADER_UPGRADE)) {
                h2c.connectionUpgrade = true;
            }
            if (HeaderValueParser.headerContainsToken(value, HEADER_HTTP2_SETTINGS)) {
                h2c.connectionSettings = true;
            }
        }

        private void markUpgradeIfPresent(String value) {
            if (HeaderValueParser.headerContainsToken(value, UPGRADE_H2C)) {
                h2c.upgrade = true;
            }
        }

        private UpgradeState upgradeState() {
            return h2c.isValidUpgrade() ? UpgradeState.H2C_REQUESTED : UpgradeState.NONE;
        }
    }

    private static final class HeaderValueParser {

        private static long parseDeclaredContentLength(String value) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < MIN_CONTENT_LENGTH) {
                    throw new Http1RequestParser.Http1ParseException(MSG_INVALID_CONTENT_LENGTH, value);
                }
                return parsed;
            } catch (NumberFormatException ex) {
                throw new Http1RequestParser.Http1ParseException(MSG_INVALID_CONTENT_LENGTH, ex, value);
            }
        }

        private static boolean headerContainsToken(String headerValue, String token) {
            int tokenStart = 0;
            while (tokenStart < headerValue.length()) {
                int tokenEnd = nextTokenDelimiter(headerValue, tokenStart);
                if (tokenMatchesIgnoreCase(headerValue, tokenStart, tokenEnd, token)) {
                    return true;
                }
                tokenStart = tokenEnd + 1;
            }
            return false;
        }

        private static int nextTokenDelimiter(String headerValue, int tokenStart) {
            int delimiter = headerValue.indexOf(',', tokenStart);
            return delimiter >= 0 ? delimiter : headerValue.length();
        }

        private static boolean tokenMatchesIgnoreCase(String headerValue, int tokenStart, int tokenEnd, String token) {
            int trimmedStart = skipOptionalWhitespaceForward(headerValue, tokenStart, tokenEnd);
            int trimmedEnd = skipOptionalWhitespaceBackward(headerValue, trimmedStart, tokenEnd);
            int candidateLength = trimmedEnd - trimmedStart;
            return candidateLength == token.length()
                    && headerValue.regionMatches(true, trimmedStart, token, 0, candidateLength);
        }

        private static int skipOptionalWhitespaceForward(String headerValue, int index, int limit) {
            int currentIndex = index;
            while (currentIndex < limit && isOptionalWhitespace(headerValue.charAt(currentIndex))) {
                currentIndex++;
            }
            return currentIndex;
        }

        private static int skipOptionalWhitespaceBackward(String headerValue, int start, int end) {
            int currentIndex = end;
            while (currentIndex > start && isOptionalWhitespace(headerValue.charAt(currentIndex - 1))) {
                currentIndex--;
            }
            return currentIndex;
        }

        private static boolean isOptionalWhitespace(char currentChar) {
            return currentChar == ' ' || currentChar == '\t';
        }
    }
}
