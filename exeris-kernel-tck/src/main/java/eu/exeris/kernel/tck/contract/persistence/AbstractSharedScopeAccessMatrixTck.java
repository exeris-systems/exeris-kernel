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
import java.util.function.IntSupplier;

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
 * predicate widens on the published shared scope while every write — insert, update, delete — stays
 * pinned to the owner, and the store operations below. The contract deliberately says nothing about how
 * the policy is expressed. A binding whose owner or scope column is not text (a {@code uuid}, for
 * instance) overrides {@link #ownerA()}, {@link #ownerB()} and {@link #sharedScope()} with values its
 * schema accepts.
 *
 * <h2>What is NOT here, on purpose</h2>
 * <p>Cross-tenant <em>mutation</em> of another owner's row is out of contract scope (ADR-012 §4b.4). It
 * appears below only as a denial — a forged insert, an update, a re-own and a delete of a partition-mate's
 * row — and MUST NOT be relaxed into an allowed cell by a binding. A single policy whose {@code USING}
 * widens on the shared scope fails the re-own and delete cells: in PostgreSQL that {@code USING} also
 * selects the rows {@code UPDATE} and {@code DELETE} reach, and a {@code WITH CHECK} pinned to the acting
 * tenant accepts a row that tenant has just re-owned.
 *
 * @since 0.11
 */
public abstract class AbstractSharedScopeAccessMatrixTck {

    /** The default {@link #ownerA()}: the first tenant participating in the shared partition. */
    protected static final String OWNER_A = "tenant-a";

    /** The default {@link #ownerB()}: the second tenant participating in the same partition. */
    protected static final String OWNER_B = "tenant-b";

    /** The default {@link #sharedScope()}: the tag both partition-mates publish. */
    protected static final String SHARED_SCOPE = "world-alpha";

    /** {@code OWNER_A} participating in the shared partition, under the default keys. */
    protected static final StorageContext CTX_A_SHARED =
            ImmutableStorageContext.shared(OWNER_A).withSharedScope(SHARED_SCOPE);

    /** {@code OWNER_B} participating in the same shared partition, under the default keys. */
    protected static final StorageContext CTX_B_SHARED =
            ImmutableStorageContext.shared(OWNER_B).withSharedScope(SHARED_SCOPE);

    /** {@code OWNER_A} declaring no shared scope, under the default keys. */
    protected static final StorageContext CTX_A_PRIVATE = ImmutableStorageContext.shared(OWNER_A);

    /**
     * Creates the contract; subclasses supply the store via {@link #seed}, {@link #readVisible},
     * {@link #updateValue}, {@link #reassignOwner} and {@link #delete}.
     */
    public AbstractSharedScopeAccessMatrixTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    // =========================================================================
    // Keys — overridable where the schema's owner or scope column is not text
    // =========================================================================

    /**
     * The first tenant of the shared partition.
     *
     * @return {@link #OWNER_A} unless a binding overrides it
     */
    protected String ownerA() {
        return OWNER_A;
    }

    /**
     * The second tenant of the same shared partition. Must differ from {@link #ownerA()}.
     *
     * @return {@link #OWNER_B} unless a binding overrides it
     */
    protected String ownerB() {
        return OWNER_B;
    }

    /**
     * The shared-scope tag both partition-mates publish.
     *
     * @return {@link #SHARED_SCOPE} unless a binding overrides it
     */
    protected String sharedScope() {
        return SHARED_SCOPE;
    }

    private StorageContext ctxAShared() {
        return ImmutableStorageContext.shared(ownerA()).withSharedScope(sharedScope());
    }

    private StorageContext ctxBShared() {
        return ImmutableStorageContext.shared(ownerB()).withSharedScope(sharedScope());
    }

    private StorageContext ctxAPrivate() {
        return ImmutableStorageContext.shared(ownerA());
    }

    private StorageContext ctxBPrivate() {
        return ImmutableStorageContext.shared(ownerB());
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

    /**
     * Sets the payload of every row whose payload is {@code value} and that {@code ctx} may update, to
     * {@code newValue}, through the binding.
     *
     * @param ctx      the acting storage context
     * @param value    the payload identifying the target row
     * @param newValue the payload to write
     * @return the number of rows the store updated; a binding may instead throw a
     *         {@link PersistenceProviderException} when the store refuses the statement
     */
    protected abstract int updateValue(StorageContext ctx, String value, String newValue);

    /**
     * Sets the owner of every row whose payload is {@code value} and that {@code ctx} may update, to
     * {@code newOwner}, through the binding.
     *
     * @param ctx      the acting storage context
     * @param value    the payload identifying the target row
     * @param newOwner the owner column value to write
     * @return the number of rows the store updated; a binding may instead throw a
     *         {@link PersistenceProviderException} when the store refuses the statement
     */
    protected abstract int reassignOwner(StorageContext ctx, String value, String newOwner);

    /**
     * Deletes every row whose payload is {@code value} and that {@code ctx} may delete, through the
     * binding.
     *
     * @param ctx   the acting storage context
     * @param value the payload identifying the target row
     * @return the number of rows the store deleted; a binding may instead throw a
     *         {@link PersistenceProviderException} when the store refuses the statement
     */
    protected abstract int delete(StorageContext ctx, String value);

    /** Seeds the fixture rows. Bindings call this once the store exists. */
    protected final void seedMatrixRows() {
        seed(ctxAShared(), ownerA(), sharedScope(), "a-in-scope");
        seed(ctxBShared(), ownerB(), sharedScope(), "b-in-scope");
        seed(ctxAPrivate(), ownerA(), null, "a-private");
    }

    // =========================================================================
    // The matrix
    // =========================================================================

    @Test
    @DisplayName("read widens — a tenant sees a partition-mate's row inside the shared scope")
    void assert_read_widens_within_shared_scope() {
        assertThat(readVisible(ctxAShared()))
                .as("a declared shared scope must make partition-mates' rows visible — that is the "
                        + "entire point of the tier (ADR-012 §4b.4)")
                .contains("a-in-scope", "b-in-scope");

        assertThat(readVisible(ctxBShared()))
                .as("the widening is a property of the partition, not of one tenant, so it must be "
                        + "symmetric")
                .contains("a-in-scope", "b-in-scope");
    }

    @Test
    @DisplayName("write stays pinned — a tenant cannot write a row owned by a partition-mate")
    void assert_write_stays_pinned_to_owner() {
        assertThatThrownBy(() -> seed(ctxAShared(), ownerB(), sharedScope(), "a-forging-b"))
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

        assertThat(readVisible(ctxBShared()))
                .as("and the refused write left nothing behind")
                .doesNotContain("a-forging-b");
    }

    @Test
    @DisplayName("no bleed — a later request declaring no shared scope does not inherit the widening")
    void assert_shared_scope_does_not_leak_onto_an_unscoped_request() {
        // Exercise the widened path first so any connection reuse carries the published setting.
        assertThat(readVisible(ctxAShared())).contains("b-in-scope");

        assertThat(readVisible(ctxAPrivate()))
                .as("the same tenant, now declaring no shared scope, must be tenant-private again. "
                        + "Session-scoped settings survive connection reuse, so a binding that only "
                        + "publishes a scope when one is present silently widens a request that never "
                        + "asked to participate")
                .doesNotContain("b-in-scope");
    }

    @Test
    @DisplayName("tenant-private is unchanged — absence behaves exactly as before the tier existed")
    void assert_absent_shared_scope_is_tenant_private() {
        assertThat(readVisible(ctxAPrivate()))
                .as("everything the tenant owns")
                .contains("a-private", "a-in-scope");

        assertThat(readVisible(ctxAPrivate()))
                .as("and nothing of a partition-mate's, because no scope was declared")
                .doesNotContain("b-in-scope");
    }

    @Test
    @DisplayName("update stays pinned — a tenant cannot change a partition-mate's row it can read")
    void assert_update_of_a_partition_mate_row_is_refused() {
        assertRefused(() -> updateValue(ctxAShared(), "b-in-scope", "a-rewrote-b"),
                "a tenant that can read a partition-mate's row through the shared scope MUST NOT be "
                        + "able to change it (ADR-012 §4b.4)");

        assertThat(readVisible(ctxBPrivate()))
                .as("the partition-mate's row is untouched")
                .contains("b-in-scope")
                .doesNotContain("a-rewrote-b");
    }

    @Test
    @DisplayName("re-own stays pinned — a tenant cannot take ownership of a partition-mate's row")
    void assert_reassigning_a_partition_mate_row_to_oneself_is_refused() {
        assertRefused(() -> reassignOwner(ctxAShared(), "b-in-scope", ownerA()),
                "a write check pinned to the acting tenant accepts a row the tenant has just made its "
                        + "own, so the row it may reach for update must itself be pinned to the owner. "
                        + "A binding that allows this lets a tenant take any row it can read");

        assertThat(readVisible(ctxBPrivate()))
                .as("the row still belongs to the partition-mate")
                .contains("b-in-scope");
        assertThat(readVisible(ctxAPrivate()))
                .as("and did not become the acting tenant's")
                .doesNotContain("b-in-scope");
    }

    @Test
    @DisplayName("delete stays pinned — a tenant cannot delete a partition-mate's row it can read")
    void assert_delete_of_a_partition_mate_row_is_refused() {
        assertRefused(() -> delete(ctxAShared(), "b-in-scope"),
                "a tenant that can read a partition-mate's row through the shared scope MUST NOT be "
                        + "able to delete it (ADR-012 §4b.4)");

        assertThat(readVisible(ctxBPrivate()))
                .as("the partition-mate's row still exists")
                .contains("b-in-scope");
    }

    @Test
    @DisplayName("the owner still writes — a tenant updates and deletes its own row inside the shared scope")
    void assert_owner_updates_and_deletes_its_own_in_scope_row() {
        // The positive control for the three denials above: a policy that refused every write would
        // pass them, and it would also break the tier, whose owner must keep full use of its rows.
        assertThat(updateValue(ctxAShared(), "a-in-scope", "a-updated"))
                .as("the owner updates its own in-scope row")
                .isEqualTo(1);
        assertThat(readVisible(ctxBShared()))
                .as("and a partition-mate reads the update")
                .contains("a-updated")
                .doesNotContain("a-in-scope");

        assertThat(delete(ctxAShared(), "a-updated"))
                .as("the owner deletes its own in-scope row")
                .isEqualTo(1);
        assertThat(readVisible(ctxAPrivate()))
                .as("and it is gone")
                .doesNotContain("a-updated");
    }

    /**
     * Passes when the store refuses the mutation, either by throwing a {@link PersistenceProviderException}
     * or by reporting that no row was affected. A binding chooses which; both leave the row as it was,
     * which the calling case then checks.
     */
    private static void assertRefused(IntSupplier mutation, String reason) {
        int affected;
        try {
            affected = mutation.getAsInt();
        } catch (PersistenceProviderException refused) {
            return;
        }
        assertThat(affected).as(reason).isZero();
    }
}
