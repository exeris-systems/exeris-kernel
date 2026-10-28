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
 * <p>Pins trusted Ed25519 public keys for offline verification per ADR-088, each with the
 * {@link IssuerKeyScope} it is allowed to entitle. The key set is fixed at build time: it is an immutable
 * map with no mutator, so no code in the process can add, replace, widen or remove a trust root at runtime
 * (ADR-088 obligation 5, ADR-089 obligation 4). Tests that need their own issuer key pass it to
 * {@link LicenseManifestVerifier#verify(String, java.time.Instant, IssuerKeyResolver)}; the bootstrap path
 * never accepts a resolver and always resolves keys here.
 *
 * <p>There are three families of key, named {@code exeris-<family>-<year>-k<N>}: {@code prod} entitles
 * production licences ({@link IssuerKeyScope#PROD}), {@code dev} developer licences
 * ({@link IssuerKeyScope#DEV}) and {@code ent} enterprise licences ({@link IssuerKeyScope#ENT}). Each
 * entry is the raw 32-byte public key of an issuer epoch whose private key is held by the Exeris license
 * issuer.
 *
 * @since 0.13
 */
public final class TrustedIssuerKeyStore {

    /** Standard SubjectPublicKeyInfo (SPKI) DER prefix for Ed25519 public keys (12 bytes). */
    private static final byte[] ED25519_SPKI_PREFIX =
            HexFormat.of().parseHex("302a300506032b6570032100");

    private static final int ED25519_PUBLIC_KEY_LENGTH = 32;

    private static final Map<String, Entry> TRUSTED_KEYS = Map.of(
            "exeris-prod-2026-k1",
            new Entry(HexFormat.of().parseHex("bbba57668037f6dc4f1c305a7fac2d2ba0a506611dd6b70bdb9a3ce24a5365c9"),
                    IssuerKeyScope.PROD),
            "exeris-dev-2026-k1",
            new Entry(HexFormat.of().parseHex("2c15e5013d02a45c579e1021aec554292a47b29ac480ae2366df276b547da92e"),
                    IssuerKeyScope.DEV),
            "exeris-ent-2026-k1",
            new Entry(HexFormat.of().parseHex("5c07d772c227fafc491d7c5d5ef0fbcd18830872fc401f4a1f6dcbec23bb6027"),
                    IssuerKeyScope.ENT)
    );

    /** A pinned raw public key and its scope; the key is copied on every read. */
    private static final class Entry {
        private final byte[] raw;
        private final IssuerKeyScope scope;

        private Entry(byte[] raw, IssuerKeyScope scope) {
            this.raw = raw.clone();
            this.scope = scope;
        }

        private byte[] raw() {
            return raw.clone();
        }

        private IssuerKeyScope scope() {
            return scope;
        }
    }

    private TrustedIssuerKeyStore() {}

    /**
     * Retrieves the raw 32-byte Ed25519 public key for the given keyId.
     *
     * @param keyId key identifier from manifest issuer section
     * @return 32-byte public key array copy
     * @throws ContractBreachException with EX-LIC-0002 if keyId is unknown
     */
    public static byte[] getPublicKeyBytes(String keyId) {
        return entry(keyId).raw().clone();
    }

    private static Entry entry(String keyId) {
        if (keyId == null) {
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0002, "Issuer keyId must not be null");
        }
        Entry entry = TRUSTED_KEYS.get(keyId);
        if (entry == null) {
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0002, "Unknown issuer keyId", keyId);
        }
        return entry;
    }

    /**
     * Resolves the {@link PublicKey} instance for the given keyId.
     *
     * @param keyId key identifier from manifest issuer section
     * @return JDK PublicKey initialized for Ed25519
     * @throws ContractBreachException if keyId is unknown (EX-LIC-0002) or malformed (EX-LIC-0001)
     */
    public static PublicKey getPublicKey(String keyId) {
        return decodeRawPublicKey(keyId, entry(keyId).raw());
    }

    /**
     * Resolves the embedded issuer key for the given keyId together with its scope.
     *
     * @param keyId key identifier from a manifest's {@code issuer.keyId}
     * @return the decoded public key with the scope it is allowed to entitle
     * @throws ContractBreachException with EX-LIC-0002 if keyId is unknown
     * @since 0.13
     */
    public static TrustedIssuerKey resolve(String keyId) {
        Entry entry = entry(keyId);
        return new TrustedIssuerKey(keyId, decodeRawPublicKey(keyId, entry.raw()), entry.scope());
    }

    /**
     * Returns the identifiers of the embedded trust roots.
     *
     * @return immutable set of embedded keyIds
     * @since 0.13
     */
    public static Set<String> trustedKeyIds() {
        return TRUSTED_KEYS.keySet();
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
