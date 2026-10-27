/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.RSAKey;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;

import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * {@link KeySetSource} that loads a JWKS document over HTTP and parses it into a
 * {@code kid -> JwsVerificationKey} snapshot for the v0.9 {@link CommunityRotatingKeySet}.
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
 * <p>Any fetch error, unparseable document, or a snapshot with no usable key raises
 * {@link KeySetRefreshException} — never an empty or partial map. A key verifies one algorithm, fixed
 * by its JWK ({@link PinnedJwsAlgorithm#fromJwk}); a JWK this validator cannot verify with is skipped
 * rather than failing the whole load, because a JWKS routinely carries keys for other purposes. A JWK
 * is not installed when it
 * <ul>
 *   <li>has no {@code kid}, or a {@code kid} that appears more than once in the document (which of
 *       two keys a token means is undecidable, so neither verifies);</li>
 *   <li>declares {@code use} other than {@code sig};</li>
 *   <li>is not an RSA key or an Ed25519 {@code OKP} key, or declares an {@code alg} other than the
 *       one its key type verifies.</li>
 * </ul>
 * A JWK of a supported type whose key material is invalid fails the load.
 *
 * @since 0.10
 */
// CyclomaticComplexity: the entry filter is one list of independent, documented rules (kid, use, key type,
// curve, algorithm, repeated kid), each a branch the class contract names.
@SuppressWarnings("PMD.CyclomaticComplexity")
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
     *           JOSE {@code JWKSet}, and keeps the entries that qualify under the class rules
     *           (signing use, unique {@code kid}, RS256 over RSA or EdDSA over Ed25519), each
     *           pinned to its algorithm; other entries are skipped rather than failing the load.
     *           Never returns an empty map: a document with no qualifying entry raises
     *           {@link KeySetRefreshException}.
     */
    @Override
    public Map<String, JwsVerificationKey> load() throws KeySetRefreshException {
        JWKSet jwkSet = parse(fetchJson());
        Map<String, JwsVerificationKey> keysByKid = extractVerificationKeys(jwkSet);
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

    private static Map<String, JwsVerificationKey> extractVerificationKeys(JWKSet jwkSet)
            throws KeySetRefreshException {
        Map<String, JwsVerificationKey> keysByKid = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new HashSet<>();
        for (JWK jwk : jwkSet.getKeys()) {
            String kid = jwk.getKeyID();
            if (kid == null || kid.isBlank()) {
                continue;
            }
            if (!seen.add(kid)) {
                repeated.add(kid);
            }
            JwsVerificationKey key = verificationKeyOf(jwk);
            if (key != null) {
                keysByKid.put(kid, key);
            }
        }
        // A kid published twice is ambiguous whichever entry would win, so no entry with it verifies.
        keysByKid.keySet().removeAll(repeated);
        return keysByKid;
    }

    private static JwsVerificationKey verificationKeyOf(JWK jwk) throws KeySetRefreshException {
        KeyUse use = jwk.getKeyUse();
        if (use != null && !KeyUse.SIGNATURE.equals(use)) {
            return null;
        }
        Curve curve = jwk instanceof OctetKeyPair okp ? okp.getCurve() : null;
        PinnedJwsAlgorithm pin = PinnedJwsAlgorithm.fromJwk(jwk.getKeyType(), curve, jwk.getAlgorithm());
        if (pin == null) {
            return null;
        }
        try {
            return switch (pin) {
                case RS256 -> JwsVerificationKey.rs256(((RSAKey) jwk).toRSAPublicKey());
                case ED25519 ->
                    JwsVerificationKey.ed25519(Ed25519PublicKeys.fromRaw(((OctetKeyPair) jwk).getDecodedX()));
            };
        } catch (JOSEException | GeneralSecurityException | IllegalArgumentException e) {
            throw new KeySetRefreshException("jwks-key-invalid", e);
        }
    }
}
