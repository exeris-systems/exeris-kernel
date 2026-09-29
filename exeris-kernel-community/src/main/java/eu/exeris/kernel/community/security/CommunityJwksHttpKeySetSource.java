/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * {@link KeySetSource} that loads a JWKS document over HTTP and parses it into a
 * {@code kid -> RSAPublicKey} snapshot for the v0.9 {@link CommunityRotatingKeySet}.
 *
 * <p>The transport detail is injected as a {@link Supplier} of the JWKS JSON text, so this source
 * is unit-testable without a live endpoint. {@link #overEndpoint(CommunityJwksEndpoint, BiFunction)}
 * supplies the production fetcher: one {@code GET} on a client engine this source builds and owns.
 *
 * <h2>The keys are as trustworthy as the connection</h2>
 * <p>A key in this document authenticates every token signed under its {@code kid}, so a server the
 * fetch never authenticated could mint tokens at will (ADR-012 §4). The engine is therefore built
 * from the endpoint's scheme ({@link CommunityJwksEndpoint#scheme()}), never from what happens to be
 * bound where the provider is assembled: {@code https} is TLS that verifies the server against the
 * endpoint host, or no source at all; {@code http} is plaintext the application asked for by name.
 *
 * <h2>Fail-closed (ADR-012)</h2>
 * <p>Any fetch error, unparseable document, or a snapshot with no usable RSA key raises
 * {@link KeySetRefreshException} — never an empty or partial map. RSA is the only Community-tier
 * signing algorithm, so non-RSA JWK entries are skipped rather than failing the whole load.
 *
 * @since 0.10
 */
final class CommunityJwksHttpKeySetSource implements KeySetSource, AutoCloseable {

    private final Supplier<String> jwksJsonFetcher;
    private final Runnable closeAction;

    /* default */ CommunityJwksHttpKeySetSource(Supplier<String> jwksJsonFetcher) {
        this(jwksJsonFetcher, () -> { });
    }

    private CommunityJwksHttpKeySetSource(Supplier<String> jwksJsonFetcher, Runnable closeAction) {
        this.jwksJsonFetcher = Objects.requireNonNull(jwksJsonFetcher, "jwksJsonFetcher must not be null");
        this.closeAction = closeAction;
    }

    /**
     * Builds a source whose fetcher issues a single {@code GET} for the endpoint's request target on a
     * client engine built for the endpoint ({@link CommunityJwksFetcher}), which the source owns and
     * closes in {@link #close()}.
     *
     * @param endpoint      where the JWKS document is served
     * @param engineFactory builds the engine from its configuration and outbound TLS requirement
     * @return a source that fetches and parses the JWKS document on each {@link #load()} call
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException ({@code EX-NET-4004}) when
     *         the endpoint is {@code https} and no engine that verifies its server can be built
     */
    /* default */ static CommunityJwksHttpKeySetSource overEndpoint(
            CommunityJwksEndpoint endpoint,
            BiFunction<HttpConfig, CommunityOutboundTls, HttpClientEngine> engineFactory) {
        CommunityJwksFetcher fetcher = CommunityJwksFetcher.open(endpoint, engineFactory);
        return new CommunityJwksHttpKeySetSource(fetcher, fetcher::close);
    }

    /** Closes the engine this source built, if it built one; a source over a supplier holds nothing. */
    @Override
    public void close() {
        closeAction.run();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Fetches the JWKS document via this source's injected fetcher, parses it as a
     *           JOSE {@code JWKSet}, and keeps only the RSA entries — Community's only
     *           supported signing algorithm — indexed by {@code kid}; non-RSA entries are
     *           skipped rather than failing the load. Never returns an empty map: an empty or
     *           all-non-RSA document raises {@link KeySetRefreshException}.
     */
    @Override
    public Map<String, RSAPublicKey> load() throws KeySetRefreshException {
        JWKSet jwkSet = parse(fetchJson());
        Map<String, RSAPublicKey> keysByKid = extractRsaKeys(jwkSet);
        if (keysByKid.isEmpty()) {
            throw new KeySetRefreshException("jwks-no-usable-keys");
        }
        return Map.copyOf(keysByKid);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // any fetch failure must deny fail-closed
    private String fetchJson() throws KeySetRefreshException {
        String json;
        try {
            json = jwksJsonFetcher.get();
        } catch (RuntimeException e) {
            throw new KeySetRefreshException("jwks-fetch-failed", e);
        }
        if (json == null || json.isBlank()) {
            throw new KeySetRefreshException("jwks-empty-response");
        }
        return json;
    }

    private static JWKSet parse(String json) throws KeySetRefreshException {
        try {
            return JWKSet.parse(json);
        } catch (ParseException e) {
            throw new KeySetRefreshException("jwks-parse-failed", e);
        }
    }

    private static Map<String, RSAPublicKey> extractRsaKeys(JWKSet jwkSet) throws KeySetRefreshException {
        Map<String, RSAPublicKey> keysByKid = new HashMap<>();
        for (JWK jwk : jwkSet.getKeys()) {
            String kid = jwk.getKeyID();
            if (!KeyType.RSA.equals(jwk.getKeyType()) || kid == null || kid.isBlank()) {
                continue;
            }
            try {
                keysByKid.put(kid, ((RSAKey) jwk).toRSAPublicKey());
            } catch (JOSEException e) {
                throw new KeySetRefreshException("jwks-key-invalid", e);
            }
        }
        return keysByKid;
    }
}
