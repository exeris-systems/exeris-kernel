/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
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
 * pinned to the owner <em>and to the published scope tag</em>, and the store operations below. The
 * contract deliberately says nothing about how the policy is expressed. A binding whose owner or scope
 * column is not text (a {@code uuid}, for instance) overrides {@link #ownerA()}, {@link #ownerB()},
 * {@link #sharedScope()} and {@link #foreignScope()} with values its schema accepts.
 *
 * <p>A {@code null} passed for a tag means "none": the binding writes {@code NULL} or {@code ''}, as its
 * schema allows. Both are tenant-private, and a written row is either tenant-private or tagged with the
 * scope the acting request published, never with a scope the request does not hold. A cross-scope move
 * from a scope the request does not hold into the one it publishes has no cell here: the check sees only
 * the new row, so a reference policy admits it and a stricter deployment trigger may refuse it.
 *
 * <p>A context that declares no tenant is not a context the policy may serve. The kernel publishes the
 * tenant key as {@code ''} for it and refuses nothing at the persistence boundary, so what the store does
 * with that value is the policy's decision, and a conforming policy raises: a read and a write from a
 * tenant-less context both fail with {@code EX-PERS-5003} instead of returning an empty result or
 * admitting a row owned by the empty tenant (ADR-012 §4c.3). A binding states which owner value its policy
 * would match against an unpublished key through {@link #tenantlessOwner()}.
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

    /** The default {@link #foreignScope()}: a tag no request in this matrix publishes. */
    protected static final String FOREIGN_SCOPE = "world-beta";

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

    /**
     * A scope tag the acting requests do not hold. Must differ from {@link #sharedScope()}.
     *
     * @return {@link #FOREIGN_SCOPE} unless a binding overrides it
     */
    protected String foreignScope() {
        return FOREIGN_SCOPE;
    }

    /**
     * The owner-column value a policy that compared the owner against an unpublished tenant key would
     * match, written by the tenant-less write cell. For a text column that is {@code ''}, the value the
     * kernel publishes for an absent tenant: a policy that merely compares the owner with the setting
     * admits that row, and a conforming one refuses it.
     *
     * <p>Not defaulted: the value depends on the owner column's type, and a default that is wrong for the
     * binding's schema would make the write cell pass because the column rejects the value, not because
     * the policy refuses the tenant-less context. The default throws, so a binding that does not
     * override it fails the write cell instead of skipping it. A binding whose owner column cannot hold
     * the empty value, a {@code uuid} for instance, returns any value the column accepts; the policy's
     * cast then raises on the unpublished key whatever the row carries.
     *
     * @return the owner value a tenant-less write attempts
     */
    protected String tenantlessOwner() {
        throw new UnsupportedOperationException(
                "A binding of AbstractSharedScopeAccessMatrixTck must override tenantlessOwner to name the "
                        + "owner value an unpublished tenant key would match (ADR-012 §4c.3)");
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

    private StorageContext ctxBForeign() {
        return ImmutableStorageContext.shared(ownerB()).withSharedScope(foreignScope());
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

    /**
     * Sets the shared-scope tag of every row whose payload is {@code value} and that {@code ctx} may
     * update, to {@code newSharedScope}, through the binding. A {@code null} tag means "none"
     * (tenant-private).
     *
     * <p>Not abstract; the default throws, because an inherited no-op would let the re-tag cells report
     * green for a binding that never implemented them.
     *
     * @param ctx            the acting storage context
     * @param value          the payload identifying the target row
     * @param newSharedScope the tag to write, or {@code null} for none
     * @return the number of rows the store updated; a binding may instead throw a
     *         {@link PersistenceProviderException} when the store refuses the statement
     */
    protected int retagScope(StorageContext ctx, String value, String newSharedScope) {
        throw new UnsupportedOperationException(
                "A binding of AbstractSharedScopeAccessMatrixTck must override retagScope to pin the "
                        + "shared-scope tag on update (ADR-012 §4c.2)");
    }

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

    @Test
    @DisplayName("insert pins the tag — a tenant cannot publish an own row into a scope its request does not hold")
    void assert_insert_tagged_with_a_scope_not_held_is_refused() {
        assertThatThrownBy(() -> seed(ctxAShared(), ownerA(), foreignScope(), "a-forged-foreign"))
                .as("a written row is tenant-private or tagged with the scope the request published; a "
                        + "row's own column must not grant visibility that no token carried "
                        + "(ADR-012 §4c.2)")
                .isInstanceOf(PersistenceProviderException.class);

        assertThat(readVisible(ctxAPrivate()))
                .as("and the refused insert left nothing behind")
                .doesNotContain("a-forged-foreign");
    }

    @Test
    @DisplayName("insert keeps the published tag — a tenant publishes an own row into the scope its request holds")
    void assert_insert_tagged_with_the_published_scope_is_accepted() {
        // The direction control for the refusal above: it differs only in the tag written.
        seed(ctxAShared(), ownerA(), sharedScope(), "a-new-in-scope");

        assertThat(readVisible(ctxBShared()))
                .as("a partition-mate reads the row, so the tag was stored and not silently dropped")
                .contains("a-new-in-scope");
    }

    @Test
    @DisplayName("insert without a tag stays private — an untagged row under a published scope is not widened")
    void assert_untagged_insert_under_a_published_scope_stays_private() {
        seed(ctxAShared(), ownerA(), null, "a-new-private");

        assertThat(readVisible(ctxBShared()))
                .as("an untagged row is tenant-private even when the request published a scope")
                .doesNotContain("a-new-private");
        assertThat(readVisible(ctxAPrivate()))
                .as("and the owner reads it, so the write was admitted")
                .contains("a-new-private");
    }

    @Test
    @DisplayName("insert pins the tag — a request declaring no scope cannot write a scoped row")
    void assert_unscoped_request_cannot_insert_a_scoped_row() {
        assertThatThrownBy(() -> seed(ctxAPrivate(), ownerA(), sharedScope(), "a-unscoped-tag"))
                .as("a request that published no scope holds no scope to tag a row with")
                .isInstanceOf(PersistenceProviderException.class);

        assertThat(readVisible(ctxBShared()))
                .as("and the refused insert left nothing a partition-mate can read")
                .doesNotContain("a-unscoped-tag");
    }

    @Test
    @DisplayName("an unscoped request still writes private rows — only the tag is pinned, not the write")
    void assert_unscoped_request_inserts_an_untagged_row() {
        seed(ctxAPrivate(), ownerA(), null, "a-unscoped-private");

        assertThat(readVisible(ctxAPrivate()))
                .as("the direction control for the refusal above: it differs only in the tag written")
                .contains("a-unscoped-private");
    }

    @Test
    @DisplayName("re-tag pins the tag — a tenant cannot move its own private row into a scope it does not hold")
    void assert_retag_to_a_scope_not_held_is_refused() {
        assertRefused(() -> retagScope(ctxAShared(), "a-private", foreignScope()),
                "an update is a write: the new row's tag must be one the request holds, or an owner "
                        + "could publish a row into any scope by updating it (ADR-012 §4c.2)");

        assertThat(readVisible(ctxBForeign()))
                .as("the foreign scope's members still cannot read the row")
                .doesNotContain("a-private");
        assertThat(readVisible(ctxAPrivate()))
                .as("and it is still the owner's, with its previous tag")
                .contains("a-private");
    }

    @Test
    @DisplayName("re-tag keeps the published tag — a tenant publishes its own private row into the scope it holds")
    void assert_retag_to_the_published_scope_is_accepted() {
        assertThat(retagScope(ctxAShared(), "a-private", sharedScope()))
                .as("the direction control for the refusal above: it differs only in the new tag")
                .isEqualTo(1);

        assertThat(readVisible(ctxBShared()))
                .as("a different tenant reads the row, so the new tag was stored")
                .contains("a-private");
    }

    @Test
    @DisplayName("un-share is admitted — an owner narrows its own in-scope row from a request not holding the scope")
    void assert_owner_unshares_its_row_from_a_request_not_holding_the_scope() {
        assertThat(retagScope(ctxAPrivate(), "a-in-scope", null))
                .as("narrowing one's own row widens nothing, so the check admits it (ADR-012 §4c.2)")
                .isEqualTo(1);

        assertThat(readVisible(ctxBShared()))
                .as("and the partition-mate no longer reads the row")
                .doesNotContain("a-in-scope");
    }

    @Test
    @DisplayName("un-share is not a re-tag — the same request cannot move the row into a scope it does not hold")
    void assert_retag_from_an_unscoped_request_to_a_foreign_scope_is_refused() {
        assertRefused(() -> retagScope(ctxAPrivate(), "a-in-scope", foreignScope()),
                "the direction control for the un-share above: it differs only in the new tag, and a "
                        + "tag the request does not hold is refused");

        assertThat(readVisible(ctxBForeign()))
                .as("the foreign scope's members still cannot read the row")
                .doesNotContain("a-in-scope");
        assertThat(readVisible(ctxBShared()))
                .as("and the row keeps its previous tag")
                .contains("a-in-scope");
    }

    @Test
    @DisplayName("an absent tenant raises on read — a tenant-less context is refused, not served an empty result")
    void assert_read_from_a_tenantless_context_is_refused() {
        assertThatThrownBy(() -> readVisible(ImmutableStorageContext.GLOBAL))
                .as("the kernel publishes '' for an absent tenant and the policy decides what it yields; "
                        + "an empty result makes a mis-scoped read look like a read of an empty table, so "
                        + "a conforming policy raises (ADR-012 §4c.3)")
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(refusal -> assertThat(((PersistenceProviderException) refusal).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_PERS_5003));
    }

    @Test
    @DisplayName("a tenant reads — the same read from a context that declares a tenant succeeds")
    void assert_read_from_a_tenant_context_is_served() {
        // The direction control for the refusal above: it differs only in the tenant the context carries.
        assertThat(readVisible(ctxAPrivate()))
                .as("a raise on every read would pass the refusal and break the tier")
                .contains("a-private", "a-in-scope");
    }

    @Test
    @DisplayName("an absent tenant raises on write — a tenant-less context cannot write a row owned by the empty tenant")
    void assert_write_from_a_tenantless_context_is_refused() {
        assertThatThrownBy(() -> seed(ImmutableStorageContext.GLOBAL, tenantlessOwner(), null, "no-tenant-write"))
                .as("a write check that only compares the owner with the published key admits a row owned "
                        + "by the empty tenant, which every later tenant-less request reads back; a "
                        + "conforming policy raises instead (ADR-012 §4c.3)")
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(refusal -> assertThat(((PersistenceProviderException) refusal).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_PERS_5003));

        assertThat(readVisible(ctxAPrivate()))
                .as("and the refused write left nothing behind")
                .doesNotContain("no-tenant-write");
    }

    @Test
    @DisplayName("a tenant writes — the same write from a context that declares a tenant succeeds")
    void assert_write_from_a_tenant_context_is_admitted() {
        // The direction control for the refusal above: it differs only in the tenant the context carries,
        // and in the owner that tenant owns.
        seed(ctxAPrivate(), ownerA(), null, "tenant-write");

        assertThat(readVisible(ctxAPrivate()))
                .as("the row was admitted, so the write was refused for the absent tenant and not for the write")
                .contains("tenant-write");
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
