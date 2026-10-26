/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Persistence Subsystem — StorageContext isolation leak test.
 *
 * <h2>What is verified (persistence.md)</h2>
 * <blockquote>"Isolation Leak Test: Verify that isolationKey A cannot see data from isolationKey B."
 * </blockquote>
 *
 * <p>This is the most critical persistence security test. A bug here means tenant A can read
 * tenant B's data — a catastrophic confidentiality breach in a multi-tenant deployment.
 *
 * <h2>Test Strategy</h2>
 * <ol>
 *   <li>Write a sentinel row with a value unique to {@code isolationKeyA}.</li>
 *   <li>Write a sentinel row with a value unique to {@code isolationKeyB}.</li>
 *   <li>Query the sentinel table under context A — must see A's row, MUST NOT see B's row.</li>
 *   <li>Query the sentinel table under context B — must see B's row, MUST NOT see A's row.</li>
 *   <li>Run both queries concurrently in a {@code StructuredTaskScope} to expose
 *       any ScopedValue mis-propagation between virtual threads.</li>
 * </ol>
 *
 * <h2>Absent tenant</h2>
 * <p>A context that declares no tenant ({@link ImmutableStorageContext#GLOBAL}) is a supported input
 * the kernel does not refuse; it publishes the tenant key as {@code ''}. A conforming policy raises on
 * that value rather than returning an empty result or admitting a row owned by the empty tenant, so a
 * read and a write from such a context both fail with {@code EX-PERS-5003} (ADR-012 §4c.3). Each
 * refusal is paired with the same operation under a tenant, which must succeed.
 *
 * <h2>VT Pinning check</h2>
 * <p>The JDBC driver MUST NOT pin the carrier thread during the isolation query.
 * This is verified via {@code @Timeout} — if the VT pins and blocks, the test times out.
 *
 * @since 0.5
 */
@DisplayName("Persistence Isolation Leak TCK")
public abstract class PersistenceIsolationLeakTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine} with an in-memory test schema.
     *
     * @return an engine whose connections can each be opened under a distinct
     *         {@link StorageContext}
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Returns the isolation key string for "Tenant A".
     * Must be distinct from {@link #isolationKeyB()}.
     * Default: {@code "tenant-alpha"}.
     *
     * @return Tenant A's isolation key
     */
    protected String isolationKeyA() {
        return "tenant-alpha";
    }

    /**
     * Returns the isolation key string for "Tenant B".
     * Must be distinct from {@link #isolationKeyA()}.
     * Default: {@code "tenant-beta"}.
     *
     * @return Tenant B's isolation key
     */
    protected String isolationKeyB() {
        return "tenant-beta";
    }

    /**
     * The SQL to write a sentinel row bound to the given isolation key.
     * The implementation must ensure each key's data is physically isolated
     * (e.g., via RLS, schema, or separate DataSource).
     *
     * <p>Example for RLS: {@code "INSERT INTO sentinel(value) VALUES (?)"} where
     * the RLS policy filters by the session-local {@code exeris.tenant_id}.
     *
     * @param conn  the connection, already opened under the acting isolation key's
     *              {@link StorageContext}
     * @param value the sentinel value to persist, unique per call
     */
    protected abstract void writeSentinelRow(PersistenceConnection conn, String value);

    /**
     * Query sentinel rows visible under the current connection.
     * Returns all visible sentinel values.
     *
     * @param conn the connection, already opened under the acting isolation key's
     *             {@link StorageContext}
     * @return the sentinel values visible to {@code conn}, in any order
     */
    protected abstract List<String> readSentinelRows(PersistenceConnection conn);

    // =========================================================================
    // Fixtures
    // =========================================================================

    private PersistenceEngine engine;

    /**
     * Creates the contract; subclasses supply the engine via {@link #createEngine()} and the
     * sentinel write/read bindings via {@link #writeSentinelRow} and {@link #readSentinelRows}.
     */
    public PersistenceIsolationLeakTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUp() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDown() {
        if (engine != null) engine.close();
    }

    private StorageContext contextFor(String isolationKey) {
        return ImmutableStorageContext.shared(isolationKey);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("Tenant A cannot read Tenant B's sentinel rows (isolation boundary)")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void tenantACannotSeeTenantBData() {
        String sentinelA = "SENTINEL-ALPHA-" + System.nanoTime();
        String sentinelB = "SENTINEL-BETA-" + System.nanoTime();

        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyA())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyA()))) {
                writeSentinelRow(conn, sentinelA);
            }
        });

        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyB())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyB()))) {
                writeSentinelRow(conn, sentinelB);
            }
        });

        List<String> visibleToA = new ArrayList<>();
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyA())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyA()))) {
                visibleToA.addAll(readSentinelRows(conn));
            }
        });

        assertThat(visibleToA)
                .as("Tenant A MUST see its own sentinel row and MUST NOT see Tenant B's sentinel row. " +
                        "ISOLATION BREACH: If this assertion fails, the RLS/schema isolation is not working — " +
                        "Tenant A can read Tenant B's data. This is a critical security bug.")
                .contains(sentinelA)
                .doesNotContain(sentinelB);
    }

    @Test
    @DisplayName("Tenant B cannot read Tenant A's sentinel rows (symmetric isolation)")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void tenantBCannotSeeTenantAData() {
        String sentinelA = "SENTINEL-A2-" + System.nanoTime();
        String sentinelB = "SENTINEL-B2-" + System.nanoTime();

        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyA())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyA()))) {
                writeSentinelRow(conn, sentinelA);
            }
        });
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyB())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyB()))) {
                writeSentinelRow(conn, sentinelB);
            }
        });

        List<String> visibleToB = new ArrayList<>();
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyB())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyB()))) {
                visibleToB.addAll(readSentinelRows(conn));
            }
        });

        assertThat(visibleToB)
                .as("Tenant B MUST see its own sentinel row. " +
                        "ISOLATION BREACH: Tenant B MUST NOT see Tenant A's sentinel row.")
                .contains(sentinelB)
                .doesNotContain(sentinelA);
    }

    @Test
    @DisplayName("CRITICAL: Concurrent VT queries — ScopedValue context not leaked between threads")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void scopedValueNotLeakedBetweenConcurrentVirtualThreads() throws Exception {
        String sentinelA = "VT-SENTINEL-A-" + System.nanoTime();
        String sentinelB = "VT-SENTINEL-B-" + System.nanoTime();

        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyA())).run(() -> {
            try (PersistenceConnection c = engine.openConnection(contextFor(isolationKeyA()))) {
                writeSentinelRow(c, sentinelA);
            }
        });
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyB())).run(() -> {
            try (PersistenceConnection c = engine.openConnection(contextFor(isolationKeyB()))) {
                writeSentinelRow(c, sentinelB);
            }
        });

        List<String> seenByA = new ArrayList<>();
        List<String> seenByB = new ArrayList<>();

        try (TckScope scope = TckScope.openFailFast()) {

            scope.fork(() ->
                    ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyA())).call(() -> {
                        try (PersistenceConnection c = engine.openConnection(contextFor(isolationKeyA()))) {
                            seenByA.addAll(readSentinelRows(c));
                        }
                        return null;
                    })
            );

            scope.fork(() ->
                    ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyB())).call(() -> {
                        try (PersistenceConnection c = engine.openConnection(contextFor(isolationKeyB()))) {
                            seenByB.addAll(readSentinelRows(c));
                        }
                        return null;
                    })
            );

            scope.join();
        }

        assertThat(seenByA)
                .as("Concurrent VT: Tenant A's context MUST isolate its query. " +
                        "Seeing Tenant B's sentinel means ScopedValue bled across VTs " +
                        // CHECKSTYLE:OFF: this string NAMES the banned type as the thing the contract forbids;
                        //                 the L0 regex cannot tell a message about a ban from a use of it.
                        "(banned ThreadLocal-style cross-contamination).")
                        // CHECKSTYLE:ON
                .doesNotContain(sentinelB);

        assertThat(seenByB)
                .as("Concurrent VT: Tenant B's context MUST isolate its query.")
                .doesNotContain(sentinelA);
    }

    @Test
    @DisplayName("An absent tenant raises on read — a tenant-less context is refused, not served an empty result")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void readFromATenantlessContextIsRefused() {
        // The table must hold a row: a policy that raises while it evaluates a row has nothing to
        // evaluate on an empty table, so a read of one is not a statement the refusal is pinned for.
        String sentinel = "SENTINEL-READ-CONTROL-" + System.nanoTime();
        try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyA()))) {
            writeSentinelRow(conn, sentinel);
        }

        assertThatThrownBy(() -> {
            try (PersistenceConnection conn = engine.openConnection(ImmutableStorageContext.GLOBAL)) {
                readSentinelRows(conn);
            }
        })
                .as("a mis-scoped read that returns an empty result looks like a read of an empty table; "
                        + "the policy must raise on the unpublished tenant key (ADR-012 §4c.3)")
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(refusal -> assertThat(((PersistenceProviderException) refusal).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_PERS_5003));

        // The direction control: the same read under a tenant is served.
        List<String> visible = new ArrayList<>();
        try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyA()))) {
            visible.addAll(readSentinelRows(conn));
        }
        assertThat(visible)
                .as("a raise on every read would pass the refusal and break isolation's positive half")
                .contains(sentinel);
    }

    @Test
    @DisplayName("An absent tenant raises on write — a tenant-less context cannot write a row owned by the empty tenant")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void writeFromATenantlessContextIsRefused() {
        String refused = "SENTINEL-NO-TENANT-" + System.nanoTime();

        assertThatThrownBy(() -> {
            try (PersistenceConnection conn = engine.openConnection(ImmutableStorageContext.GLOBAL)) {
                writeSentinelRow(conn, refused);
            }
        })
                .as("a write check that only compares the owner with the published key admits a row owned "
                        + "by the empty tenant, which every later tenant-less request reads back; the "
                        + "policy must raise instead (ADR-012 §4c.3)")
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(refusal -> assertThat(((PersistenceProviderException) refusal).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_PERS_5003));

        // The direction control: the same write under a tenant is admitted.
        String admitted = "SENTINEL-TENANT-WRITE-" + System.nanoTime();
        List<String> visible = new ArrayList<>();
        try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyA()))) {
            writeSentinelRow(conn, admitted);
            visible.addAll(readSentinelRows(conn));
        }
        assertThat(visible)
                .as("the tenant's write was admitted, so the refusal above was for the absent tenant")
                .contains(admitted)
                .doesNotContain(refused);
    }
}
