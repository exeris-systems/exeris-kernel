/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.core.security.RoleCheckEnforcer;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;
import java.util.UUID;

/**
 * Community zero-allocation guard for the {@code @RequiresRole} accept path
 * (ADR-014 §5; reviewer finding on PR #102).
 *
 * <h2>What this proves</h2>
 * <p>{@link RoleCheckEnforcer#isAllowed(int, PrincipalContext, RoleRegistry)}
 * is a pure primitive {@code AND}/{@code EQ} dance over a {@code long} mask
 * and the registry's primitive lookups. The accept path MUST therefore emit
 * zero {@code eu.exeris.*} heap allocations across {@code hotPathIterations()}
 * invocations under JFR observation
 * ({@code jdk.ObjectAllocationInNewTLAB} +
 * {@code jdk.ObjectAllocationOutsideTLAB} +
 * {@code jdk.ObjectAllocationSample}).
 *
 * <h2>Hot-path call shape</h2>
 * <p>The iteration calls the enforcer with a {@code int} literal for the
 * method id — {@link #METHOD_ID} is a {@code static final int} constant —
 * which avoids the {@link Integer} autoboxing that {@code BiPredicate.test}
 * would force. The principal's {@code roleMask()} returns a primitive long
 * directly from a record field; the stub registry returns primitive longs
 * and a primitive boolean from constant-time switches.
 *
 * <h2>Tier expectation</h2>
 * <p>{@link #supportsZeroGcHotPath()} returns {@code true}: this is a Core
 * helper, not an SPI gateway, and the contract is "no allocation, full
 * stop." Failure of this test means a regression introduced autoboxing,
 * captured-lambda creation, or an allocating registry path.
 *
 * @since 0.7.0
 */
@DisplayName("Community: @RequiresRole zero-alloc TCK")
class CommunityRequiresRoleZeroAllocTckTest extends AbstractSubsystemZeroAllocTck {

    private static final int METHOD_ID = 0;
    private static final int BIT_ADMIN = 1;

    private RoleRegistry registry;
    private PrincipalContext principal;

    @Override
    protected String subsystemName() {
        return "Security";
    }

    @Override
    protected String hotPathDescription() {
        return "RoleCheckEnforcer.isAllowed(methodId, principal, registry) — accept path";
    }

    @Override
    protected boolean supportsZeroGcHotPath() {
        return true;
    }

    @Override
    protected void bootstrapSubsystem() {
        registry = new ZeroAllocStubRegistry();
        principal = new MaskedPrincipal(1L << BIT_ADMIN);
    }

    @Override
    protected void runSingleIteration() {
        boolean allowed = RoleCheckEnforcer.isAllowed(METHOD_ID, principal, registry);
        if (!allowed) {
            throw new AssertionError("accept path must allow — stub mis-wired");
        }
    }

    @Override
    protected void tearDownSubsystem() {
        // No resources to release — registry and principal are GC-managed records.
    }

    /**
     * Stub registry whose lookups are constant-time and allocation-free:
     * primitive {@code int} in, primitive {@code long}/{@code boolean} out.
     */
    private static final class ZeroAllocStubRegistry implements RoleRegistry {

        private static final long REQUIRED = 1L << BIT_ADMIN;

        @Override
        public long requiredAny(int methodId) {
            return REQUIRED;
        }

        @Override
        public long requiredAll(int methodId) {
            return 0L;
        }

        @Override
        public boolean matchIsAll(int methodId) {
            return false;
        }

        @Override
        public int methodCount() {
            return 1;
        }

        @Override
        public int roleNameToBit(String roleName) {
            return "ROLE_ADMIN".equals(roleName) ? BIT_ADMIN : -1;
        }
    }

    /**
     * Minimal {@link PrincipalContext} carrying a precomputed role mask —
     * the same shape an authentication layer wires after resolving claim
     * role names through the registry. Backed by a record so the {@code long}
     * field is read directly via field access, no boxing, no virtual call
     * beyond the interface dispatch.
     */
    private record MaskedPrincipal(long maskValue) implements PrincipalContext {
        @Override
        public UUID principalId() {
            return new UUID(0L, 0L);
        }

        @Override
        public java.util.Optional<UUID> tenantId() {
            return java.util.Optional.empty();
        }

        @Override
        public Set<String> roles() {
            return Set.of();
        }

        @Override
        public Set<String> scopes() {
            return Set.of();
        }

        @Override
        public long roleMask() {
            return maskValue;
        }
    }
}
