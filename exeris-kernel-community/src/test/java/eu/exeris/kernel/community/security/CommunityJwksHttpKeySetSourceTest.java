/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
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

        Map<String, JwsVerificationKey> keys = source.load();

        assertThat(keys).containsOnlyKeys(TestJwt.TEST_KID);
        assertThat(keys.get(TestJwt.TEST_KID).algorithm()).isEqualTo(PinnedJwsAlgorithm.RS256);
        assertThat(((RSAPublicKey) keys.get(TestJwt.TEST_KID).publicKey()).getModulus())
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

    private static final String RSA_KID = "rsa-1";
    private static final String OKP_KID = "okp-1";

    private static String rsaJwk(String kid, Algorithm alg, KeyUse use) {
        RSAKey.Builder builder = new RSAKey.Builder(TestJwt.testPublicKey()).keyID(kid);
        if (alg != null) {
            builder.algorithm(alg);
        }
        if (use != null) {
            builder.keyUse(use);
        }
        return builder.build().toJSONString();
    }

    private static String okpJwk(String kid, Curve crv, byte[] x, Algorithm alg, KeyUse use) {
        OctetKeyPair.Builder builder = new OctetKeyPair.Builder(crv, Base64URL.encode(x)).keyID(kid);
        if (alg != null) {
            builder.algorithm(alg);
        }
        if (use != null) {
            builder.keyUse(use);
        }
        return builder.build().toJSONString();
    }

    private static String ed25519Jwk(String kid, Algorithm alg, KeyUse use) {
        return okpJwk(kid, Curve.Ed25519, TestJwt.testEd25519PublicKeyRaw(), alg, use);
    }

    private static String jwks(String... entries) {
        return Arrays.stream(entries).collect(Collectors.joining(",", "{\"keys\":[", "]}"));
    }

    private static Map<String, JwsVerificationKey> load(String... entries) throws Exception {
        return new CommunityJwksHttpKeySetSource(() -> jwks(entries)).load();
    }

    @Test
    @DisplayName("an Ed25519 OKP key with no alg is installed pinned to EdDSA")
    void okpEd25519LoadsPinned() throws Exception {
        Map<String, JwsVerificationKey> keys = load(ed25519Jwk(OKP_KID, null, null));

        assertThat(keys).containsOnlyKeys(OKP_KID);
        assertThat(keys.get(OKP_KID).algorithm()).isEqualTo(PinnedJwsAlgorithm.ED25519);
        byte[] encoded = keys.get(OKP_KID).publicKey().getEncoded();
        assertThat(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length))
                .isEqualTo(TestJwt.testEd25519PublicKeyRaw());
    }

    @Test
    @DisplayName("an RSA key and an Ed25519 key are both installed, each under its own pin")
    void rsaAndOkpBothLoad() throws Exception {
        Map<String, JwsVerificationKey> keys = load(rsaJwk(RSA_KID, null, null), ed25519Jwk(OKP_KID, null, null));

        assertThat(keys).containsOnlyKeys(RSA_KID, OKP_KID);
        assertThat(keys.get(RSA_KID).algorithm()).isEqualTo(PinnedJwsAlgorithm.RS256);
        assertThat(keys.get(OKP_KID).algorithm()).isEqualTo(PinnedJwsAlgorithm.ED25519);
    }

    @Test
    @DisplayName("an X25519 key beside an RSA key is not installed")
    void x25519Skipped() throws Exception {
        assertThat(load(rsaJwk(RSA_KID, null, null), okpJwk("x-1", Curve.X25519, new byte[32], null, null)))
                .containsOnlyKeys(RSA_KID);
    }

    @Test
    @DisplayName("an Ed448 key beside an RSA key is not installed")
    void ed448Skipped() throws Exception {
        assertThat(load(rsaJwk(RSA_KID, null, null), okpJwk("e448-1", Curve.Ed448, new byte[57], null, null)))
                .containsOnlyKeys(RSA_KID);
    }

    @Test
    @DisplayName("a key set holding only an X25519 key has no usable key")
    void x25519OnlyHasNoUsableKeys() {
        var source = new CommunityJwksHttpKeySetSource(
                () -> jwks(okpJwk("x-1", Curve.X25519, new byte[32], null, null)));
        assertThatThrownBy(source::load).isInstanceOf(KeySetRefreshException.class)
                .hasMessageContaining("jwks-no-usable-keys");
    }

    @Test
    @DisplayName("an RSA key declaring alg RS256 is installed")
    void rsaDeclaringRs256Loads() throws Exception {
        assertThat(load(rsaJwk(RSA_KID, JWSAlgorithm.RS256, null))).containsOnlyKeys(RSA_KID);
    }

    @Test
    @DisplayName("an RSA key declaring RSA-OAEP, RS512 or EdDSA is not installed")
    void rsaDeclaringAnotherAlgorithmSkipped() throws Exception {
        for (Algorithm alg : new Algorithm[] {new Algorithm("RSA-OAEP"), JWSAlgorithm.RS512, JWSAlgorithm.EdDSA}) {
            assertThat(load(rsaJwk(RSA_KID, alg, null), ed25519Jwk(OKP_KID, null, null)))
                    .as("RSA alg " + alg.getName()).containsOnlyKeys(OKP_KID);
        }
    }

    @Test
    @DisplayName("an OKP key declaring RS256 is not installed; EdDSA and Ed25519 are")
    void okpDeclaredAlgorithm() throws Exception {
        assertThat(load(ed25519Jwk(OKP_KID, JWSAlgorithm.RS256, null), rsaJwk(RSA_KID, null, null)))
                .containsOnlyKeys(RSA_KID);
        assertThat(load(ed25519Jwk(OKP_KID, JWSAlgorithm.EdDSA, null))).containsOnlyKeys(OKP_KID);
        assertThat(load(ed25519Jwk(OKP_KID, JWSAlgorithm.Ed25519, null))).containsOnlyKeys(OKP_KID);
    }

    @Test
    @DisplayName("a key whose use is enc is not installed; use sig is")
    void keyUseEnforced() throws Exception {
        assertThat(load(rsaJwk("rsa-enc", null, KeyUse.ENCRYPTION), rsaJwk("rsa-sig", null, KeyUse.SIGNATURE)))
                .containsOnlyKeys("rsa-sig");
    }

    @Test
    @DisplayName("a kid published by an RSA key and an OKP key is installed for neither")
    void duplicateKidAcrossKeyTypesDropped() throws Exception {
        assertThat(load(rsaJwk("dup", null, null), ed25519Jwk("dup", null, null), rsaJwk(RSA_KID, null, null)))
                .containsOnlyKeys(RSA_KID);
    }

    @Test
    @DisplayName("a kid published twice by RSA keys is installed for neither copy")
    void duplicateKidWithinRsaDropped() throws Exception {
        assertThat(load(rsaJwk("dup", null, null), rsaJwk("dup", null, null), ed25519Jwk(OKP_KID, null, null)))
                .containsOnlyKeys(OKP_KID);
    }

    @Test
    @DisplayName("an Ed25519 key whose x is 31 bytes fails the load as jwks-key-invalid")
    void shortEd25519KeyFailsLoad() {
        var source = new CommunityJwksHttpKeySetSource(
                () -> jwks(okpJwk(OKP_KID, Curve.Ed25519, new byte[31], null, null), rsaJwk(RSA_KID, null, null)));
        assertThatThrownBy(source::load).isInstanceOf(KeySetRefreshException.class)
                .hasMessage("jwks-key-invalid");
    }

    @Test
    @DisplayName("a symmetric oct key is skipped")
    void octKeySkipped() throws Exception {
        String oct = new OctetSequenceKey.Builder(new byte[32]).keyID("oct-1").algorithm(JWSAlgorithm.HS256)
                .build().toJSONString();
        assertThat(load(oct, rsaJwk(RSA_KID, null, null))).containsOnlyKeys(RSA_KID);
    }

    @Test
    @DisplayName("an EC P-256 key is skipped")
    void ecKeySkipped() throws Exception {
        ECKey ec = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
        assertThat(load(ec.toPublicJWK().toJSONString(), rsaJwk(RSA_KID, null, null))).containsOnlyKeys(RSA_KID);
    }
}
