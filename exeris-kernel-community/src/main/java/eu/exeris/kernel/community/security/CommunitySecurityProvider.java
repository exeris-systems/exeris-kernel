/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.Objects;

/**
 * Community {@link SecurityProvider} baseline implementation.
 *
 * @since 0.5.0
 */
public final class CommunitySecurityProvider implements SecurityProvider {

    private static final String PROVIDER_ID = "jwt-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/JWT";
    private static final String EXPECTED_ISSUER = "https://auth.example.com";
    private static final String EXPECTED_AUDIENCE = "exeris-kernel";

    private final CommunityJwksValidator jwksValidator;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public CommunitySecurityProvider() {
        this(Map.of(), EXPECTED_ISSUER, EXPECTED_AUDIENCE);
    }

    public CommunitySecurityProvider(Map<String, RSAPublicKey> keysByKid,
                                     String expectedIssuer,
                                     String expectedAudience) {
        this(new CommunityJwksValidator(keysByKid, expectedIssuer, expectedAudience));
    }

    /* default */ CommunitySecurityProvider(CommunityJwksValidator jwksValidator) {
        this.jwksValidator = Objects.requireNonNull(jwksValidator, "jwksValidator must not be null");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public AuthenticationResult authenticate(LoanedBuffer rawToken) {
        if (rawToken == null) {
            throw new SecurityAuthenticationException("JWT", "missing-token");
        }

        long tokenSize = rawToken.size();
        if (tokenSize < 0) {
            throw new SecurityAuthenticationException("JWT", "indeterminate");
        }
        if (tokenSize == 0) {
            throw new SecurityAuthenticationException("JWT", "empty-token");
        }

        byte[] tokenBytes = rawToken.segment()
            .asSlice(0L, tokenSize)
            .toArray(ValueLayout.JAVA_BYTE);
        String compactJwt = new String(tokenBytes, StandardCharsets.UTF_8);

        return jwksValidator.authenticate(compactJwt);
    }

    @Override
    public StorageContext systemStorageContext() {
        return ImmutableStorageContext.GLOBAL;
    }
}
