/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.core.security.RoleCheckEnforcer;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
import eu.exeris.kernel.tck.contract.security.AbstractRequiresRoleTck;
import org.junit.jupiter.api.DisplayName;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

@DisplayName("Community: @RequiresRole runtime TCK")
class CommunityRequiresRoleTckTest extends AbstractRequiresRoleTck {

    @Override
    protected BiPredicate<Integer, PrincipalContext> isAllowed(RoleRegistry registry) {
        return (methodId, principal) -> RoleCheckEnforcer.isAllowed(methodId, principal, registry);
    }

    @Override
    protected BiConsumer<Integer, PrincipalContext> check(RoleRegistry registry) {
        return (methodId, principal) -> RoleCheckEnforcer.check(methodId, principal, registry);
    }
}
