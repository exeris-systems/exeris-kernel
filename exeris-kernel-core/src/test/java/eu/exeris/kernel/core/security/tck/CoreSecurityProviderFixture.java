/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security.tck;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;

import java.util.Set;
import java.util.UUID;

public final class CoreSecurityProviderFixture implements SecurityProvider {

    private static final UUID PRINCIPAL_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    static final String SEPARATED_SCHEMA_NAME = "core-schema";
    static final String DEDICATED_DS_KEY      = "core-ds";
    private static final String TENANT_KEY    = "00000000-0000-7000-8000-000000000002";

    public CoreSecurityProviderFixture() {
        /* ServiceLoader-required public no-arg constructor */
    }

    @Override
    public String providerId() {
        return "core-security-provider-fixture";
    }

    @Override
    public String providerName() {
        return "CoreSecurityProviderFixture";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public AuthenticationResult authenticate(LoanedBuffer token) {
        if (token.size() < 0) {
            throw new SecurityAuthenticationException("JWT", "indeterminate");
        }
        if (token.size() == 0) {
            throw new SecurityAuthenticationException("TCK", "empty-token");
        }
        ImmutableStorageContext storage = switch ((int) token.size()) {
            case 2  -> ImmutableStorageContext.separatedSchema(TENANT_KEY, SEPARATED_SCHEMA_NAME);
            case 3  -> ImmutableStorageContext.dedicated(TENANT_KEY, DEDICATED_DS_KEY);
            // ADR-012 §4a (amended) / S-P0-07: a declared-but-unhonourable isolation strategy is a
            // terminal deny, NOT a downgrade to SHARED. Sizes 10/11/12/13 model the four deny shapes
            // the inverted IsolationStrategyContract drives (missing schema / missing datasource /
            // unrecognized strategy / wrong-typed strategy). Secret-safe reason codes only.
            // Size 13 models what a conforming provider does during *its own* token validation:
            // per §9 the wrong-typed deny is not inherited from IdentityStorageMapping, because
            // VerifiedClaims.claim() reports such a claim as absent.
            case 10 -> throw new SecurityAuthenticationException("JWT", "isolation-incomplete");
            case 11 -> throw new SecurityAuthenticationException("JWT", "isolation-incomplete");
            case 12 -> throw new SecurityAuthenticationException("JWT", "isolation-unknown-strategy");
            case 13 -> throw new SecurityAuthenticationException("JWT", "isolation-malformed");
            // Size 14: a declared shared scope, on a provider not constructed as enforcing — narrowing
            // it to tenant-private or honouring it unenforced are both forbidden (ADR-012 §4b.5/§4b.7).
            case 14 -> throw new SecurityAuthenticationException("JWT", "shared-scope-unsupported");
            // Size 15: a wrong-typed shared scope. Like size 13 this deny is the provider's own — the
            // mapping sees the claim as absent — but it guards the opposite failure: not a weakened
            // tier, a silently withheld one (ADR-012 §4b.7).
            case 15 -> throw new SecurityAuthenticationException("JWT", "shared-scope-malformed");
            default -> ImmutableStorageContext.GLOBAL;
        };
        return new AuthenticationResult(
                ImmutablePrincipal.ofScopes(PRINCIPAL_ID, Set.of("security:read")),
                storage);
    }

    @Override
    public StorageContext systemStorageContext() {
        return ImmutableStorageContext.GLOBAL;
    }
}