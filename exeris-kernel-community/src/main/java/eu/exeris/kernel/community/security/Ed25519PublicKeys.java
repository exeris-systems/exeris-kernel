/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * Builds an Ed25519 {@link PublicKey} from the raw 32-byte form a JWK {@code x} member carries.
 *
 * @since 0.13
 */
final class Ed25519PublicKeys {

    private static final int RAW_LENGTH = 32;

    // X.509 SubjectPublicKeyInfo header for an Ed25519 key (RFC 8410), followed by the raw 32 bytes.
    // The same encoding as Core's TrustedIssuerKeyStore#decodeRawPublicKey, repeated here because
    // that method is private and scoped to licence verification.
    private static final byte[] SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private Ed25519PublicKeys() {
    }

    /**
     * Decodes a raw Ed25519 public key.
     *
     * @param rawKey the 32-byte public key
     * @return the key
     * @throws IllegalArgumentException if {@code rawKey} is not exactly 32 bytes
     * @throws GeneralSecurityException if the JDK rejects the encoded key
     */
    /* default */ static PublicKey fromRaw(byte[] rawKey) throws GeneralSecurityException {
        if (rawKey == null || rawKey.length != RAW_LENGTH) {
            throw new IllegalArgumentException("an Ed25519 public key is exactly 32 bytes");
        }
        byte[] spki = new byte[SPKI_PREFIX.length + RAW_LENGTH];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(rawKey, 0, spki, SPKI_PREFIX.length, RAW_LENGTH);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }
}
