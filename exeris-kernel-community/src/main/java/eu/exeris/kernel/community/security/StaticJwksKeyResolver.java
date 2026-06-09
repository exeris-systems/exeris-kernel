/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.9.0
 */
/* default */ final class StaticJwksKeyResolver implements JwksKeyResolver {

    private final Map<String, RSAPublicKey> keysByKid;

    /* default */ StaticJwksKeyResolver(Map<String, RSAPublicKey> keysByKid) {
        this.keysByKid = Map.copyOf(Objects.requireNonNull(keysByKid, "keysByKid must not be null"));
    }

    @Override
    public RSAPublicKey resolve(String kid) {
        return keysByKid.get(kid);
    }
}
