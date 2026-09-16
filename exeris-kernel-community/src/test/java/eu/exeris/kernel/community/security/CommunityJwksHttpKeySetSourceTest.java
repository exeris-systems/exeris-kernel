/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CommunityJwksHttpKeySetSource} — JWKS parsing and fail-closed behaviour,
 * exercised through an injected JSON fetcher (no live endpoint).
 */
@DisplayName("CommunityJwksHttpKeySetSource — JWKS parse + fail-closed")
class CommunityJwksHttpKeySetSourceTest {

    private static String jwksJson(String kid) {
        RSAPublicKey pub = TestJwt.testPublicKey();
        RSAKey jwk = new RSAKey.Builder(pub).keyID(kid).build();
        return new JWKSet(jwk).toString();
    }

    @Test
    @DisplayName("parses a JWKS document into a kid -> RSA public key snapshot")
    void parsesValidJwks() throws Exception {
        var source = new CommunityJwksHttpKeySetSource(() -> jwksJson(TestJwt.TEST_KID));

        Map<String, RSAPublicKey> keys = source.load();

        assertThat(keys).containsOnlyKeys(TestJwt.TEST_KID);
        assertThat(keys.get(TestJwt.TEST_KID).getModulus())
                .isEqualTo(TestJwt.testPublicKey().getModulus());
    }

    @Test
    @DisplayName("fetch failure is a fail-closed refresh exception")
    void fetchFailureDenies() {
        Supplier<String> failing = () -> {
            throw new IllegalStateException("network down");
        };
        var source = new CommunityJwksHttpKeySetSource(failing);

        assertThatThrownBy(source::load).isInstanceOf(KeySetRefreshException.class);
    }

    @Test
    @DisplayName("blank response denies fail-closed")
    void blankResponseDenies() {
        var source = new CommunityJwksHttpKeySetSource(() -> "   ");
        assertThatThrownBy(source::load).isInstanceOf(KeySetRefreshException.class);
    }

    @Test
    @DisplayName("unparseable document denies fail-closed")
    void unparseableDenies() {
        var source = new CommunityJwksHttpKeySetSource(() -> "not-a-jwks");
        assertThatThrownBy(source::load).isInstanceOf(KeySetRefreshException.class);
    }

    @Test
    @DisplayName("a key set with no usable RSA key denies fail-closed (never an empty map)")
    void noUsableKeysDenies() {
        var source = new CommunityJwksHttpKeySetSource(() -> new JWKSet().toString());
        assertThatThrownBy(source::load).isInstanceOf(KeySetRefreshException.class);
    }
}
