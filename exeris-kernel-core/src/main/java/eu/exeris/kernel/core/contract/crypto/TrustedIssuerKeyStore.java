/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Root of trust for verifying Exeris cryptographic license manifests.
 *
 * <p>Pins trusted Ed25519 public keys for offline verification per ADR-088. The key set is fixed
 * at build time: it is an immutable map with no mutator, so no code in the process can add,
 * replace or remove a trust root at runtime (ADR-088 obligation 5, ADR-089 obligation 4). Tests
 * that need their own issuer key pass it to {@link LicenseManifestVerifier#verify(String,
 * java.time.Instant, IssuerKeyResolver)}; the bootstrap path never accepts a resolver and always
 * resolves keys here.
 *
 * <p>Each entry is the raw 32-byte public key of an issuer epoch whose private key is held by the
 * Exeris license issuer. Historical epoch keys stay in this map for as long as a version line must
 * boot {@code PERPETUAL_INTERNAL} manifests signed with them.
 *
 * @since 0.13
 */
public final class TrustedIssuerKeyStore {

    /** Standard SubjectPublicKeyInfo (SPKI) DER prefix for Ed25519 public keys (12 bytes). */
    private static final byte[] ED25519_SPKI_PREFIX =
            HexFormat.of().parseHex("302a300506032b6570032100");

    private static final int ED25519_PUBLIC_KEY_LENGTH = 32;

    private static final Map<String, byte[]> TRUSTED_PUBLIC_KEYS = Map.of(
            "exeris-root-2026-k1",
            HexFormat.of().parseHex("ab971ce0e0bbf674139d8beac52d38dc3dad28a351e00975032a8be613e5e70f")
    );

    private TrustedIssuerKeyStore() {}

    /**
     * Retrieves the raw 32-byte Ed25519 public key for the given keyId.
     *
     * @param keyId key identifier from manifest issuer section
     * @return 32-byte public key array copy
     * @throws ContractBreachException with EX-LIC-0002 if keyId is unknown
     */
    public static byte[] getPublicKeyBytes(String keyId) {
        if (keyId == null) {
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0002, "Issuer keyId must not be null");
        }
        byte[] key = TRUSTED_PUBLIC_KEYS.get(keyId);
        if (key == null) {
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0002, "Unknown issuer keyId", keyId);
        }
        return key.clone();
    }

    /**
     * Resolves the {@link PublicKey} instance for the given keyId.
     *
     * @param keyId key identifier from manifest issuer section
     * @return JDK PublicKey initialized for Ed25519
     * @throws ContractBreachException if keyId is unknown (EX-LIC-0002) or malformed (EX-LIC-0001)
     */
    public static PublicKey getPublicKey(String keyId) {
        return decodeRawPublicKey(keyId, getPublicKeyBytes(keyId));
    }

    /**
     * Returns the identifiers of the embedded trust roots.
     *
     * @return immutable set of embedded keyIds
     */
    /* default */ static Set<String> trustedKeyIds() {
        return TRUSTED_PUBLIC_KEYS.keySet();
    }

    private static PublicKey decodeRawPublicKey(String keyId, byte[] raw) {
        if (raw.length != ED25519_PUBLIC_KEY_LENGTH) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "Embedded issuer public key must be exactly 32 bytes: " + keyId
            );
        }
        byte[] spki = new byte[ED25519_SPKI_PREFIX.length + ED25519_PUBLIC_KEY_LENGTH];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, ED25519_PUBLIC_KEY_LENGTH);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            return keyFactory.generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "Failed to decode embedded issuer public key: " + keyId,
                    e
            );
        }
    }
}
