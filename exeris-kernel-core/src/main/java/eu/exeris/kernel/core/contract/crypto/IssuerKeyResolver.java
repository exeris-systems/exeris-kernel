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
 * Resolves the issuer key a manifest's {@code issuer.keyId} names, with the {@link IssuerKeyScope} it is
 * allowed to entitle.
 *
 * <p>The kernel bootstrap resolves keys only through {@link #embedded()}, the build-time
 * {@link TrustedIssuerKeyStore}. Any other resolver yields a contract the caller holds privately:
 * nothing it produces reaches {@code ContractKernelProviders.EXECUTION_CONTRACT}, so a resolver is a way to
 * verify a manifest against a key of the caller's choosing, never a way to extend the kernel's trust.
 *
 * @since 0.13
 */
@FunctionalInterface
public interface IssuerKeyResolver {

    /**
     * Resolves the issuer key for {@code keyId}.
     *
     * @param keyId key identifier from the manifest issuer section
     * @return the Ed25519 public key with its scope
     * @throws ContractBreachException with EX-LIC-0002 if keyId is unknown
     */
    TrustedIssuerKey resolve(String keyId);

    /**
     * The resolver backed by the embedded {@link TrustedIssuerKeyStore}: the keys, with their scopes, that the
     * kernel itself trusts. A tool that signs licences verifies what it has signed through it to check that the
     * manifest passes under the pinned keys, and not only under a key of its own.
     *
     * @return the kernel's own trust-root resolver
     * @since 0.13
     */
    static IssuerKeyResolver embedded() {
        return TrustedIssuerKeyStore::resolve;
    }

    /**
     * A resolver over a fixed set of keys, each with the unrestricted {@link IssuerKeyScope#ENT} scope; any
     * other keyId fails with EX-LIC-0002. A caller that needs a narrower scope uses
     * {@link #ofScoped(Map)}.
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
                throw unknown(keyId);
            }
            return new TrustedIssuerKey(keyId, key, IssuerKeyScope.ENT);
        };
    }

    /**
     * A resolver over a fixed set of scoped keys; any other keyId fails with EX-LIC-0002. It lets a caller
     * verify a manifest against a key of its own under the scope that key would hold in the trust store, for
     * example to check what an issuer is about to sign.
     *
     * @param keys keyId to scoped key; each key's own {@link TrustedIssuerKey#keyId()} must equal its map key
     * @return resolver over an immutable copy of {@code keys}
     * @throws IllegalArgumentException if a key is stored under a keyId other than its own
     * @since 0.13
     */
    static IssuerKeyResolver ofScoped(Map<String, TrustedIssuerKey> keys) {
        Map<String, TrustedIssuerKey> copy = Map.copyOf(Objects.requireNonNull(keys, "keys must not be null"));
        copy.forEach((keyId, key) -> {
            if (!keyId.equals(key.keyId())) {
                throw new IllegalArgumentException("key " + key.keyId() + " is registered under " + keyId);
            }
        });
        return keyId -> {
            TrustedIssuerKey key = keyId == null ? null : copy.get(keyId);
            if (key == null) {
                throw unknown(keyId);
            }
            return key;
        };
    }

    private static ContractBreachException unknown(String keyId) {
        return new ContractBreachException(KernelErrorCodes.EX_LIC_0002, "Unknown issuer keyId", keyId);
    }
}
