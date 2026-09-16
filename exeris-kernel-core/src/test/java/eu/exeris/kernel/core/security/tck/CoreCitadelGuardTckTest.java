/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security.tck;

import eu.exeris.kernel.core.security.CitadelGuard;
import eu.exeris.kernel.tck.contract.security.AbstractCitadelGuardTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Core: CitadelGuard TCK")
class CoreCitadelGuardTckTest extends AbstractCitadelGuardTck<CitadelGuard> {

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
