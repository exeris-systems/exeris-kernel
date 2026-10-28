/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: a persistence binding that installs its row-level-security interceptor refuses a database role
 * that bypasses row-level security, unless the operator consented (ADR-012 §4c.4 / §9).
 *
 * <h2>Why a role is the unit under test</h2>
 * <p>A role that is {@code SUPERUSER} or has {@code BYPASSRLS} skips every policy, {@code FORCE}d ones
 * included. Isolation then rests on application code alone, which is the guarantee the interceptor exists
 * to move into the database. The refusal is therefore a property of the pair "interceptor installed,
 * role bypasses", and each cell below differs from its control in exactly one of those inputs.
 *
 * <h2>What a binding must provide</h2>
 * <p>A live database with one role per {@link RoleKind}, and a boot that installs the binding's
 * row-level-security interceptor under the role and consent it is given. The suite says nothing about how
 * the binding detects the attribute. A binding for a database with no such attribute is out of scope and
 * does not extend this suite.
 *
 * <h2>What is NOT here, on purpose</h2>
 * <p>The downgrade's telemetry (an error log line and a JFR event) is asserted by the binding's own test:
 * this module depends on the SPI only, and the event type lives in Core.
 *
 * @since 0.13
 */
public abstract class AbstractRlsBypassRoleCheckTck {

    /** The role attributes the suite provisions. */
    public enum RoleKind {
        /** {@code NOSUPERUSER NOBYPASSRLS}: subject to every policy. */
        ORDINARY,
        /** {@code NOSUPERUSER BYPASSRLS}: skips policies through the attribute alone. */
        BYPASSRLS,
        /** {@code SUPERUSER NOBYPASSRLS}: skips policies through superuser status alone. */
        SUPERUSER
    }

    /**
     * Creates the contract; subclasses supply the roles and the boot via {@link #roleName(RoleKind)},
     * {@link #bootWithRlsAs(RoleKind, boolean)} and {@link #tenantContext()}.
     */
    public AbstractRlsBypassRoleCheckTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    // =========================================================================
    // Template methods — binding supplies the roles and the boot
    // =========================================================================

    /**
     * The name of the provisioned role for {@code kind}, as the database reports it for that login.
     *
     * @param kind the role attributes
     * @return the role name; the refusal's {@code rawArgs[1]} must equal it
     */
    protected abstract String roleName(RoleKind kind);

    /**
     * Boots an engine that connects as the role of {@code kind} and installs the binding's
     * row-level-security interceptor, with the operator's consent set to {@code allowBypassRole}.
     *
     * <p>A refused boot throws whatever the binding throws; it must not swallow the refusal. A binding
     * that cannot reach the refusal by returning an engine (for instance one that checks at
     * registration) lets the exception propagate from here.
     *
     * @param kind            the role to connect as
     * @param allowBypassRole whether the operator consented to a bypassing role
     * @return the booted engine; the suite closes it
     */
    protected abstract PersistenceEngine bootWithRlsAs(RoleKind kind, boolean allowBypassRole);

    /**
     * A tenant context the booted engine can open a connection for.
     *
     * @return a context whose connection the interceptor can configure
     */
    protected abstract StorageContext tenantContext();

    // =========================================================================
    // The cells
    // =========================================================================

    @Test
    @DisplayName("a BYPASSRLS role is refused with EX-PERS-5009 naming the role and the attribute")
    void assert_bypassrls_role_is_refused() {
        assertThatThrownBy(() -> closeAfterBoot(RoleKind.BYPASSRLS, false))
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(failure -> {
                    PersistenceProviderException refusal = (PersistenceProviderException) failure;
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5009);
                    assertThat(refusal.rawArgs()[1]).isEqualTo(roleName(RoleKind.BYPASSRLS));
                    assertThat(refusal.rawArgs()[2]).isEqualTo("BYPASSRLS");
                });
    }

    @Test
    @DisplayName("an ordinary role boots and serves a connection — the control for the BYPASSRLS refusal")
    void assert_ordinary_role_boots() {
        assertThatCode(() -> openAndCloseConnection(RoleKind.ORDINARY, false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a BYPASSRLS role boots when the operator consented — the control for the missing consent")
    void assert_bypassrls_role_boots_with_consent() {
        assertThatCode(() -> openAndCloseConnection(RoleKind.BYPASSRLS, true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a SUPERUSER role without BYPASSRLS is refused with EX-PERS-5009 naming SUPERUSER")
    void assert_superuser_role_is_refused() {
        assertThatThrownBy(() -> closeAfterBoot(RoleKind.SUPERUSER, false))
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(failure -> {
                    PersistenceProviderException refusal = (PersistenceProviderException) failure;
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5009);
                    assertThat(refusal.rawArgs()[1]).isEqualTo(roleName(RoleKind.SUPERUSER));
                    assertThat((String) refusal.rawArgs()[2]).contains("SUPERUSER");
                });
    }

    private void closeAfterBoot(RoleKind kind, boolean allowBypassRole) {
        try (PersistenceEngine ignored = bootWithRlsAs(kind, allowBypassRole)) {
            // Reaching here means the boot was not refused; the caller asserts on the refusal.
            assertThat(ignored).isNotNull();
        }
    }

    private void openAndCloseConnection(RoleKind kind, boolean allowBypassRole) {
        try (PersistenceEngine engine = bootWithRlsAs(kind, allowBypassRole);
             PersistenceConnection connection = engine.openConnection(tenantContext())) {
            assertThat(connection).isNotNull();
        }
    }
}
