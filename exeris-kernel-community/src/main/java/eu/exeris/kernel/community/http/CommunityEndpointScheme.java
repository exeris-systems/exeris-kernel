/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.transport.CommunityOutboundTls;

/**
 * The scheme of an endpoint a Community client dials, and what it decides: the default port, and
 * what the client engine requires of its transport.
 *
 * <p>Community-internal. An owner whose engine dials one configured endpoint — the S3 blob client,
 * the OIDC provider's JWKS fetch — reads the scheme off the endpoint URI and builds its engine with
 * {@link #outboundTls()}, so the scheme the operator wrote decides the transport wherever the owner
 * is built. {@code http} is plaintext, even where a crypto provider is bound. {@code https} is TLS
 * that verifies the server against the endpoint host, or no engine. It is never downgraded.
 *
 * @since 0.12
 */
public enum CommunityEndpointScheme {

    /** Plaintext, whatever crypto provider is bound where the engine is built. */
    HTTP("http", 80, CommunityOutboundTls.PLAINTEXT),

    /** TLS that verifies the endpoint host, or no engine. */
    HTTPS("https", 443, CommunityOutboundTls.VERIFIED);

    private final String token;
    private final int defaultPort;
    private final CommunityOutboundTls outboundTls;

    CommunityEndpointScheme(String token, int defaultPort, CommunityOutboundTls outboundTls) {
        this.token = token;
        this.defaultPort = defaultPort;
        this.outboundTls = outboundTls;
    }

    /**
     * The scheme as a URI spells it.
     *
     * @return {@code http} or {@code https}
     */
    public String token() {
        return token;
    }

    /**
     * The port an endpoint that states none is reached on.
     *
     * @return {@code 80} or {@code 443}
     */
    public int defaultPort() {
        return defaultPort;
    }

    /**
     * What the client engine requires of its transport for an endpoint of this scheme.
     *
     * @return {@link CommunityOutboundTls#PLAINTEXT} or {@link CommunityOutboundTls#VERIFIED}
     */
    public CommunityOutboundTls outboundTls() {
        return outboundTls;
    }

    /**
     * The scheme an endpoint URI names, in any case.
     *
     * @param token the URI's scheme, or {@code null}
     * @param field what the endpoint is called in the refusal, such as {@code location}
     * @return the scheme
     * @throws IllegalArgumentException if {@code token} is neither {@code http} nor {@code https}
     */
    // 'of' is the standard Java factory idiom (cf. List.of, Path.of)
    @SuppressWarnings("PMD.ShortMethodName")
    public static CommunityEndpointScheme of(String token, String field) {
        for (CommunityEndpointScheme scheme : values()) {
            if (scheme.token.equalsIgnoreCase(token)) {
                return scheme;
            }
        }
        throw new IllegalArgumentException(field + " must use the http or https scheme, got: " + token);
    }
}
