/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import eu.exeris.kernel.core.http.client.KernelWebClient;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link KeySetSource} that loads a JWKS document over HTTP and parses it into a
 * {@code kid -> RSAPublicKey} snapshot for the v0.9 {@link CommunityRotatingKeySet}.
 *
 * <p>The transport detail is injected as a {@link Supplier} of the JWKS JSON text, so this source
 * is unit-testable without a live endpoint. {@link #overWebClient(KernelWebClient, String)} supplies
 * the production fetcher backed by a {@link KernelWebClient} (one GET against the IDP's JWKS path).
 *
 * <h2>Fail-closed (ADR-012)</h2>
 * <p>Any fetch error, unparseable document, or a snapshot with no usable RSA key raises
 * {@link KeySetRefreshException} — never an empty or partial map. RSA is the only Community-tier
 * signing algorithm, so non-RSA JWK entries are skipped rather than failing the whole load.
 *
 * @since 0.10.0
 */
final class CommunityJwksHttpKeySetSource implements KeySetSource {

    private final Supplier<String> jwksJsonFetcher;

    /* default */ CommunityJwksHttpKeySetSource(Supplier<String> jwksJsonFetcher) {
        this.jwksJsonFetcher = Objects.requireNonNull(jwksJsonFetcher, "jwksJsonFetcher must not be null");
    }

    /**
     * Builds a source whose fetcher issues a single {@code GET jwksPath} via {@code client} and
     * returns the raw JWKS JSON. The {@link KernelWebClient}'s engine targets the IDP host.
     *
     * <p>The JWKS document is handed verbatim to the JOSE parser, so {@code client} must decode a
     * {@code String.class} response as the raw response text — wire it with a
     * {@link eu.exeris.kernel.community.http.CommunityTextResponseBodyDecoder}, not a JSON
     * object-mapping decoder (a JWKS advertises {@code application/json}, which a JSON decoder
     * cannot coerce into a {@code String}).
     */
    /* default */ static CommunityJwksHttpKeySetSource overWebClient(KernelWebClient client, String jwksPath) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(jwksPath, "jwksPath must not be null");
        return new CommunityJwksHttpKeySetSource(() -> client.get(jwksPath, String.class));
    }

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
