/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import eu.exeris.kernel.spi.security.RequiresRole;

/**
 * Service carrying compile-time role annotations to exercise RoleCheckRegistry generation.
 */
public final class SmokeRoleProtectedService {

    /**
     * Action requiring administrative role.
     */
    @RequiresRole("ROLE_ADMIN")
    public void performAdminAction() {
        // Entry point for compile-time RBAC index verification
    }

    /**
     * Action requiring standard operator role.
     */
    @RequiresRole("ROLE_OPERATOR")
    public void performOperatorAction() {
        // Entry point for compile-time RBAC index verification
    }
}
