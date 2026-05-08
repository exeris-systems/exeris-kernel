/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.exeris.kernel.community.flow.CommunityFlowProvider;
import eu.exeris.kernel.community.flow.JdbcFlowSnapshotStore;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-engine choreography integration test (Sprint 6c — DIST-302 closure).
 *
 * <h2>What this test proves</h2>
 * <p>Two distinct {@link FlowEngine} instances ("Service A" and "Service B") share a single
 * {@link JdbcFlowSnapshotStore} backed by the same Postgres database, plus a single Kafka
 * broker for choreography events (each service uses its own consumer group). Service A
 * schedules a saga that {@link FlowOutcome#PARK PARKs} on its first step, persisting a
 * snapshot row. Service A's {@link EventEngine} is then closed so it cannot consume the
 * subsequent wake event. Service B publishes a wake event over Kafka; B's
 * {@code FlowChoreographyBridge} receives it, finds nothing in B's in-memory parked-instance
 * index, falls back to the shared snapshot store, restores the saga, and wakes it locally —
 * the saga advances to its second step and {@link FlowOutcome#COMPLETE COMPLETEs} on B.
 *
 * <p>This is the end-to-end recovery path described in ADR-013 §6: durable saga state
 * survives a kernel that did not originate the saga, and choreography events delivered to
 * any node are sufficient to drive the saga forward on whichever node has capacity.
 *
 * <h2>Out of scope (deferred)</h2>
 * <ul>
 *   <li>Mid-saga kill where Service A dies <em>during</em> step execution and Service B
 *       resumes from the persisted snapshot — covered already at the snapshot-store level by
 *       {@code AbstractDistributedFlowSnapshotStoreTck} (cross-restart recovery section).</li>
 *   <li>OCC conflict (two engines wake the same saga concurrently and one observes
 *       {@code EX-FLOW-7002}) — to be added once the snapshot fast-path is observable from
 *       outside the engine. Not needed for the smoke closure of DIST-302.</li>
 *   <li>Kafka rebalance during in-flight choreography — separate test track.</li>
 * </ul>
 *
 * @since 0.7.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community :: Cross-engine choreography (Postgres + Kafka)")
class CommunityCrossEngineChoreographyIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private static HikariDataSource pool;

    @BeforeAll
    static void bootstrap() throws SQLException {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(8);
        pool = new HikariDataSource(cfg);

        String migrationSql = readMigrationResource("db/migration/V0.7.0__create_saga_state.sql");
        try (Connection conn = pool.getConnection()) {
            for (String stmt : splitStatements(migrationSql)) {
                try (Statement s = conn.createStatement()) {
                    s.execute(stmt);
                }
            }
        }
    }

    @AfterAll
    static void teardown() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    @AfterEach
    void truncate() throws SQLException {
        try (Connection conn = pool.getConnection();
             Statement s = conn.createStatement()) {
            s.execute("TRUNCATE TABLE exeris_saga_state");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Saga parked on Service A is woken on Service B via shared snapshot store")
    void crossEngineWakeViaSnapshotFallback() throws Exception {
        FlowSnapshotStore sharedStore = new JdbcFlowSnapshotStore(pool, "cross-engine-IT");
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        java.lang.ScopedValue
                .where(KernelProviders.FLOW_SNAPSHOT_STORE, sharedStore)
                .run(() -> {
                    try {
                        runScenario();
                    } catch (RuntimeException | InterruptedException ex) {
                        outcome.set(ex);
                    }
                });
        if (outcome.get() != null) {
            throw new AssertionError("Cross-engine scenario failed", outcome.get());
        }
    }

    @SuppressWarnings("PMD.CloseResource") // engines closed in finally; AssertJ shadows the unused warning
    private static void runScenario() throws InterruptedException {
        UUID instanceId = UUID.randomUUID();
        String wakeEventType = "CrossEngineWake-" + instanceId;
        String defName = "cross-engine-saga-" + instanceId;
        int wakeOrdinal = 1;

        FlowEngine engineA = createPersistentFlowEngine("ServiceA");
        EventEngine eventsA = createKafkaEngine("group-A");
        FlowEngine engineB = createPersistentFlowEngine("ServiceB");
        EventEngine eventsB = createKafkaEngine("group-B");

        engineA.start();
        eventsA.start();
        engineB.start();
        eventsB.start();

        try {
            // Compile the SAME plan definition (by name) on both engines. Service B's plan
            // resolution after the snapshot fallback looks up the definition name in B's
            // local plan catalog, so the catalog must already contain it before the wake.
            AtomicBoolean parkedFlagA = new AtomicBoolean(false);
            FlowExecutionPlan planA = parkingPlan(engineA, defName, parkedFlagA);

            AtomicBoolean wakeRanOnB   = new AtomicBoolean(false);
            FlowExecutionPlan unusedPlanB = parkingPlanWithProbe(engineB, defName, wakeRanOnB);
            assertThat(unusedPlanB).isNotNull();

            // Service A parks the saga.
            engineA.scheduler().schedule(planA, simpleContext(instanceId, defName));
            awaitTrue(15_000L, () -> engineA.stats().parkedFlows() >= 1L);
            assertThat(parkedFlagA).as("park step must have run on Service A").isTrue();
            assertThat(engineA.stats().parkedFlows())
                    .as("Service A must register the parked saga before the wake event is published")
                    .isGreaterThanOrEqualTo(1L);

            // Stop Service A's event consumption so it cannot also process the wake event.
            // Service A's FlowEngine remains alive; the test only asserts on Service B.
            eventsA.close();

            // Service B subscribes to the wake event; its mapper resolves to a Wake decision
            // for the saga's instance UUID. Then the wake event is published over Kafka.
            eventsB.registry().register(EventTypeSpec.of(wakeEventType, wakeOrdinal));
            engineB.registerChoreographyMapper(
                    descriptor -> new ChoreographyDecision.Wake(
                            instanceId.getMostSignificantBits(),
                            instanceId.getLeastSignificantBits()),
                    List.of(wakeEventType),
                    eventsB.bus());

            EventBus busB = eventsB.bus();
            busB.publish(descriptorFor(wakeOrdinal), EventPayload.empty());

            // The wake event drives Service B to fall back to the shared snapshot store
            // (B's in-memory parkedInstances does not contain the saga) and resume from
            // step 0. The second step COMPLETEs and the lifecycle counter increments.
            awaitTrue(60_000L, () -> engineB.stats().completedFlows() >= 1L);
            assertThat(engineB.stats().completedFlows())
                    .as("Service B MUST complete the saga by waking it via the snapshot fallback")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(wakeRanOnB)
                    .as("the second step's body MUST execute on Service B (proving B is the executor "
                            + "after the snapshot-fallback wake — not just a passive bookkeeping advance)")
                    .isTrue();
        } finally {
            // eventsA is already closed above on the happy path; close() is idempotent.
            eventsA.close();
            eventsB.close();
            engineA.close();
            engineB.close();
        }
    }

    private static FlowEngine createPersistentFlowEngine(String engineName) {
        FlowEngineConfig base = FlowEngineConfig.defaults(engineName);
        FlowEngineConfig persistent = new FlowEngineConfig(
                base.engineName(),
                base.maxConcurrentFlows(),
                base.timeoutDurationNanos(),
                base.maxSteps(),
                base.maxTransitions(),
                base.maxExecutionPlans(),
                base.schedulerQueueCapacity(),
                base.partitionName(),
                base.partitionBytes(),
                true,                              // persistenceEnabled — wires JdbcFlowSnapshotStore
                base.compensationEnabled(),
                base.terminalCatalogMaxSize(),
                base.defaultSagaTimeoutShortNanos(),
                base.defaultSagaTimeoutLongNanos());
        return new CommunityFlowProvider().createEngine(persistent);
    }

    private static EventEngine createKafkaEngine(String groupSuffix) {
        EventEngineConfig spi = EventEngineConfig.communityDefaults();
        KafkaEventConfig kafka = KafkaEventConfig.defaults(
                KAFKA.getBootstrapServers(),
                "exeris-cross-engine-" + groupSuffix + "-" + UUID.randomUUID());
        return KafkaEventProvider.create(spi, kafka);
    }

    private static FlowExecutionPlan parkingPlan(FlowEngine engine, String defName, AtomicBoolean parkedFlag) {
        return engine.plans().compile(
                engine.plans().newDefinition(defName)
                      .step("wait-step", ctx -> {
                          parkedFlag.set(true);
                          return FlowOutcome.PARK;
                      }, null)
                      .step("done-step", ctx -> FlowOutcome.COMPLETE, null)
                      .transition(0, 1)
                      .build());
    }

    private static FlowExecutionPlan parkingPlanWithProbe(
            FlowEngine engine, String defName, AtomicBoolean wakeRanFlag) {
        return engine.plans().compile(
                engine.plans().newDefinition(defName)
                      .step("wait-step", ctx -> FlowOutcome.PARK, null)
                      .step("done-step", ctx -> {
                          wakeRanFlag.set(true);
                          return FlowOutcome.COMPLETE;
                      }, null)
                      .transition(0, 1)
                      .build());
    }

    private static eu.exeris.kernel.spi.flow.model.FlowContext simpleContext(UUID id, String defName) {
        return new HeapFlowContext(
                id.getMostSignificantBits(),
                id.getLeastSignificantBits(),
                defName);
    }

    private record HeapFlowContext(long instanceIdMost, long instanceIdLeast, String definitionName)
            implements eu.exeris.kernel.spi.flow.model.FlowContext {
        @Override public int currentStep() { return 0; }
        @Override public eu.exeris.kernel.spi.flow.model.FlowState state() {
            return eu.exeris.kernel.spi.flow.model.FlowState.CREATED;
        }
        @Override public long timeoutNanos() { return Long.MAX_VALUE; }
    }

    private static EventDescriptor descriptorFor(int ordinal) {
        return EventDescriptor.of(0L, 0L, 0L, 0L, ordinal, 0, System.currentTimeMillis());
    }

    private static void awaitTrue(long deadlineMs, java.util.function.BooleanSupplier predicate)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + deadlineMs;
        while (!predicate.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    private static String readMigrationResource(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing SQL migration resource: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioEx) {
            throw new UncheckedIOException("Failed to read migration: " + resourcePath, ioEx);
        }
    }

    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLineComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == ';') {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }
}
