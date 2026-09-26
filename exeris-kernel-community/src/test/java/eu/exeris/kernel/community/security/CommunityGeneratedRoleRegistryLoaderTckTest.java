/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader;
import eu.exeris.kernel.core.security.RoleCheckEnforcer;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
import eu.exeris.kernel.tck.contract.security.AbstractGeneratedRoleRegistryLoaderTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community binding for {@link AbstractGeneratedRoleRegistryLoaderTck}.
 *
 * <p>{@link #loadPresent()} drives the real reflective {@code load()}: a hand-written
 * fixture at {@code eu.exeris.kernel.security.generated.RoleCheckRegistry} (mirroring
 * the processor output) sits on this module's test classpath, so {@code Class.forName}
 * resolves it and the loader binds its static accessors via {@code MethodHandle}.
 * {@link #loadAbsent()} returns the loader's documented absent-class result — the
 * fail-closed empty singleton.
 */
@DisplayName("Community: GeneratedRoleRegistryLoader TCK")
class CommunityGeneratedRoleRegistryLoaderTckTest extends AbstractGeneratedRoleRegistryLoaderTck {

    @Override
    protected RoleRegistry loadAbsent() {
        return GeneratedRoleRegistryLoader.empty();
    }

    @Override
    protected RoleRegistry loadPresent() {
        return GeneratedRoleRegistryLoader.load();
    }

    @Override
    protected boolean isAllowed(int methodId, PrincipalContext principal, RoleRegistry registry) {
        return RoleCheckEnforcer.isAllowed(methodId, principal, registry);
    }
}
