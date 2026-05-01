/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.core.security.CitadelGuard;
import eu.exeris.kernel.tck.contract.security.AbstractCitadelGuardTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: CitadelGuard TCK")
class CommunityCitadelGuardTckTest extends AbstractCitadelGuardTck<CitadelGuard> {

    @Override
    protected CitadelGuard createGuard() {
        return new CitadelGuard();
    }

    @Override
    protected void preAllocate(CitadelGuard guard, String requiredRole) {
        guard.preAllocate(requiredRole);
    }

    @Override
    protected void seal(CitadelGuard guard) {
        guard.seal();
    }

    @Override
    protected boolean isSealed(CitadelGuard guard) {
        return guard.isSealed();
    }

    @Override
    protected void requireRole(CitadelGuard guard, String requiredRole) {
        guard.requireRole(requiredRole);
    }

    @Override
    protected boolean hasRole(CitadelGuard guard, String role) {
        return guard.hasRole(role);
    }

    @Override
    protected int preAllocatedCount(CitadelGuard guard) {
        return guard.preAllocatedCount();
    }
}
