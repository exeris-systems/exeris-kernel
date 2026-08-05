/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2 Contract: durable / distributed {@link FlowSnapshotStore} bindings (ADR-013, FLOW-106).
 *
 * <h2>Bindings</h2>
 * <p>Concrete community / enterprise tests extend this class and provide:
 * <ul>
 *   <li>{@link #createStore()} — returns a fresh, empty store backed by a real durable
 *       backend (e.g., Postgres via Testcontainers).</li>
 *   <li>{@link #reopenStore(FlowSnapshotStore)} — returns a brand-new store instance
 *       sharing the same underlying database, used to simulate a kernel restart for
 *       cross-restart recovery assertions.</li>
 * </ul>
 *
 * <h2>What this contract codifies</h2>
 * <ul>
 *   <li>save → load round-trip preserves every field, including {@code schemaVersion}.</li>
 *   <li>{@code delete} removes the entry; subsequent {@code load} is empty and
 *       {@code exists} returns {@code false}.</li>
 *   <li>{@code listParked} returns only snapshots whose state is {@link FlowState#PARKED};
 *       running, completed, or compensating snapshots are excluded.</li>
 *   <li>Cross-restart simulation: snapshots saved through one store instance survive
 *       a fresh store instance created against the same database.</li>
 *   <li>Concurrent saves under contention: when two writers race on the same instance
 *       with the same incoming {@code schemaVersion}, exactly one wins; the loser
 *       receives {@link FlowEngineException} with {@code phase=OPTIMISTIC_LOCK_CONFLICT}.</li>
 * </ul>
 *
 * @since 0.7.0
 */
@DisplayName("L2 TCK: Distributed FlowSnapshotStore — save/load/delete/listParked + OCC")
public abstract class AbstractDistributedFlowSnapshotStoreTck {

    private FlowSnapshotStore store;

    /** Returns a fresh, empty store. Implementations are responsible for cleaning state. */
    protected abstract FlowSnapshotStore createStore();

    /**
     * Returns a brand-new store instance sharing the same underlying database as
     * {@code current}. Used to simulate a kernel restart while the data persists.
     *
     * <p><b>Contract parameter.</b> The abstract base does not consume {@code current} —
     * concrete implementations such as {@code CommunityJdbcFlowSnapshotStoreTckTest} use it to
     * extract the shared {@code DataSource} (or container handle) from the previous store so the
     * "reopened" store reads from the same database. Static analyzers flag this as an unused
     * parameter; the suppression below is intentional.
     */
    @SuppressWarnings("java:S1172")
    protected abstract FlowSnapshotStore reopenStore(FlowSnapshotStore current);

    /**
     * Optional: implementations may override to release resources (close pool, etc.).
     *
     * <p><b>Contract parameter.</b> The default no-op does not consume {@code current};
     * implementations that own a connection pool, container, or any external resource use it to
     * close that resource between TCK runs. Static analyzers flag this as an unused parameter
     * on the default body — the suppression below is intentional.
     */
    @SuppressWarnings("java:S1172")
    protected void disposeStore(FlowSnapshotStore current) {
        // no-op by default; overridden in implementations that own external resources
    }

    @BeforeEach
    void setUpStore() {
        store = createStore();
    }

    @AfterEach
    void tearDownStore() {
        if (store != null) {
            disposeStore(store);
            store = null;
        }
    }

    private static FlowSnapshot snapshot(UUID id, FlowState state, int currentStep, long schemaVersion) {
        return new FlowSnapshot(
                id.getMostSignificantBits(),
                id.getLeastSignificantBits(),
                "demo-saga",
                currentStep,
                // The store must round-trip the identity, so the fixture always carries one.
                Optional.of("step-" + currentStep),
                state,
                Instant.parse("2026-05-02T10:00:00Z"),
                Instant.parse("2026-05-02T11:00:00Z"),
                new int[]{7, 9},
                2,
                new byte[]{0x01, 0x02},
                schemaVersion);
    }

    @Nested
    @DisplayName("save → load round-trip")
    class SaveLoadRoundTrip {

        @Test
        @DisplayName("First save (no row) inserts; load returns equivalent snapshot with bumped schemaVersion")
        void firstSaveInsertsRow() {
            UUID id = UUID.randomUUID();
            FlowSnapshot original = snapshot(id, FlowState.PARKED, 3, FlowSnapshot.SCHEMA_VERSION_INITIAL);

            store.save(original);

            Optional<FlowSnapshot> loaded = store.load(id.getMostSignificantBits(), id.getLeastSignificantBits());
            assertThat(loaded).isPresent();
            FlowSnapshot got = loaded.orElseThrow();
            assertThat(got.definitionName()).isEqualTo(original.definitionName());
            assertThat(got.currentStep()).isEqualTo(original.currentStep());
            assertThat(got.state()).isEqualTo(original.state());
            assertThat(got.stackPointer()).isEqualTo(original.stackPointer());
            assertThat(got.compensationStack()).containsExactly(7, 9);
            assertThat(got.opaqueState()).containsExactly(0x01, 0x02);
            assertThat(got.schemaVersion())
                    .as("ADR-013 §5: durable stores advance schema_version by one on every accepted write")
                    .isEqualTo(original.schemaVersion() + 1L);
        }

        @Test
        @DisplayName("Second save with the loaded version succeeds (UPDATE) and bumps schemaVersion again")
        void secondSaveWithLoadedVersionSucceeds() {
            UUID id = UUID.randomUUID();
            store.save(snapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL));

            FlowSnapshot afterFirst = store.load(id.getMostSignificantBits(), id.getLeastSignificantBits()).orElseThrow();

            FlowSnapshot updated = snapshot(id, FlowState.RUNNING, 5, afterFirst.schemaVersion());
            store.save(updated);

            FlowSnapshot afterSecond = store.load(id.getMostSignificantBits(), id.getLeastSignificantBits()).orElseThrow();
            assertThat(afterSecond.state()).isEqualTo(FlowState.RUNNING);
            assertThat(afterSecond.currentStep()).isEqualTo(5);
            assertThat(afterSecond.schemaVersion()).isEqualTo(afterFirst.schemaVersion() + 1L);
        }

        @Test
        @DisplayName("Stale schemaVersion on UPDATE raises EX-FLOW-7002 with phase=OPTIMISTIC_LOCK_CONFLICT")
        void staleVersionRaisesOcc() {
            UUID id = UUID.randomUUID();
            store.save(snapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL));

            FlowSnapshot loaded = store.load(id.getMostSignificantBits(), id.getLeastSignificantBits()).orElseThrow();
            // First update bumps the on-disk version.
            store.save(snapshot(id, FlowState.RUNNING, 1, loaded.schemaVersion()));

            // Reusing the original loaded version is now stale.
            FlowSnapshot staleAttempt = snapshot(id, FlowState.PARKED, 2, loaded.schemaVersion());
            assertThatThrownBy(() -> store.save(staleAttempt))
                    .isInstanceOf(FlowEngineException.class);
        }
    }

    @Nested
    @DisplayName("delete + exists semantics")
    class DeleteAndExists {

        @Test
        @DisplayName("delete removes the row; subsequent load is empty and exists is false")
        void deleteRemovesEntry() {
            UUID id = UUID.randomUUID();
            store.save(snapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL));
            assertThat(store.exists(id.getMostSignificantBits(), id.getLeastSignificantBits())).isTrue();

            store.delete(id.getMostSignificantBits(), id.getLeastSignificantBits());

            assertThat(store.load(id.getMostSignificantBits(), id.getLeastSignificantBits())).isEmpty();
            assertThat(store.exists(id.getMostSignificantBits(), id.getLeastSignificantBits())).isFalse();
        }

        @Test
        @DisplayName("delete on non-existent row is a no-op")
        void deleteOnAbsentInstanceIsNoOp() {
            UUID id = UUID.randomUUID();
            store.delete(id.getMostSignificantBits(), id.getLeastSignificantBits());
            // No exception expected.
            assertThat(store.exists(id.getMostSignificantBits(), id.getLeastSignificantBits())).isFalse();
        }
    }

    @Nested
    @DisplayName("listParked enumeration")
    class ListParkedContract {

        @Test
        @DisplayName("listParked returns only PARKED snapshots; running/completed are excluded")
        void listParkedReturnsOnlyParked() {
            UUID parkedA = UUID.randomUUID();
            UUID parkedB = UUID.randomUUID();
            UUID running = UUID.randomUUID();
            UUID completed = UUID.randomUUID();

            store.save(snapshot(parkedA, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL));
            store.save(snapshot(parkedB, FlowState.PARKED, 1, FlowSnapshot.SCHEMA_VERSION_INITIAL));
            store.save(snapshot(running, FlowState.RUNNING, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL));
            store.save(snapshot(completed, FlowState.COMPLETED, 9, FlowSnapshot.SCHEMA_VERSION_INITIAL));

            List<FlowSnapshot> parked = store.listParked();

            assertThat(parked).hasSize(2);
            assertThat(parked).allSatisfy(s -> assertThat(s.state()).isEqualTo(FlowState.PARKED));
            assertThat(parked).extracting(FlowSnapshot::instanceIdMost)
                    .containsExactlyInAnyOrder(parkedA.getMostSignificantBits(), parkedB.getMostSignificantBits());
        }

        @Test
        @DisplayName("listParked on an empty store returns an empty list")
        void emptyStoreReturnsEmpty() {
            assertThat(store.listParked()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cross-restart recovery")
    class CrossRestartRecovery {

        @Test
        @DisplayName("Snapshot saved through store-A is visible through reopened store-B")
        void persistedSnapshotSurvivesStoreReopen() {
            UUID id = UUID.randomUUID();
            store.save(snapshot(id, FlowState.PARKED, 4, FlowSnapshot.SCHEMA_VERSION_INITIAL));

            FlowSnapshotStore reopened = reopenStore(store);
            try {
                Optional<FlowSnapshot> loaded = reopened.load(
                        id.getMostSignificantBits(), id.getLeastSignificantBits());
                assertThat(loaded).isPresent();
                assertThat(loaded.orElseThrow().currentStep()).isEqualTo(4);
                assertThat(reopened.listParked()).hasSize(1);
            } finally {
                disposeStore(reopened);
            }
        }
    }

    @Nested
    @DisplayName("Concurrent save under contention")
    class ConcurrentSaveContention {

        @Test
        @DisplayName("UPDATE race: two writers, same loaded version — one wins, the other observes OCC conflict")
        void concurrentSaveOnSameInstanceResolvesViaCas() throws Exception {
            UUID id = UUID.randomUUID();
            store.save(snapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL));
            FlowSnapshot loaded = store.load(id.getMostSignificantBits(), id.getLeastSignificantBits()).orElseThrow();

            RaceOutcome outcome = raceTwoSavers(
                    () -> store.save(snapshot(id, FlowState.RUNNING, 1, loaded.schemaVersion())));

            assertThat(outcome.unexpected.get()).as("Unexpected error in racy writer").isNull();
            assertThat(outcome.successes.get()).as("Exactly one writer should win the CAS race").isEqualTo(1);
            assertThat(outcome.conflicts.get()).as("The losing writer must observe an OCC conflict").isEqualTo(1);

            FlowSnapshot finalState = store.load(id.getMostSignificantBits(), id.getLeastSignificantBits()).orElseThrow();
            assertThat(finalState.state()).isEqualTo(FlowState.RUNNING);
            assertThat(finalState.schemaVersion()).isEqualTo(loaded.schemaVersion() + 1L);
        }

        @Test
        @DisplayName("INSERT race: two first-writers for the same new instance — one wins, the other observes OCC conflict (TOCTOU contract)")
        void concurrentFirstSaveOnNewInstanceResolvesViaPkRemap() throws Exception {
            UUID id = UUID.randomUUID();
            // No pre-existing row — both writers will hit the no-row branch inside save(),
            // both pass the existsInTransaction probe, and then race on INSERT. The loser
            // gets a primary-key integrity violation from the database which the durable
            // store contract requires to be remapped to phase=OPTIMISTIC_LOCK_CONFLICT
            // (ADR-013 §5) — without that remap the loser would surface an opaque SQL
            // error and break the OCC failure-mode promise.

            RaceOutcome outcome = raceTwoSavers(
                    () -> store.save(snapshot(id, FlowState.PARKED, 0, FlowSnapshot.SCHEMA_VERSION_INITIAL)));

            assertThat(outcome.unexpected.get())
                    .as("Concurrent INSERT loser must surface as FlowEngineException, not a raw SQL error")
                    .isNull();
            assertThat(outcome.successes.get()).as("Exactly one INSERT must succeed").isEqualTo(1);
            assertThat(outcome.conflicts.get())
                    .as("The losing INSERT must surface as OPTIMISTIC_LOCK_CONFLICT, not as an opaque SQL error")
                    .isEqualTo(1);

            // Exactly one row persisted; idempotency restored on the next attempt with a fresh load.
            FlowSnapshot persisted = store.load(id.getMostSignificantBits(), id.getLeastSignificantBits()).orElseThrow();
            assertThat(persisted.schemaVersion())
                    .as("First successful save advances version by exactly one (ADR-013 §5)")
                    .isEqualTo(FlowSnapshot.SCHEMA_VERSION_INITIAL + 1L);
        }

        private RaceOutcome raceTwoSavers(Runnable saveAction) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            RaceOutcome outcome = new RaceOutcome();

            Runnable writer = () -> {
                try {
                    start.await();
                    saveAction.run();
                    outcome.successes.incrementAndGet();
                } catch (FlowEngineException _) {
                    outcome.conflicts.incrementAndGet();
                } catch (Throwable t) { //NOPMD AvoidCatchingThrowable — racy test path captures any error
                    outcome.unexpected.set(t);
                }
            };

            Thread t1 = Thread.ofVirtual().unstarted(writer);
            Thread t2 = Thread.ofVirtual().unstarted(writer);
            t1.start();
            t2.start();
            start.countDown();
            t1.join(TimeUnit.SECONDS.toMillis(10));
            t2.join(TimeUnit.SECONDS.toMillis(10));
            return outcome;
        }

        private final class RaceOutcome {
            final AtomicInteger successes = new AtomicInteger();
            final AtomicInteger conflicts = new AtomicInteger();
            final AtomicReference<Throwable> unexpected = new AtomicReference<>();
        }
    }
}
