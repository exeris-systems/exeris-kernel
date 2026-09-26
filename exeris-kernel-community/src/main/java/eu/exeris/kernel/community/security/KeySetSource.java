/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/**
 * Injectable seam that supplies a point-in-time snapshot of the verification key set.
 *
 * <p>Implementations return an immutable {@code kid -> key} snapshot. There is no HTTP
 * or wire vocabulary here — a real OIDC/JWKS fetch lands in v0.10
 * ({@code CommunityOidcIdentityProvider}, deferred by ADR-040). A refresh that cannot
 * produce a trustworthy snapshot MUST signal failure via {@link KeySetRefreshException}
 * rather than returning an empty or partial map (fail-closed, ADR-012).
 *
 * @since 0.9
 */
@FunctionalInterface
/* default */ interface KeySetSource {

    /**
     * Loads the current key-set snapshot.
     *
     * @return an immutable snapshot of {@code kid -> RSA public key}
     * @throws KeySetRefreshException when a trustworthy snapshot cannot be produced
     */
    Map<String, RSAPublicKey> load() throws KeySetRefreshException;
}
