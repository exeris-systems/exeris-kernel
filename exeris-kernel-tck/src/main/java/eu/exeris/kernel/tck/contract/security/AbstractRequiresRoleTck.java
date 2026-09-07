/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.InsufficientPrivilegesException;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.RoleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: runtime contract for {@code @RequiresRole} enforcement (ADR-014 §5–§6).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code RoleMatch.ANY} accepts when the principal mask intersects the
 *       required mask, otherwise raises {@code EX-SEC-2003}.</li>
 *   <li>{@code RoleMatch.ALL} accepts only when the principal mask covers
 *       every bit in the required mask.</li>
 *   <li>A principal with the default {@code roleMask() = 0L} is denied for
 *       any non-empty required mask (fail-closed default).</li>
 *   <li>Denial raises {@link InsufficientPrivilegesException} with
 *       {@code errorCode = EX-SEC-2003}.</li>
 *   <li>Accept path performs no allocation beyond what the registry
 *       implementation does for its lookup methods (verified separately by
 *       a per-binding zero-alloc test).</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class CommunityRequiresRoleTckTest extends AbstractRequiresRoleTck {
 *     @Override
 *     protected BiPredicate<Integer, PrincipalContext> isAllowed(RoleRegistry registry) {
 *         return (methodId, principal) -> RoleCheckEnforcer.isAllowed(methodId, principal, registry);
 *     }
 *
 *     @Override
 *     protected BiConsumer<Integer, PrincipalContext> check(RoleRegistry registry) {
 *         return (methodId, principal) -> RoleCheckEnforcer.check(methodId, principal, registry);
 *     }
 * }
 * }
 *
 * <p>The abstract drives the enforcer with a stub {@link RoleRegistry}; bindings
 * supply the enforcer factory.
 *
 * @since 0.7
 */
public abstract class AbstractRequiresRoleTck {

    /** Method id used for ANY-match scenarios. */
    protected static final int METHOD_ANY = 0;

    /** Method id used for ALL-match scenarios. */
    protected static final int METHOD_ALL = 1;

    /** Bit position assigned to {@code ROLE_ADMIN} in the stub registry. */
    protected static final int BIT_ADMIN = 1;

    /** Bit position assigned to {@code ROLE_AUDITOR} in the stub registry. */
    protected static final int BIT_AUDITOR = 8;

    /**
     * Creates the contract; subclasses supply the allow-check binding via
     * {@link #isAllowed(RoleRegistry)} and the enforcement binding via {@link #check(RoleRegistry)}.
     */
    public AbstractRequiresRoleTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Returns a function that delegates to the binding's allocation-free
     * decision routine.
     *
     * @param registry registry supplied by the abstract; binding must read it
     * @return non-null predicate {@code (methodId, principal) → allowed}
     */
    protected abstract java.util.function.BiPredicate<Integer, PrincipalContext> isAllowed(RoleRegistry registry);

    /**
     * Returns a consumer that delegates to the binding's enforcement routine: it returns
     * silently when the principal is permitted for the given method id, and throws
     * {@link InsufficientPrivilegesException} when it is not.
     *
     * @param registry registry supplied by the abstract; binding must read it
     * @return non-null consumer {@code (methodId, principal) → void}
     */
    protected abstract java.util.function.BiConsumer<Integer, PrincipalContext> check(RoleRegistry registry);

    @Test
    @DisplayName("ANY-match accepts a principal whose mask intersects the required set")
    void anyMatchAccepts() {
        RoleRegistry registry = new StubRegistry();
        PrincipalContext principal = principalWithMask(1L << BIT_ADMIN);

        assertThat(isAllowed(registry).test(METHOD_ANY, principal)).isTrue();
    }

    @Test
    @DisplayName("ANY-match denies a principal whose mask is empty")
    void anyMatchDeniesEmptyMask() {
        RoleRegistry registry = new StubRegistry();
        PrincipalContext principal = principalWithMask(0L);

        assertThat(isAllowed(registry).test(METHOD_ANY, principal)).isFalse();
    }

    @Test
    @DisplayName("ANY-match denies a principal whose mask hits other bits only")
    void anyMatchDeniesUnrelatedMask() {
        RoleRegistry registry = new StubRegistry();
        // bit 9 is not part of the ANY required mask {ADMIN(1), AUDITOR(8)}.
        PrincipalContext principal = principalWithMask(1L << 9);

        assertThat(isAllowed(registry).test(METHOD_ANY, principal)).isFalse();
    }

    @Test
    @DisplayName("ALL-match accepts only when every required bit is set")
    void allMatchRequiresEveryBit() {
        RoleRegistry registry = new StubRegistry();
        PrincipalContext bothRoles = principalWithMask((1L << BIT_ADMIN) | (1L << BIT_AUDITOR));
        PrincipalContext adminOnly = principalWithMask(1L << BIT_ADMIN);

        assertThat(isAllowed(registry).test(METHOD_ALL, bothRoles)).isTrue();
        assertThat(isAllowed(registry).test(METHOD_ALL, adminOnly)).isFalse();
    }

    @Test
    @DisplayName("Default principal (roleMask() returns 0L) fails closed")
    void defaultPrincipalFailsClosed() {
        RoleRegistry registry = new StubRegistry();
        PrincipalContext defaultPrincipal = ImmutablePrincipal.system(
                UUID.randomUUID(),
                Set.of("ROLE_ADMIN")); // role names present but no roleMask override
        java.util.function.BiConsumer<Integer, PrincipalContext> checker = check(registry);

        assertThat(defaultPrincipal.roleMask())
                .as("ImmutablePrincipal must keep the SPI default of 0L until callers explicitly populate it")
                .isZero();
        assertThat(isAllowed(registry).test(METHOD_ANY, defaultPrincipal)).isFalse();
        assertThatThrownBy(() -> checker.accept(METHOD_ANY, defaultPrincipal))
                .isInstanceOf(InsufficientPrivilegesException.class)
                .extracting(thrown -> ((InsufficientPrivilegesException) thrown).errorCode())
                .isEqualTo(KernelErrorCodes.EX_SEC_2003);
    }

    @Test
    @DisplayName("check() raises EX-SEC-2003 on deny and returns silently on accept")
    void checkRaisesOnDenyAndReturnsSilentlyOnAccept() {
        RoleRegistry registry = new StubRegistry();
        PrincipalContext admin = principalWithMask(1L << BIT_ADMIN);
        java.util.function.BiConsumer<Integer, PrincipalContext> checker = check(registry);

        checker.accept(METHOD_ANY, admin);   // accept — must not throw

        assertThatThrownBy(() -> checker.accept(METHOD_ALL, admin))
                .isInstanceOf(InsufficientPrivilegesException.class)
                .satisfies(thrown -> assertThat(((InsufficientPrivilegesException) thrown).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_SEC_2003))
                .satisfies(thrown -> {
                    Object[] rawArgs = ((InsufficientPrivilegesException) thrown).rawArgs();
                    assertThat(rawArgs)
                            .as("rawArgs[0] must carry the method-id label for telemetry correlation")
                            .hasSize(1);
                    assertThat(rawArgs[0])
                            .asString()
                            .contains("methodId=" + METHOD_ALL);
                });
    }

    /**
     * Asserts the stub registry's own {@code roleNameToBit}/{@code roleNameToMask} results;
     * it does not invoke or verify the {@code SecurityInterceptor} mask-population path that
     * the display name alludes to — that contract belongs to
     * {@link AbstractRoleMaskPopulationTck}.
     */
    @Test
    @DisplayName("roleNameToBit is consulted when populating the principal mask at auth time")
    void roleNameToBitResolves() {
        RoleRegistry registry = new StubRegistry();

        assertThat(registry.roleNameToBit("ROLE_ADMIN")).isEqualTo(BIT_ADMIN);
        assertThat(registry.roleNameToBit("ROLE_AUDITOR")).isEqualTo(BIT_AUDITOR);
        assertThat(registry.roleNameToBit("ROLE_GHOST"))
                .as("unknown roles must surface as -1 so callers can fail closed")
                .isEqualTo(-1);
        assertThat(registry.roleNameToMask("ROLE_ADMIN")).isEqualTo(1L << BIT_ADMIN);
        assertThat(registry.roleNameToMask("ROLE_GHOST")).isZero();
    }

    private static PrincipalContext principalWithMask(long mask) {
        return new MaskedPrincipal(mask);
    }

    /**
     * Stub registry mirroring the shape that {@code RoleCheckRegistry} would
     * expose for two methods: {@code METHOD_ANY} requires {@code ANY} of
     * {@code ROLE_ADMIN(1)} or {@code ROLE_AUDITOR(8)}; {@code METHOD_ALL}
     * requires {@code ALL} of the same two.
     */
    protected static final class StubRegistry implements RoleRegistry {

        private static final long REQUIRED = (1L << BIT_ADMIN) | (1L << BIT_AUDITOR);

        /**
         * Creates a registry fixed to {@code METHOD_ANY} and {@code METHOD_ALL}, the only two
         * method ids this suite drives.
         */
        protected StubRegistry() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Override
        public long requiredAny(int methodId) {
            return methodId == METHOD_ANY ? REQUIRED : 0L;
        }

        @Override
        public long requiredAll(int methodId) {
            return methodId == METHOD_ALL ? REQUIRED : 0L;
        }

        @Override
        public boolean matchIsAll(int methodId) {
            return methodId == METHOD_ALL;
        }

        @Override
        public int methodCount() {
            return 2;
        }

        @Override
        public int roleNameToBit(String roleName) {
            return switch (roleName) {
                case "ROLE_ADMIN" -> BIT_ADMIN;
                case "ROLE_AUDITOR" -> BIT_AUDITOR;
                case null, default -> -1;
            };
        }
    }

    /**
     * Minimal {@link PrincipalContext} carrying only a precomputed role mask —
     * the shape an authentication layer wires after resolving claim role names
     * through the registry.
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
