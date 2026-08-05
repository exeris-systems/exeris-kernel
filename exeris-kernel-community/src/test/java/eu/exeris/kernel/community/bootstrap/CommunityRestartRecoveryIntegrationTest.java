/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.flow.JdbcFlowSnapshotStore;
import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operational-continuity IT (v0.9 Sprint 7, deliverable #2): a parked saga checkpoint must survive a
 * <em>real</em> kernel restart. Kernel instance A parks a {@link FlowState#PARKED} snapshot and then
 * <strong>fully closes its persistence engine</strong> (pool shut down — a genuine stop, not the
 * same-engine reopen trick the per-store TCK uses); instance B boots a fresh engine + pool against the
 * <strong>same</strong> Postgres database and must recover the snapshot with its checkpoint intact, so
 * the resumed flow re-enters at the same step. Tagged {@code continuity} → runs in
 * {@code recovery-continuity-gate}, not the fast unit pass.
 */
@Tag("continuity")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community restart-recovery: parked saga snapshot survives a real engine restart")
class CommunityRestartRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    @Timeout(120)
    void parkedSagaSnapshotSurvivesRealEngineRestart() {
        UUID id = UUID.randomUUID();
        int checkpointStep = 4;

        // ── Kernel instance A: park a saga, then fully stop the engine (real restart boundary) ──
        PersistenceEngine engineA = newEngine();
        try {
            FlowSnapshotStore storeA = new JdbcFlowSnapshotStore(engineA, "kernel-A");
            storeA.save(parkedSnapshot(id, checkpointStep));
        } finally {
            engineA.close();
        }

        // ── Kernel instance B: fresh engine + pool against the SAME database ──
        PersistenceEngine engineB = newEngine();
        try {
            FlowSnapshotStore storeB = new JdbcFlowSnapshotStore(engineB, "kernel-B");

            assertThat(storeB.exists(id.getMostSignificantBits(), id.getLeastSignificantBits()))
                    .as("parked snapshot outlives a full engine restart").isTrue();

            Optional<FlowSnapshot> recovered =
                    storeB.load(id.getMostSignificantBits(), id.getLeastSignificantBits());
            assertThat(recovered).isPresent();
            assertThat(recovered.get().state()).isEqualTo(FlowState.PARKED);
            assertThat(recovered.get().currentStep())
                    .as("flow resumes from its checkpoint step, not the start").isEqualTo(checkpointStep);

            assertThat(storeB.listParked())
                    .as("recovered saga is enumerable as parked after restart")
                    .anyMatch(s -> s.instanceIdMost() == id.getMostSignificantBits()
                            && s.instanceIdLeast() == id.getLeastSignificantBits());
        } finally {
            engineB.close();
        }
    }

    private static PersistenceEngine newEngine() {
        PersistenceConfig cfg = new PersistenceConfig(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                8, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 0,
                Map.of("run.migrations", "true"));
        return new CommunityPersistenceProvider().createEngine(cfg);
    }

    private static FlowSnapshot parkedSnapshot(UUID id, int currentStep) {
        return new FlowSnapshot(
                id.getMostSignificantBits(),
                id.getLeastSignificantBits(),
                "restart-recovery-saga",
                currentStep,
                Optional.of("step-" + currentStep),
                FlowState.PARKED,
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T11:00:00Z"),
                new int[]{1, 2, 3},
                3,
                new byte[]{0x0A, 0x0B},
                FlowSnapshot.SCHEMA_VERSION_INITIAL);
    }
}
