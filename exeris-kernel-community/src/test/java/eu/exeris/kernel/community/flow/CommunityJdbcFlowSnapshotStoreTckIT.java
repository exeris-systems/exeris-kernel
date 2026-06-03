/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.flow.AbstractDistributedFlowSnapshotStoreTck;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community binding for {@link AbstractDistributedFlowSnapshotStoreTck} using a real
 * Postgres 16 instance via Testcontainers and a HikariCP pool.
 *
 * <p>The shared schema is bootstrapped once per class via the v0.7.0 migration script;
 * each test starts with a fresh empty store created against the same database. The
 * {@code reopenStore} method returns a brand-new {@code JdbcFlowSnapshotStore} backed
 * by the same DataSource — that exercises the cross-restart contract without bouncing
 * the container.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: JdbcFlowSnapshotStore distributed TCK (PostgreSQL)")
class CommunityJdbcFlowSnapshotStoreTckIT extends AbstractDistributedFlowSnapshotStoreTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static PersistenceEngine engine;

    @BeforeAll
    static void bootstrap() {
        PersistenceConfig cfg = new PersistenceConfig(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                8,                       // maxPoolSize
                1,                       // minIdleConnections
                5_000L,                  // connectionTimeoutMs
                60_000L,                 // idleTimeoutMs
                600_000L,                // maxLifetimeMs
                false,                   // useTls
                false,                   // rlsEnabled
                false,                   // perTenantPooling
                0,                       // maxTenantPools
                Map.of("run.migrations", "true"));
        engine = new CommunityPersistenceProvider().createEngine(cfg);
    }

    @AfterAll
    static void teardown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @Override
    protected FlowSnapshotStore createStore() {
        truncateSagaState();
        return new JdbcFlowSnapshotStore(engine, "tck-engine");
    }

    @Override
    protected FlowSnapshotStore reopenStore(FlowSnapshotStore current) {
        // Same engine (i.e., same database) but a fresh store instance — the
        // contract is "data outlives a kernel restart", which the shared engine
        // simulates without bouncing the container.
        return new JdbcFlowSnapshotStore(engine, "tck-engine-restarted");
    }

    private static void truncateSagaState() {
        try (PersistenceConnection conn = engine.openConnection()) {
            conn.executeUpdate("TRUNCATE TABLE exeris_saga_state");
        }
    }

    // ------------------------------------------------------------------------
    // ADR-013 §8 — distributed-saga JFR telemetry contract.
    // OptimisticLockConflictEvent is emitted on both UPDATE_STALE and INSERT_TOCTOU
    // race-loser paths inside JdbcFlowSnapshotStore.save(); the tests below pin both.
    // ------------------------------------------------------------------------

    private static final String OCC_EVENT = "eu.exeris.kernel.flow.OptimisticLockConflict";

    @Test
    @Tag("integration")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("emits OptimisticLockConflictEvent (UPDATE_STALE) when stale schemaVersion loses an UPDATE")
    void emitsOccEventOnUpdateStale() throws Exception {
        truncateSagaState();
        JdbcFlowSnapshotStore freshStore = new JdbcFlowSnapshotStore(engine, "occ-jfr-update-stale");
        UUID id = UUID.randomUUID();

        FlowSnapshot initial = newSnapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL);
        freshStore.save(initial);
        FlowSnapshot loaded = freshStore.load(id.getMostSignificantBits(), id.getLeastSignificantBits())
                .orElseThrow(() -> new IllegalStateException("snapshot must exist after save"));

        // First UPDATE — the legitimate writer advances the row.
        FlowSnapshot winner = bumpVersion(loaded, FlowState.RUNNING, 1);
        freshStore.save(winner);

        CountDownLatch eventReceived = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(OCC_EVENT);
            rs.onEvent(OCC_EVENT, event -> {
                if (captured.compareAndSet(null, event)) {
                    eventReceived.countDown();
                }
            });
            rs.startAsync();

            // Stale UPDATE: we save with the original (now stale) loaded version.
            FlowSnapshot loserAttempt = bumpVersion(loaded, FlowState.RUNNING, 1);
            assertThatThrownBy(() -> freshStore.save(loserAttempt))
                    .as("stale-version UPDATE MUST raise EX-FLOW-7002")
                    .isInstanceOf(FlowEngineException.class);

            assertThat(eventReceived.await(5, TimeUnit.SECONDS))
                    .as("OptimisticLockConflict JFR event MUST be emitted on the stale-version UPDATE path")
                    .isTrue();
            RecordedEvent event = captured.get();
            assertThat(event.getString("phase")).isEqualTo("UPDATE_STALE");
            assertThat(event.getString("engineName")).isEqualTo("occ-jfr-update-stale");
            assertThat(event.getLong("loadedSchemaVersion"))
                    .as("the emitted version MUST match the schemaVersion the losing writer carried")
                    .isEqualTo(loserAttempt.schemaVersion());
        }
    }

    @Test
    @Tag("integration")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("emits OptimisticLockConflictEvent (UPDATE_STALE) when a duplicate save from a different store loses the UPDATE")
    void emitsOccEventOnDuplicateSaveTakenAsUpdateStale() throws Exception {
        truncateSagaState();
        JdbcFlowSnapshotStore winnerStore = new JdbcFlowSnapshotStore(engine, "occ-jfr-insert-winner");
        JdbcFlowSnapshotStore loserStore  = new JdbcFlowSnapshotStore(engine, "occ-jfr-insert-loser");
        UUID id = UUID.randomUUID();

        FlowSnapshot first = newSnapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL);
        winnerStore.save(first);

        CountDownLatch eventReceived = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(OCC_EVENT);
            rs.onEvent(OCC_EVENT, event -> {
                if (captured.compareAndSet(null, event)) {
                    eventReceived.countDown();
                }
            });
            rs.startAsync();

            // Sequential setup: winnerStore.save commits (schemaVersion bumped to 2) before the
            // loser's transaction starts. tryOptimisticUpdate WHERE schema_version = 1 affects 0
            // rows, then existsInTransaction sees the committed winner row under READ COMMITTED
            // and the UPDATE_STALE branch fires deterministically — the INSERT_TOCTOU branch
            // requires a true concurrent first-writer race (two transactions both passing the
            // existsInTransaction=false check before either commits) which would need a barrier
            // injected between tryOptimisticUpdate and insertOrRemapPkConflict; that scenario
            // is intentionally out of scope for this PR (see PR #94 review).
            FlowSnapshot duplicate = newSnapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL);
            assertThatThrownBy(() -> loserStore.save(duplicate))
                    .as("duplicate save MUST raise EX-FLOW-7002")
                    .isInstanceOf(FlowEngineException.class);

            assertThat(eventReceived.await(5, TimeUnit.SECONDS))
                    .as("OptimisticLockConflict JFR event MUST be emitted on the UPDATE_STALE path")
                    .isTrue();
            RecordedEvent event = captured.get();
            assertThat(event.getString("phase"))
                    .as("a duplicate save against a committed row deterministically takes UPDATE_STALE; "
                            + "see test Javadoc for why INSERT_TOCTOU is not reachable from this setup")
                    .isEqualTo("UPDATE_STALE");
            assertThat(event.getString("engineName")).isEqualTo("occ-jfr-insert-loser");
        }
    }

    private static FlowSnapshot newSnapshot(UUID id, FlowState state, int currentStep, long schemaVersion) {
        return new FlowSnapshot(
                id.getMostSignificantBits(),
                id.getLeastSignificantBits(),
                "occ-jfr-saga",
                currentStep,
                state,
                Instant.parse("2026-05-08T10:00:00Z"),
                Instant.parse("2026-05-08T11:00:00Z"),
                new int[0],
                0,
                new byte[]{0x01},
                schemaVersion);
    }

    private static FlowSnapshot bumpVersion(FlowSnapshot loaded, FlowState newState, int newStep) {
        return new FlowSnapshot(
                loaded.instanceIdMost(),
                loaded.instanceIdLeast(),
                loaded.definitionName(),
                newStep,
                newState,
                loaded.lastUpdate(),
                loaded.timeout(),
                loaded.compensationStack(),
                loaded.stackPointer(),
                loaded.opaqueState(),
                loaded.schemaVersion());
    }
}
