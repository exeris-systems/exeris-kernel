/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.context.KernelProviders;
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
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <h2>VT Pinning check</h2>
 * <p>The JDBC driver MUST NOT pin the carrier thread during the isolation query.
 * This is verified via {@code @Timeout} — if the VT pins and blocks, the test times out.
 *
 * @since 0.5.0
 */
@DisplayName("Persistence Isolation Leak TCK")
public abstract class PersistenceIsolationLeakTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates a fully bootstrapped {@link PersistenceEngine} with an in-memory test schema. */
    protected abstract PersistenceEngine createEngine();

    /**
     * Returns the isolation key string for "Tenant A".
     * Must be distinct from {@link #isolationKeyB()}.
     * Default: {@code "tenant-alpha"}.
     */
    protected String isolationKeyA() { return "tenant-alpha"; }

    /**
     * Returns the isolation key string for "Tenant B".
     * Must be distinct from {@link #isolationKeyA()}.
     * Default: {@code "tenant-beta"}.
     */
    protected String isolationKeyB() { return "tenant-beta"; }

    /**
     * The SQL to write a sentinel row bound to the given isolation key.
     * The implementation must ensure each key's data is physically isolated
     * (e.g., via RLS, schema, or separate DataSource).
     *
     * <p>Example for RLS: {@code "INSERT INTO sentinel(value) VALUES (?)"} where
     * the RLS policy filters by the session-local {@code exeris.tenant_id}.
     */
    protected abstract void writeSentinelRow(PersistenceConnection conn, String value);

    /**
     * Query sentinel rows visible under the current connection.
     * Returns all visible sentinel values.
     */
    protected abstract List<String> readSentinelRows(PersistenceConnection conn);

    // =========================================================================
    // Fixtures
    // =========================================================================

    private PersistenceEngine engine;

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
        String sentinelB = "SENTINEL-BETA-"  + System.nanoTime();

        // Write Tenant A's sentinel under Tenant A's context
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyA())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyA()))) {
                writeSentinelRow(conn, sentinelA);
            }
        });

        // Write Tenant B's sentinel under Tenant B's context
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, contextFor(isolationKeyB())).run(() -> {
            try (PersistenceConnection conn = engine.openConnection(contextFor(isolationKeyB()))) {
                writeSentinelRow(conn, sentinelB);
            }
        });

        // Read under Tenant A — must see A, must NOT see B
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
        // Run Tenant A and Tenant B queries simultaneously in a StructuredTaskScope.
        // Each VT inherits its own ScopedValue — no cross-contamination.

        String sentinelA = "VT-SENTINEL-A-" + System.nanoTime();
        String sentinelB = "VT-SENTINEL-B-" + System.nanoTime();

        // Pre-write sentinels sequentially
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

        // Fork two VTs with different ScopedValue bindings — must not bleed
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {

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
                    "(banned ThreadLocal-style cross-contamination).")
                .doesNotContain(sentinelB);

        assertThat(seenByB)
                .as("Concurrent VT: Tenant B's context MUST isolate its query.")
                .doesNotContain(sentinelA);
    }
}
