/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.Objects;

/**
 * {@link JwksKeyResolver} backed by an immutable, fixed {@code kid -> key} map.
 *
 * <p>Preserves the exact pre-rotation behavior: a {@code kid} present in the map
 * resolves to its key; any other {@code kid} resolves to {@code null} (the validator
 * then throws today's {@code unknown-kid} deny). This resolver never rotates and
 * never throws a terminal deny of its own.
 *
 * @since 0.9
 */
/* default */ final class StaticJwksKeyResolver implements JwksKeyResolver {

    private final Map<String, JwsVerificationKey> keysByKid;

    /* default */ StaticJwksKeyResolver(Map<String, JwsVerificationKey> keysByKid) {
        this.keysByKid = Map.copyOf(Objects.requireNonNull(keysByKid, "keysByKid must not be null"));
    }

    /**
     * A resolver over RSA keys, each pinned to RS256.
     *
     * @param keysByKid {@code kid} to RSA public key
     * @return a resolver that verifies those keys under RS256 only
     */
    /* default */ static StaticJwksKeyResolver ofRsa(Map<String, RSAPublicKey> keysByKid) {
        return new StaticJwksKeyResolver(JwsVerificationKey.rs256All(keysByKid));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Looks up {@code kid} in the fixed map supplied at construction; an unmapped
     *           {@code kid} returns {@code null} rather than throwing.
     */
    @Override
    public JwsVerificationKey resolve(String kid) {
        return keysByKid.get(kid);
    }
}
