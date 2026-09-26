/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * The rule behind {@link TlsPeerIdentity#of(String)}: which authority hosts are IP literals, and
 * what is left of the others for {@link TlsPeerIdentity.DnsName} to validate.
 *
 * <p>An IP literal is recognised before a trailing dot is removed, so {@code 127.0.0.1.} is the DNS
 * name {@code 127.0.0.1}, never the address.
 */
final class TlsPeerIdentityParser {

    private static final char OPEN_BRACKET = '[';
    private static final char CLOSE_BRACKET = ']';
    private static final char TRAILING_DOT = '.';
    private static final int SHORTEST_BRACKETED = 2;

    private TlsPeerIdentityParser() {
    }

    /**
     * {@link TlsPeerIdentity#of(String)}.
     *
     * @param host the host part of an authority
     * @return the identity
     * @throws TlsHandshakeException ({@code EX-NET-2001}, detail
     *         {@link TlsFailureDetail#INVALID_PEER_NAME}) when the host is neither an IP literal
     *         nor a DNS name
     */
    /* default */ static TlsPeerIdentity classify(String host) {
        if (host == null || host.isEmpty()) {
            throw invalid(null);
        }
        if (host.charAt(0) == OPEN_BRACKET) {
            return bracketed(host);
        }
        InetAddress literal = ipLiteralOrNull(host);
        if (literal != null) {
            return new TlsPeerIdentity.IpAddress(literal);
        }
        String name = host.charAt(host.length() - 1) == TRAILING_DOT
                ? host.substring(0, host.length() - 1)
                : host;
        try {
            return new TlsPeerIdentity.DnsName(name);
        } catch (IllegalArgumentException notAName) {
            throw invalid(notAName);
        }
    }

    private static TlsPeerIdentity bracketed(String host) {
        if (host.length() < SHORTEST_BRACKETED || host.charAt(host.length() - 1) != CLOSE_BRACKET) {
            throw invalid(null);
        }
        try {
            return new TlsPeerIdentity.IpAddress(Inet6Address.ofLiteral(host.substring(1, host.length() - 1)));
        } catch (IllegalArgumentException notIpv6) {
            throw invalid(notIpv6);
        }
    }

    private static InetAddress ipLiteralOrNull(String host) {
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException notALiteral) {
            return null;
        }
    }

    private static TlsHandshakeException invalid(IllegalArgumentException cause) {
        return new TlsHandshakeException(TlsFailureDetail.INVALID_PEER_NAME, cause);
    }
}
