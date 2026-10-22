/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import java.security.PublicKey;
import java.util.Objects;

/**
 * An Ed25519 issuer public key together with the {@link IssuerKeyScope} it is allowed to entitle.
 *
 * @param keyId     identifier a manifest's {@code issuer.keyId} names; not blank
 * @param publicKey the Ed25519 public key that verifies the manifest signature
 * @param scope     what a manifest signed by this key may entitle
 * @since 0.13
 */
public record TrustedIssuerKey(String keyId, PublicKey publicKey, IssuerKeyScope scope) {

    /**
     * Compact constructor rejecting a null or blank component.
     */
    public TrustedIssuerKey {
        Objects.requireNonNull(keyId, "keyId must not be null");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
    }
}
