/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowMigrationState;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A migrated saga must keep its optimistic-lock version aligned with the row the migration wrote.
 *
 * <p>{@code AbstractFlowDefinitionVersioningTck} covers in-flight migration thoroughly — single hop,
 * chains, refusals, transforms that throw — and every one of those cases passes against the shipped
 * bug, because the store a binding supplies is in-memory and {@code FlowSnapshot}'s contract lets such
 * a store "ignore the field entirely". The bookkeeping the migration write disturbs is therefore
 * invisible to the whole suite. What is missing is not a case but an <em>instrument</em>: a store that
 * enforces the durable contract the field exists for.
 *
 * <p>So this test supplies one — reject a save whose incoming version does not match the row, advance
 * by one on accept — and then runs the plainest migration there is. Nothing here is Core-specific
 * except where the defect lives; the same store under the TCK would fail the same way.
 */
@DisplayName("CoreFlowRuntime — a migrated saga's next checkpoint matches the row the migration wrote")
class CoreFlowMigrationVersionTest {

    private static final String DEFINITION = "migrating-saga";
    private static final String PARKED_STEP = "parked-step";
    private static final long AWAIT_SECONDS = 15L;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("the checkpoint after a migration is not rejected as an optimistic-lock conflict")
    void migratedSagaCheckpointsAgainstTheMigratedRow() {
        VersionEnforcingSnapshotStore store = new VersionEnforcingSnapshotStore();
        AtomicInteger resumed = new AtomicInteger();

        UUID id = UUID.randomUUID();
        store.seed(parkedSnapshot(id, 1));

        try (CoreFlowEngine engine = persistentEngine()) {
            ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, store).run(() -> {
                engine.start();
                register(engine, 2, resumed);
                engine.plans().registerMigration(DEFINITION, 1, parked -> parked);

                engine.scheduler().wake(parkedContext(id));
                // Two writes: the migration's, then the checkpoint the resumed step's PARK produces.
                // Waiting on the step counter instead would return BEFORE the step returns, and the
                // engine would close over the checkpoint this test exists to observe — the first
                // version of this test did exactly that and passed against the bug.
                awaitTrue(() -> store.writes() >= 2);
            });
        }

        assertThat(resumed.get())
                .as("the saga must reach v2's step at all — a refused migration would leave this at 0 "
                    + "and make the version assertion below vacuous")
                .isEqualTo(1);
        assertThat(store.conflicts())
                .as("the migration write advanced the row, but the instance was seeded from the "
                    + "migrated snapshot — which carries the version the row held BEFORE that write. "
                    + "Its first checkpoint then arrived one behind, and a durable store rejects that "
                    + "as EX-FLOW-7002 / OPTIMISTIC_LOCK_CONFLICT")
                .isZero();
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    /**
     * The durable half of {@code FlowSnapshot}'s optimistic-concurrency contract: advance on every
     * accepted write, reject an incoming version that does not match the row. Conflicts are counted
     * rather than thrown out of the worker thread, so the assertion reads the defect directly instead
     * of inferring it from a saga that quietly stopped.
     */
    private static final class VersionEnforcingSnapshotStore implements FlowSnapshotStore {

        private final Map<UUID, FlowSnapshot> rows = new ConcurrentHashMap<>();
        private final AtomicInteger conflicts = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();

        private void seed(FlowSnapshot snapshot) {
            rows.put(keyOf(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
        }

        private int conflicts() {
            return conflicts.get();
        }

        /** Attempts, conflicts included — what the wait below counts, so a rejected write still ends it. */
        private int writes() {
            return writes.get();
        }

        @Override
        public void save(FlowSnapshot snapshot) {
            UUID key = keyOf(snapshot.instanceIdMost(), snapshot.instanceIdLeast());
            writes.incrementAndGet();
            rows.compute(key, (_, current) -> {
                if (current != null && current.schemaVersion() != snapshot.schemaVersion()) {
                    conflicts.incrementAndGet();
                    return current;
                }
                return withSchemaVersion(snapshot, snapshot.schemaVersion() + 1);
            });
        }

        @Override
        public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
            return Optional.ofNullable(rows.get(keyOf(instanceIdMost, instanceIdLeast)));
        }

        @Override
        public void delete(long instanceIdMost, long instanceIdLeast) {
            rows.remove(keyOf(instanceIdMost, instanceIdLeast));
        }

        @Override
        public boolean exists(long instanceIdMost, long instanceIdLeast) {
            return rows.containsKey(keyOf(instanceIdMost, instanceIdLeast));
        }

        private static UUID keyOf(long most, long least) {
            return new UUID(most, least);
        }

        private static FlowSnapshot withSchemaVersion(FlowSnapshot from, long schemaVersion) {
            return new FlowSnapshot(
                    from.instanceIdMost(), from.instanceIdLeast(),
                    from.definitionName(), from.definitionVersion(),
                    from.currentStep(), from.currentStepName(),
                    from.state(), from.lastUpdate(), from.timeout(),
                    from.compensationStack(), from.compensationStepNames(), from.stackPointer(),
                    from.opaqueState(), schemaVersion);
        }
    }

    private static CoreFlowEngine persistentEngine() {
        FlowEngineConfig d = FlowEngineConfig.defaults("CoreFlowMigrationVersionTest");
        return new CoreFlowEngine(
                new FlowEngineConfig(
                        d.engineName(), d.maxConcurrentFlows(), d.timeoutDurationNanos(), d.maxSteps(),
                        d.maxTransitions(), d.maxExecutionPlans(), d.schedulerQueueCapacity(),
                        d.partitionName(), d.partitionBytes(), true, d.compensationEnabled()),
                FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-migration-test"));
    }

    /**
     * Two steps: a saga parked AT step 0 resumes at step 0+1, so a single-step definition has nothing
     * to run on wake and a successful resume is indistinguishable from a refusal.
     */
    private static void register(CoreFlowEngine engine, int version, AtomicInteger resumed) {
        FlowDefinition base = engine.plans().newDefinition(DEFINITION)
                .step(PARKED_STEP, _ -> FlowOutcome.PARK, null)
                // PARK, not COMPLETE. A completing saga has its row DELETED, so it never writes the
                // checkpoint whose version is the subject here — the defect would sail through.
                .step("resumed-step", _ -> {
                    resumed.incrementAndGet();
                    return FlowOutcome.PARK;
                }, null)
                .transition(0, 1)
                .build();
        engine.plans().compile(new FlowDefinition(
                base.name(), version, base.steps(), base.timeoutDurationNanos(), base.maxRetries()));
    }

    private static FlowSnapshot parkedSnapshot(UUID id, int definitionVersion) {
        return new FlowSnapshot(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                DEFINITION, definitionVersion,
                0, Optional.of(PARKED_STEP),
                FlowState.PARKED, Instant.now(), Instant.now().plusSeconds(60L),
                new int[0], new String[0], 0,
                new byte[0], FlowSnapshot.SCHEMA_VERSION_INITIAL);
    }

    private static FlowContext parkedContext(UUID id) {
        return new FlowContext() {
            @Override public long instanceIdMost() {
                return id.getMostSignificantBits();
            }

            @Override public long instanceIdLeast() {
                return id.getLeastSignificantBits();
            }

            @Override public String definitionName() {
                return DEFINITION;
            }

            @Override public int currentStep() {
                return 0;
            }

            @Override public FlowState state() {
                return FlowState.PARKED;
            }

            @Override public long timeoutNanos() {
                return System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
            }
        };
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new FlowEngineException("condition not met within " + AWAIT_SECONDS + "s");
    }
}
