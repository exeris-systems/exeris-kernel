/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrustedIssuerKeyStore")
class TrustedIssuerKeyStoreTest {

    /**
     * RFC 8032 §7.1 test vectors, secret key to public key. Their secret keys are published, so a
     * trust root equal to any of these public keys lets anyone sign a manifest that verifies.
     */
    private static final Map<String, String> RFC8032_TEST_VECTORS = Map.of(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "f5e5767cf153319517630f226876b86c8160cc583bc013744c6bf255f5cc0ee5",
            "278117fc144c72340f67d0f2316e8386ceffbf2b2428c9c51fef7c597f1d426e",
            "833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42",
            "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf"
    );

    @Test
    @DisplayName("The store pins exactly the production, developer and enterprise keys of the 2026 epoch")
    void pinsExactlyThreeKeys() {
        Map<String, String> pinned = TrustedIssuerKeyStore.trustedKeyIds().stream()
                .collect(Collectors.toMap(keyId -> keyId,
                        keyId -> HexFormat.of().formatHex(TrustedIssuerKeyStore.getPublicKeyBytes(keyId))));

        assertThat(pinned).containsExactlyInAnyOrderEntriesOf(Map.of(
                "exeris-prod-2026-k1", "bbba57668037f6dc4f1c305a7fac2d2ba0a506611dd6b70bdb9a3ce24a5365c9",
                "exeris-dev-2026-k1", "2c15e5013d02a45c579e1021aec554292a47b29ac480ae2366df276b547da92e",
                "exeris-ent-2026-k1", "5c07d772c227fafc491d7c5d5ef0fbcd18830872fc401f4a1f6dcbec23bb6027"));
    }

    @Test
    @DisplayName("Every keyId names its family, and its scope is the constant of that family")
    void everyKeyHoldsTheScopeOfItsFamily() {
        Pattern naming = Pattern.compile("exeris-(prod|dev|ent)-\\d{4}-k[1-9]\\d*");
        Map<String, IssuerKeyScope> byFamily = Map.of(
                "prod", IssuerKeyScope.PROD, "dev", IssuerKeyScope.DEV, "ent", IssuerKeyScope.ENT);

        for (String keyId : TrustedIssuerKeyStore.trustedKeyIds()) {
            Matcher family = naming.matcher(keyId);
            assertThat(family.matches()).as("%s follows the naming scheme", keyId).isTrue();
            TrustedIssuerKey key = TrustedIssuerKeyStore.resolve(keyId);
            assertThat(key.keyId()).isEqualTo(keyId);
            assertThat(key.scope()).as("scope of %s", keyId).isEqualTo(byFamily.get(family.group(1)));
        }
        assertThat(TrustedIssuerKeyStore.trustedKeyIds().stream().map(id -> id.split("-")[1]))
                .containsExactlyInAnyOrder("prod", "dev", "ent");
    }

    @Test
    @DisplayName("Only the developer key has a workload ceiling, so only it runs under the developer envelope")
    void onlyTheDeveloperKeyIsDeveloperScoped() {
        assertThat(TrustedIssuerKeyStore.resolve("exeris-dev-2026-k1").scope().isDeveloperScope()).isTrue();
        assertThat(TrustedIssuerKeyStore.resolve("exeris-prod-2026-k1").scope().isDeveloperScope()).isFalse();
        assertThat(TrustedIssuerKeyStore.resolve("exeris-ent-2026-k1").scope().isDeveloperScope()).isFalse();
    }

    @Test
    @DisplayName("The embedded resolver resolves every pinned key with its scope")
    void embeddedResolverServesThePinnedKeys() {
        for (String keyId : TrustedIssuerKeyStore.trustedKeyIds()) {
            assertThat(IssuerKeyResolver.embedded().resolve(keyId))
                    .usingRecursiveComparison().isEqualTo(TrustedIssuerKeyStore.resolve(keyId));
        }
        assertThatThrownBy(() -> IssuerKeyResolver.embedded().resolve("exeris-root-2026-k1"))
                .isInstanceOf(ContractBreachException.class);
    }

    @Test
    @DisplayName("The retired root is no longer a trust root: EX-LIC-0002 from every accessor")
    void retiredRootIsUnknown() {
        assertThat(TrustedIssuerKeyStore.trustedKeyIds()).doesNotContain("exeris-root-2026-k1");
        assertThatThrownBy(() -> TrustedIssuerKeyStore.resolve("exeris-root-2026-k1"))
                .isInstanceOfSatisfying(ContractBreachException.class, breach -> {
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0002);
                    assertThat(breach.rawArgs()).containsExactly("exeris-root-2026-k1");
                });
        assertThatThrownBy(() -> TrustedIssuerKeyStore.getPublicKeyBytes("exeris-root-2026-k1"))
                .isInstanceOf(ContractBreachException.class);
        assertThatThrownBy(() -> TrustedIssuerKeyStore.getPublicKey("exeris-root-2026-k1"))
                .isInstanceOf(ContractBreachException.class);
        assertThatThrownBy(() -> TrustedIssuerKeyStore.resolve(null)).isInstanceOf(ContractBreachException.class);
    }

    @Test
    @DisplayName("No embedded trust root is an RFC 8032 test-vector key")
    void noEmbeddedRootIsAPublishedTestVector() {
        Set<String> embedded = TrustedIssuerKeyStore.trustedKeyIds().stream()
                .map(keyId -> HexFormat.of().formatHex(TrustedIssuerKeyStore.getPublicKeyBytes(keyId)))
                .collect(Collectors.toSet());

        assertThat(embedded).isNotEmpty().doesNotContainAnyElementsOf(RFC8032_TEST_VECTORS.values());
    }

    @Test
    @DisplayName("Every embedded trust root decodes as an Ed25519 public key")
    void everyEmbeddedRootDecodes() {
        for (String keyId : TrustedIssuerKeyStore.trustedKeyIds()) {
            assertThat(TrustedIssuerKeyStore.getPublicKey(keyId).getAlgorithm()).isIn("Ed25519", "EdDSA");
        }
    }

    @Test
    @DisplayName("A schema-valid manifest signed with a published RFC 8032 secret fails on its signature or its key")
    void manifestSignedWithPublishedSecretIsRejected() throws Exception {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        for (String seedHex : RFC8032_TEST_VECTORS.keySet()) {
            KeyPair published = keyPairFromSeed(HexFormat.of().parseHex(seedHex));

            for (String realKeyId : TrustedIssuerKeyStore.trustedKeyIds()) {
                String underRealRoot = signedManifest(realKeyId, published.getPrivate());
                assertThat(LicenseManifestVerifier.verify(underRealRoot, at,
                        IssuerKeyResolver.of(Map.of(realKeyId, published.getPublic()))))
                        .as("control: the forged manifest is schema-valid, so only the trust root can refuse it")
                        .isNotNull();
                assertThatThrownBy(() -> LicenseManifestVerifier.verify(underRealRoot, at))
                        .isInstanceOf(ContractBreachException.class)
                        .hasMessageContaining("signature verification failed")
                        .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                                .isEqualTo(KernelErrorCodes.EX_LIC_0001));
            }

            String underUnknownRoot = signedManifest("exeris-prod-2025-k1", published.getPrivate());
            assertThatThrownBy(() -> LicenseManifestVerifier.verify(underUnknownRoot, at))
                    .isInstanceOf(ContractBreachException.class)
                    .satisfies(e -> {
                        ContractBreachException breach = (ContractBreachException) e;
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0002);
                        assertThat(breach.rawArgs()).containsExactly("exeris-prod-2025-k1");
                    });
        }
    }

    @Test
    @DisplayName("Changing the array getPublicKeyBytes returns does not change the trust root")
    void returnedKeyBytesAreACopy() {
        for (String keyId : TrustedIssuerKeyStore.trustedKeyIds()) {
            byte[] handedOut = TrustedIssuerKeyStore.getPublicKeyBytes(keyId);
            byte[] original = handedOut.clone();
            Arrays.fill(handedOut, (byte) 0);

            assertThat(TrustedIssuerKeyStore.getPublicKeyBytes(keyId)).isEqualTo(original);
        }
        assertThat(TrustedIssuerKeyStore.trustedKeyIds()).isNotEmpty();
    }

    @Test
    @DisplayName("The key set cannot be changed at runtime: no public mutator, every static field final")
    void keySetIsFixedAtBuildTime() {
        Set<String> publicMethods = Arrays.stream(TrustedIssuerKeyStore.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(publicMethods)
                .containsExactlyInAnyOrder("getPublicKeyBytes", "getPublicKey", "resolve", "trustedKeyIds");

        for (Field field : TrustedIssuerKeyStore.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final", field.getName())
                        .isTrue();
            }
        }
        assertThatThrownBy(() -> TrustedIssuerKeyStore.trustedKeyIds().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Unknown keyId fails with EX-LIC-0002")
    void unknownKeyIdIsRejected() {
        assertThatThrownBy(() -> TrustedIssuerKeyStore.getPublicKey("not-a-trust-root"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0002));
    }

    private static KeyPair keyPairFromSeed(byte[] seed) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(255, new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, bytes.length);
            }
        });
        return generator.generateKeyPair();
    }

    private static String signedManifest(String keyId, PrivateKey key) throws Exception {
        String unsigned = "{\"$schema\":\"" + LicenseManifestVerifier.SCHEMA_V1 + "\","
                + "\"contract\":{\"id\":\"FORGED-1\",\"commercialModel\":\"OEM\"},"
                + "\"entitlement\":{\"edition\":\"enterprise\",\"sku\":\"forged\","
                + "\"licenseMode\":\"PERPETUAL_INTERNAL\",\"capabilities\":[\"gateway.core\"]},"
                + "\"execution\":{\"environments\":[\"production\"],\"authorizedInstances\":1000,"
                + "\"validFrom\":\"2026-01-01T00:00:00Z\"},"
                + "\"issuer\":{\"keyId\":\"" + keyId + "\",\"issuedAt\":\"2026-09-12T12:00:00Z\"}}";
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(JsonCanonicalizer.canonicalize(unsigned));
        String value = Base64.getEncoder().encodeToString(signer.sign());
        return unsigned.substring(0, unsigned.length() - 1)
                + ",\"signature\":{\"algorithm\":\"Ed25519\",\"canonicalization\":\"RFC-8785\","
                + "\"value\":\"" + value + "\"}}";
    }
}
