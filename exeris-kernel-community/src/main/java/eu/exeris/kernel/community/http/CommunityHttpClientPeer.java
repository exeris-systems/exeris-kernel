/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

/**
 * The dialled endpoint plus the authority it came from, which the {@code Host} header follows.
 *
 * <p>Extracted from {@link CommunityHttpClientEngine} so that authority parsing is self-contained
 * and engine cyclomatic complexity stays strictly within PMD bounds (ADR-074).
 *
 * @param host      the target host name or bracketed IPv6 literal
 * @param port      the target TCP port (1-65535)
 * @param authority the original normalized authority string
 * @since 0.12
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
record CommunityHttpClientPeer(String host, int port, String authority) {

    private static final int MAX_PORT = 65_535;

    /* default */ static CommunityHttpClientPeer parse(String authority) {
        int close = authority.startsWith("[") ? authority.indexOf(']') : -1;
        if (authority.startsWith("[") && close < 0) {
            throw new IllegalStateException("Unterminated IPv6 literal in authority: " + authority);
        }
        int separator = close >= 0 ? authority.indexOf(':', close) : authority.lastIndexOf(':');
        if (separator <= 0 || separator == authority.length() - 1) {
            throw new IllegalStateException(
                    "Authority must carry an explicit port (host:port), got: " + authority);
        }
        String host = authority.substring(0, separator);
        // An unbracketed IPv6 literal is not merely unusual, it is AMBIGUOUS: "::1:8080" is a
        // valid IPv6 address in its own right, so reading it as host "::1" port 8080 is a guess.
        // RFC 3986 requires the bracketed form for exactly this reason, and guessing is what
        // ADR-074 exists to remove. Note the bracketed host keeps its brackets — InetSocketAddress
        // accepts them (measured), so stripping would be work that also loses the disambiguation.
        if (close < 0 && host.indexOf(':') >= 0) {
            throw new IllegalStateException(
                    "IPv6 authority must be bracketed as [address]:port, got: " + authority);
        }
        int port;
        try {
            port = Integer.parseInt(authority.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Authority port is not a number: " + authority, e);
        }
        if (port <= 0 || port > MAX_PORT) {
            throw new IllegalStateException("Authority port out of range: " + authority);
        }
        return new CommunityHttpClientPeer(host, port, authority);
    }
}
