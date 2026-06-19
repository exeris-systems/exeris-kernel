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
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLOW: regression for the {@code persistSnapshot} lifecycle write-fence
 * ({@link CoreFlowRuntime} {@code !isActiveLifecycle(instance)}).
 *
 * <h2>What this guards</h2>
 * <p>In the multi-runtime restart model two {@link CoreFlowEngine} instances share one
 * {@link FlowSnapshotStore}. An abandoned generation-1 worker — still inside its step action
 * past {@code close()}'s bounded interrupt-and-join — must never land a {@code save(PARKED)}
 * AFTER a second engine has driven the same flow key to COMPLETED and reclaimed
 * ({@code complete()} -> {@code deleteSnapshot} -> {@code snapshotStore.delete}) the row.
 * Resurrecting that row breaks the unbounded-mode "deleted on complete()" invariant
 * (docs/subsystems/flow.md).
 *
 * <p>The single-runtime start -> close -> start shape does NOT reproduce the bug: the stale
 * save is blocked upstream by {@code finalizeStep}'s {@code isCurrentLifecycle} re-check and
 * the {@code runInstance} loop guard, because a restart on the SAME runtime bumps that
 * runtime's own generation counter. The abandoned worker here belongs to a DIFFERENT,
 * now-closed runtime whose own generation is unchanged — so only the {@code started && !closed}
 * half of the fence catches it. Hence the two-engine / shared-store model below.
 *
 * <h2>Counter semantics</h2>
 * <p>{@code savesAfterReclaim} counts saves that land after engine-2 reclaimed the key.
 * Fence present ({@code !isActiveLifecycle}) -> 0 and the row stays absent. With the old
 * {@code isStaleLifecycle} predicate -> 1 and the row is resurrected.
 *
 * @since 0.9.0
 */
@DisplayName("Core: persistSnapshot stale-write fence (two-engine shared-store regression)")
final class CoreFlowRuntimeStaleWriteFenceTest {

    private static final String DEF_NAME = "stale-write-fence-saga";

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("abandoned gen-1 worker cannot resurrect a row reclaimed by gen-2 (savesAfterReclaim == 0)")
    void abandonedWorkerCannotResurrectReclaimedRow() {
        CountingSnapshotStore store = new CountingSnapshotStore();

        // The shared flow key both engines operate on.
        FlowContext ctx = context("resurrection-target", DEF_NAME);

        // Gen-1 worker coordination: it announces it is inside the step, then blocks on a
        // release flag (interrupt-immune park loop) and only then returns PARK -> persistSnapshot.
        CountDownLatch gen1InStep = new CountDownLatch(1);
        AtomicBoolean releaseGen1 = new AtomicBoolean(false);

        FlowStepAction blockingPark = _ -> {
            gen1InStep.countDown();
            // Survive close()'s interrupt+5s join: park-loop ignores interruption so the
            // worker is genuinely abandoned past the join window, then proceeds to PARK.
            while (!releaseGen1.get()) {
                LockSupport.parkNanos(1_000_000L);
            }
            return FlowOutcome.PARK;
        };

        FlowEngine engine1 = newEngine(store, "gen-1");
        engine1.start();
        FlowDefinition def1 = engine1.plans().newDefinition(DEF_NAME)
                .step("park-here", blockingPark, null)
                .build();
        FlowExecutionPlan plan1 = engine1.plans().compile(def1);

        engine1.scheduler().schedule(plan1, ctx);

        // Gen-1 worker is now pinned inside its step.
        assertThat(await(() -> gen1InStep.getCount() == 0L, 5))
                .as("gen-1 worker MUST enter its blocking step before close()")
                .isTrue();

        // Close gen-1. Its runtime is now closed; the worker survives the join (still parked).
        engine1.close();

        // Nothing persisted yet — gen-1 blocked BEFORE returning PARK.
        assertThat(store.exists(ctx.instanceIdMost(), ctx.instanceIdLeast()))
                .as("no checkpoint yet — gen-1 worker has not returned PARK")
                .isFalse();

        // Gen-2 on the SAME store drives the SAME key to COMPLETED -> reclaim (delete).
        AtomicInteger gen2Exec = new AtomicInteger();
        FlowEngine engine2 = newEngine(store, "gen-2");
        engine2.start();
        FlowDefinition def2 = engine2.plans().newDefinition(DEF_NAME)
                .step("complete-here", _ -> { gen2Exec.incrementAndGet(); return FlowOutcome.CONTINUE; }, null)
                .build();
        FlowExecutionPlan plan2 = engine2.plans().compile(def2);

        engine2.scheduler().schedule(plan2, ctx);

        assertThat(await(() -> engine2.stats().completedFlows() >= 1, 10))
                .as("gen-2 MUST drive the shared key to COMPLETED")
                .isTrue();
        assertThat(gen2Exec.get()).as("gen-2 step ran once").isEqualTo(1);
        assertThat(store.exists(ctx.instanceIdMost(), ctx.instanceIdLeast()))
                .as("reclaim deleted the row in unbounded mode")
                .isFalse();

        // Open the reclaim fence and release the abandoned gen-1 worker. Any save it lands now
        // is a post-reclaim save and must be blocked by the lifecycle write-fence.
        store.markReclaimed();
        releaseGen1.set(true);

        // The released worker resumes from its park-loop, returns PARK, and the runtime
        // synchronously runs applyParkOutcome -> persistSnapshot in that same call stack. Wait
        // for the gen-1 worker virtual thread to terminate, then settle, so any save it would
        // land has already been attempted (and counted) regardless of whether the fence blocks it.
        assertThat(await(() -> liveGen1WorkerCount(ctx) == 0L, 5))
                .as("released gen-1 worker VT MUST terminate after PARK return")
                .isTrue();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));

        int savesAfterReclaim = store.savesAfterReclaim();
        boolean rowResurrected = store.exists(ctx.instanceIdMost(), ctx.instanceIdLeast());

        engine2.close();

        assertThat(savesAfterReclaim)
                .as("FENCE VIOLATION: abandoned gen-1 worker landed %d save(s) after gen-2 reclaim — "
                        + "resurrecting the reclaimed row. persistSnapshot MUST reject a closed runtime "
                        + "(!isActiveLifecycle).", savesAfterReclaim)
                .isZero();
        assertThat(rowResurrected)
                .as("reclaimed row MUST stay absent after the abandoned worker is released")
                .isFalse();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("sanity: a live active engine still persists a legitimate PARK (fence does not over-block)")
    void activeEngineStillPersistsPark() {
        CountingSnapshotStore store = new CountingSnapshotStore();
        FlowContext ctx = context("legit-park", DEF_NAME);

        FlowEngine engine = newEngine(store, "live");
        engine.start();
        FlowDefinition def = engine.plans().newDefinition(DEF_NAME)
                .step("park-here", _ -> FlowOutcome.PARK, null)
                .build();
        FlowExecutionPlan plan = engine.plans().compile(def);

        engine.scheduler().schedule(plan, ctx);

        boolean persisted = await(() -> store.exists(ctx.instanceIdMost(), ctx.instanceIdLeast()), 5);
        engine.close();

        assertThat(persisted)
                .as("an ACTIVE engine MUST persist a legitimate PARK — fence must not over-block")
                .isTrue();
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private static FlowEngine newEngine(FlowSnapshotStore store, String provider) {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("StaleWriteFence/" + provider);
        FlowEngineConfig config = new FlowEngineConfig(
                defaults.engineName(),
                defaults.maxConcurrentFlows(),
                defaults.timeoutDurationNanos(),
                defaults.maxSteps(),
                defaults.maxTransitions(),
                defaults.maxExecutionPlans(),
                defaults.schedulerQueueCapacity(),
                defaults.partitionName(),
                defaults.partitionBytes(),
                true,   // persistenceEnabled
                defaults.compensationEnabled());
        CoreFlowEngine engine = new CoreFlowEngine(
                config, FlowEngineCapabilities.COMMUNITY.withProvider("stale-write-fence-" + provider));
        return new StoreScopedEngine(engine, store);
    }

    private static FlowContext context(String instanceId, String definitionName) {
        UUID uuid = UUID.nameUUIDFromBytes(instanceId.getBytes(StandardCharsets.UTF_8));
        return new FlowContext() {
            @Override public long instanceIdMost()  { return uuid.getMostSignificantBits(); }
            @Override public long instanceIdLeast() { return uuid.getLeastSignificantBits(); }
            @Override public String definitionName() { return definitionName; }
            @Override public int currentStep()       { return 0; }
            @Override public FlowState state()        { return FlowState.RUNNING; }
            @Override public long timeoutNanos()      { return System.nanoTime() + 30_000_000_000L; }
        };
    }

    private static long liveGen1WorkerCount(FlowContext ctx) {
        // Both engines name their worker VT "exeris-flow-{most}-{least}" (same flow key), so this
        // count cannot distinguish gen-1 from gen-2 by name. The caller relies on gen-2 having
        // already completed (completedFlows >= 1 awaited earlier) before releasing gen-1, so a
        // non-zero count here is gen-1's still-abandoned worker, not a lingering gen-2 VT.
        String name = "exeris-flow-" + ctx.instanceIdMost() + '-' + ctx.instanceIdLeast();
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name::equals)
                .count();
    }

    private static boolean await(BooleanSupplier condition, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return condition.getAsBoolean();
    }

    /**
     * Binds the snapshot store into the engine's start scope, exactly as the saga-recovery TCK
     * binding does (ScopedStoreFlowEngine).
     */
    private record StoreScopedEngine(FlowEngine delegate, FlowSnapshotStore store) implements FlowEngine {
        @Override public eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory plans() { return delegate.plans(); }
        @Override public eu.exeris.kernel.spi.flow.FlowScheduler scheduler() { return delegate.scheduler(); }
        @Override public eu.exeris.kernel.spi.flow.FlowRegistry registry() { return delegate.registry(); }
        @Override public FlowEngineCapabilities capabilities() { return delegate.capabilities(); }
        @Override public eu.exeris.kernel.spi.flow.FlowEngineStats stats() { return delegate.stats(); }
        @Override public void start() {
            ScopedValue.where(KernelProviders.FLOW_SNAPSHOT_STORE, store).run(delegate::start);
        }
        @Override public void close() { delegate.close(); }
        @Override public void registerChoreographyMapper(
                eu.exeris.kernel.spi.flow.FlowChoreographyMapper mapper,
                java.util.Collection<String> eventTypeNames,
                eu.exeris.kernel.spi.events.EventBus bus) {
            delegate.registerChoreographyMapper(mapper, eventTypeNames, bus);
        }
    }

    /**
     * Shared in-memory store that counts {@code save} calls landing AFTER {@link #markReclaimed()}.
     */
    private static final class CountingSnapshotStore implements FlowSnapshotStore {
        private final ConcurrentMap<Key, FlowSnapshot> rows = new ConcurrentHashMap<>();
        private final AtomicBoolean reclaimed = new AtomicBoolean(false);
        private final AtomicInteger savesAfterReclaim = new AtomicInteger();

        void markReclaimed() { reclaimed.set(true); }

        int savesAfterReclaim() { return savesAfterReclaim.get(); }

        @Override
        public void save(FlowSnapshot snapshot) {
            if (reclaimed.get()) {
                savesAfterReclaim.incrementAndGet();
            }
            rows.put(new Key(snapshot.instanceIdMost(), snapshot.instanceIdLeast()), snapshot);
        }

        @Override
        public Optional<FlowSnapshot> load(long most, long least) {
            return Optional.ofNullable(rows.get(new Key(most, least)));
        }

        @Override
        public void delete(long most, long least) {
            rows.remove(new Key(most, least));
        }

        @Override
        public boolean exists(long most, long least) {
            return rows.containsKey(new Key(most, least));
        }

        private record Key(long most, long least) {}
    }
}
