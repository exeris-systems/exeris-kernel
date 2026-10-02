/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.http.CommunityEndpointScheme;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * The JWKS document's location, read once out of the URI the application configures.
 *
 * <p>The scheme decides the transport the key set travels over ({@link CommunityEndpointScheme}):
 * the keys this document carries decide which tokens authenticate, so they are trusted only as far as
 * the connection that delivered them authenticated its server (ADR-012 §4). The host is lower-cased
 * and loses one trailing dot, the form the transport verifies a name in.
 *
 * @param scheme        the URI's scheme, which decides the fetch engine's transport
 * @param host          the host, lower-cased, without a trailing dot; an IPv6 literal keeps its brackets
 * @param port          the port, the scheme's default when the URI states none
 * @param requestTarget the path and query the document is served from, never empty
 * @since 0.12
 */
/* default */ value record CommunityJwksEndpoint(CommunityEndpointScheme scheme, String host, int port,
                                           String requestTarget) {

    private static final String FIELD = "jwksUri";

    /* default */ CommunityJwksEndpoint {
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(requestTarget, "requestTarget must not be null");
    }

    /**
     * Reads the endpoint out of an absolute {@code http} or {@code https} URI.
     *
     * @param jwksUri the JWKS document's URI
     * @return the endpoint
     * @throws NullPointerException     if {@code jwksUri} is {@code null}
     * @throws IllegalArgumentException if the URI is not absolute {@code http} or {@code https} with a
     *                                  host, or carries user information or a fragment
     */
    // 'of' is the standard Java factory idiom (cf. List.of, Path.of)
    @SuppressWarnings("PMD.ShortMethodName")
    /* default */ static CommunityJwksEndpoint of(URI jwksUri) {
        Objects.requireNonNull(jwksUri, FIELD + " must not be null");
        CommunityEndpointScheme scheme = CommunityEndpointScheme.of(jwksUri.getScheme(), FIELD);
        if (jwksUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(FIELD + " must not carry user information, got: " + jwksUri);
        }
        if (jwksUri.getRawFragment() != null) {
            throw new IllegalArgumentException(FIELD + " must not carry a fragment, got: " + jwksUri);
        }
        return new CommunityJwksEndpoint(
                scheme,
                normalisedHost(jwksUri),
                jwksUri.getPort() < 0 ? scheme.defaultPort() : jwksUri.getPort(),
                requestTarget(jwksUri));
    }

    /**
     * The authority the fetch engine dials: {@code host:port}, with the port always stated, since the
     * client engine requires one.
     *
     * @return the dialled authority
     */
    /* default */ String dialAuthority() {
        return host + ":" + port;
    }

    private static String normalisedHost(URI jwksUri) {
        String raw = jwksUri.getHost();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(FIELD + " must carry a host, got: " + jwksUri);
        }
        String host = raw.toLowerCase(Locale.ROOT);
        String withoutDot = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (withoutDot.isEmpty()) {
            throw new IllegalArgumentException(FIELD + " must carry a host, got: " + jwksUri);
        }
        return withoutDot;
    }

    private static String requestTarget(URI jwksUri) {
        String path = jwksUri.getRawPath();
        String target = path == null || path.isEmpty() ? "/" : path;
        String query = jwksUri.getRawQuery();
        return query == null ? target : target + "?" + query;
    }
}
