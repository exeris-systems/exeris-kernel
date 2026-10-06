/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the Ed25519 public key a manifest's {@code issuer.keyId} names.
 *
 * <p>The kernel bootstrap resolves keys only through {@link #embedded()}, the build-time
 * {@link TrustedIssuerKeyStore}. Any other resolver yields a contract the caller holds privately:
 * nothing it produces reaches {@code KernelProviders.EXECUTION_CONTRACT}, so a resolver is a way to
 * verify a manifest against a key of the caller's choosing, never a way to extend the kernel's trust.
 *
 * @since 0.13
 */
@FunctionalInterface
public interface IssuerKeyResolver {

    /**
     * Resolves the public key for {@code keyId}.
     *
     * @param keyId key identifier from the manifest issuer section
     * @return Ed25519 public key
     * @throws ContractBreachException with EX-LIC-0002 if keyId is unknown
     */
    PublicKey resolve(String keyId);

    /**
     * The resolver backed by the embedded {@link TrustedIssuerKeyStore}.
     *
     * @return the kernel's own trust-root resolver
     */
    static IssuerKeyResolver embedded() {
        return TrustedIssuerKeyStore::getPublicKey;
    }

    /**
     * A resolver over a fixed set of keys; any other keyId fails with EX-LIC-0002.
     *
     * @param keys keyId to public key
     * @return resolver over an immutable copy of {@code keys}
     */
    @SuppressWarnings("PMD.ShortMethodName")
    static IssuerKeyResolver of(Map<String, PublicKey> keys) {
        Map<String, PublicKey> copy = Map.copyOf(Objects.requireNonNull(keys, "keys must not be null"));
        return keyId -> {
            PublicKey key = keyId == null ? null : copy.get(keyId);
            if (key == null) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0002, "Unknown issuer keyId", keyId);
            }
            return key;
        };
    }
}
