/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: the shared-vs-tenant access matrix every persistence binding must satisfy once it claims to
 * enforce the shared-scope tier (ADR-012 §4b.4 / §9).
 *
 * <h2>Why this is its own suite rather than a nested contract in {@link AbstractPersistenceEngineTck}</h2>
 * <p>ADR-012 §9 originally named {@code AbstractPersistenceEngineTck} as the host. That suite's only
 * in-repo binding runs on H2 in PostgreSQL-compatibility mode, and H2 implements neither
 * {@code CREATE POLICY} nor {@code current_setting} — so a matrix hosted there could only ever be skipped,
 * which is precisely the vacuous-anchor failure this repository has already had to fix once. Splitting it
 * out lets the binding be a live-database suite while keeping the obligation abstract and enforceable, so
 * an out-of-repo binding cannot claim shared-scope support without passing these cases.
 *
 * <h2>What a binding must provide</h2>
 * <p>A store of rows carrying an owner and a shared-scope tag, an RLS (or equivalent) policy whose read
 * predicate widens on the published shared scope and whose write predicate stays pinned to the owner, and
 * the two operations below. The contract deliberately says nothing about how the policy is expressed.
 *
 * <h2>What is NOT here, on purpose</h2>
 * <p>Cross-tenant <em>mutation</em> of another owner's row is out of contract scope (ADR-012 §4b.4). It
 * appears below only as a denial, and MUST NOT be relaxed into an allowed cell by a binding.
 *
 * @since 0.11
 */
public abstract class AbstractSharedScopeAccessMatrixTck {

    /** The first tenant participating in the shared partition under test. */
    protected static final String OWNER_A = "tenant-a";

    /** The second tenant participating in the same shared partition under test. */
    protected static final String OWNER_B = "tenant-b";

    /** The shared-scope tag both {@link #CTX_A_SHARED} and {@link #CTX_B_SHARED} publish. */
    protected static final String SHARED_SCOPE = "world-alpha";

    /** {@code OWNER_A} participating in the shared partition. */
    protected static final StorageContext CTX_A_SHARED =
            ImmutableStorageContext.shared(OWNER_A).withSharedScope(SHARED_SCOPE);

    /** {@code OWNER_B} participating in the same shared partition. */
    protected static final StorageContext CTX_B_SHARED =
            ImmutableStorageContext.shared(OWNER_B).withSharedScope(SHARED_SCOPE);

    /** {@code OWNER_A} declaring no shared scope — the tenant-private default. */
    protected static final StorageContext CTX_A_PRIVATE = ImmutableStorageContext.shared(OWNER_A);

    /**
     * Creates the contract; subclasses supply the store via {@link #seed} and {@link #readVisible}.
     */
    public AbstractSharedScopeAccessMatrixTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    // =========================================================================
    // Template methods — binding supplies the store
    // =========================================================================

    /**
     * Writes one row through the binding, under {@code ctx}.
     *
     * <p>{@code owner} is passed separately from {@code ctx} so the write-pin case can attempt a row
     * whose owner is <em>not</em> the acting tenant; a conforming binding must refuse that.
     *
     * @param ctx         the acting storage context
     * @param owner       the owner column value to write
     * @param sharedScope the shared-scope tag for the row, or {@code null} for none
     * @param value       an identifying payload, returned by {@link #readVisible}
     */
    protected abstract void seed(StorageContext ctx, String owner, String sharedScope, String value);

    /**
     * Values of every row visible to {@code ctx}, in any order.
     *
     * @param ctx the acting storage context whose visibility is under test
     * @return the payload values seeded by {@link #seed} that {@code ctx} can currently read
     */
    protected abstract List<String> readVisible(StorageContext ctx);

    /** Seeds the fixture rows. Bindings call this once the store exists. */
    protected final void seedMatrixRows() {
        seed(CTX_A_SHARED, OWNER_A, SHARED_SCOPE, "a-in-scope");
        seed(CTX_B_SHARED, OWNER_B, SHARED_SCOPE, "b-in-scope");
        seed(CTX_A_PRIVATE, OWNER_A, null, "a-private");
    }

    // =========================================================================
    // The matrix
    // =========================================================================

    @Test
    @DisplayName("read widens — a tenant sees a partition-mate's row inside the shared scope")
    void assert_read_widens_within_shared_scope() {
        assertThat(readVisible(CTX_A_SHARED))
                .as("a declared shared scope must make partition-mates' rows visible — that is the "
                        + "entire point of the tier (ADR-012 §4b.4)")
                .contains("a-in-scope", "b-in-scope");

        assertThat(readVisible(CTX_B_SHARED))
                .as("the widening is a property of the partition, not of one tenant, so it must be "
                        + "symmetric")
                .contains("a-in-scope", "b-in-scope");
    }

    @Test
    @DisplayName("write stays pinned — a tenant cannot write a row owned by a partition-mate")
    void assert_write_stays_pinned_to_owner() {
        assertThatThrownBy(() -> seed(CTX_A_SHARED, OWNER_B, SHARED_SCOPE, "a-forging-b"))
                .as("reads widening MUST NOT relax writes: inside the shared partition a tenant may "
                        + "still only write rows it owns. A binding that lets this through has made "
                        + "cross-tenant mutation possible, which ADR-012 §4b.4 puts out of scope")
                // PersistenceProviderException, not Exception. Exception.class accepted anything at
                // all, so a fixture that never reached the database — a missing table, an unbound
                // context, a null connection — read as "the store refused the write", which is the
                // one reading this case must not have. Excluding a list of harness-failure types
                // instead was no better: it still passes on any type nobody thought to list.
                // Naming the persistence family says the refusal came from the store; the SQLSTATE
                // underneath stays the binding's business.
                .isInstanceOf(PersistenceProviderException.class);

        assertThat(readVisible(CTX_B_SHARED))
                .as("and the refused write left nothing behind")
                .doesNotContain("a-forging-b");
    }

    @Test
    @DisplayName("no bleed — a later request declaring no shared scope does not inherit the widening")
    void assert_shared_scope_does_not_leak_onto_an_unscoped_request() {
        // Exercise the widened path first so any connection reuse carries the published setting.
        assertThat(readVisible(CTX_A_SHARED)).contains("b-in-scope");

        assertThat(readVisible(CTX_A_PRIVATE))
                .as("the same tenant, now declaring no shared scope, must be tenant-private again. "
                        + "Session-scoped settings survive connection reuse, so a binding that only "
                        + "publishes a scope when one is present silently widens a request that never "
                        + "asked to participate")
                .doesNotContain("b-in-scope");
    }

    @Test
    @DisplayName("tenant-private is unchanged — absence behaves exactly as before the tier existed")
    void assert_absent_shared_scope_is_tenant_private() {
        assertThat(readVisible(CTX_A_PRIVATE))
                .as("everything the tenant owns")
                .contains("a-private", "a-in-scope");

        assertThat(readVisible(CTX_A_PRIVATE))
                .as("and nothing of a partition-mate's, because no scope was declared")
                .doesNotContain("b-in-scope");
    }
}
