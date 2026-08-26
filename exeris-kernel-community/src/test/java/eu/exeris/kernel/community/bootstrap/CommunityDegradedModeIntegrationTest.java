/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor.KernelState;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor.SubsystemState;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operational-continuity IT (v0.9 Sprint 7, deliverable #3): a real dependency failure — the Postgres
 * container is stopped mid-run — must drive the required {@code persistence} subsystem to
 * {@link SubsystemState#DEGRADED} via {@link CommunitySubsystemHealthWatcher}, dropping readiness
 * (so the load balancer drains the instance — the ADR-012 deterministic deny at the routing layer)
 * while liveness stays UP (the process is not killed, so it can recover).
 *
 * <p>Community impl-level (the watcher is Community-only; no SPI surface), tagged {@code continuity}
 * so it runs in the {@code recovery-continuity-gate} CI job, not the fast unit pass.
 */
@Tag("continuity")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community degraded-mode: Postgres loss drives readiness DEGRADED, liveness stays UP")
class CommunityDegradedModeIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    @Timeout(120)
    void databaseLossDrivesReadinessDegraded() {
        PersistenceConfig cfg = new PersistenceConfig(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                8, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 0,
                Map.of("run.migrations", "true"));
        PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(cfg);

        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("persistence", true);
        monitor.markSubsystemState("persistence", SubsystemState.RUNNING);
        monitor.markKernelState(KernelState.INITIALIZED);
        monitor.markKernelState(KernelState.STARTED);

        CommunitySubsystemHealthWatcher watcher =
                new CommunitySubsystemHealthWatcher(monitor, TimeUnit.MILLISECONDS.toNanos(200));
        // Connectivity signal: healthCheckDetailed() runs conn.isValid(2) and fails when the DB is gone.
        watcher.register("persistence", () -> engine.healthCheckDetailed().healthy());

        watcher.start();
        try {
            awaitSubsystemState(monitor, SubsystemState.RUNNING);
            assertThat(monitor.readiness().status()).isEqualTo("READY");
            assertThat(monitor.readiness().healthy()).isTrue();

            POSTGRES.stop();

            awaitSubsystemState(monitor, SubsystemState.DEGRADED);
            assertThat(monitor.readiness().status()).isEqualTo("DEGRADED");
            assertThat(monitor.readiness().healthy()).as("readiness drains on DB loss").isFalse();
            assertThat(monitor.liveness().healthy()).as("process stays alive while degraded").isTrue();
        } finally {
            watcher.stop();
            engine.close();
        }
    }

    private static void awaitSubsystemState(KernelHealthMonitor monitor, SubsystemState expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (monitor.stateOf("persistence") != expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(monitor.stateOf("persistence")).as("persistence reaches %s within deadline", expected)
                .isEqualTo(expected);
    }
}
