/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import eu.exeris.kernel.tck.contract.security.AbstractIdentityProviderTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community OIDC binding of {@link AbstractIdentityProviderTck}.
 */
@DisplayName("Community: CommunityOidcIdentityProvider TCK")
class CommunityOidcIdentityProviderTckTest extends AbstractIdentityProviderTck {

    private static final String FOREIGN_ISSUER = "https://foreign.example.com";

    @Override
    protected IdentityProvider createProvider() {
        return new CommunityOidcIdentityProvider(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
    }

    @Override
    protected LoanedBuffer validTokenBuffer() {
        return TestJwt.builder().toBuffer();
    }

    @Override
    protected LoanedBuffer foreignIssuerTokenBuffer() {
        return TestJwt.builder().issuer(FOREIGN_ISSUER).toBuffer();
    }

    @Override
    protected LoanedBuffer unverifiableTokenBuffer() {
        return TestJwt.builder().kid(TestJwt.UNKNOWN_KID).toBuffer();
    }

    @Override
    protected LoanedBuffer expiredTokenBuffer() {
        return TestJwt.builder().expired().toBuffer();
    }

    @Override
    protected LoanedBuffer sharedScopeTokenBuffer() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.SHARED_SCOPE_KEY, "world-alpha")
                .toBuffer();
    }

    @Override
    protected IdentityProvider createSharedScopeEnforcingProvider() {
        return new CommunityOidcIdentityProvider(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE)
                .enforcingSharedScope();
    }
}
