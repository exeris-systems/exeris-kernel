/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow; // NOPMD

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.IdempotencyGuard;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowDefinitionMigration;
import eu.exeris.kernel.spi.flow.model.FlowMigrationState;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class CoreFlowRuntime { // NOPMD

    /** Blast-radius bound only: adjacency enforced at registration is what terminates the chain. */
    private static final int MAX_MIGRATION_HOPS = 32;


    private static final long NO_TIMEOUT_OVERRUN       = 0L;
    private static final int  STEP_PROCEED             = Integer.MIN_VALUE;
    private static final int  EXIT_RUN_LOOP            = -1;

    private final FlowEngineConfig config;
    private final FlowProgressPublisher progressPublisher;
    private final Scheduler scheduler = new Scheduler();
    private final ConcurrentMap<FlowKey, RuntimeFlowInstance> liveInstances = new ConcurrentHashMap<>();
    private final ConcurrentMap<FlowKey, RuntimeFlowInstance> parkedInstances = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlanKey, CoreFlowExecutionPlan> planCatalog = new ConcurrentHashMap<>();
    private final ConcurrentMap<MigrationKey, FlowDefinitionMigration> migrations = new ConcurrentHashMap<>();
    private final TerminalStateCatalog terminalStateCatalog;
    private final ParkedLookupMissCache parkedLookupMisses = new ParkedLookupMissCache();
    private final Set<Thread> runningThreads = ConcurrentHashMap.newKeySet();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicInteger activeFlows = new AtomicInteger();
    private final LongAdder parkedFlows = new LongAdder();
    private final LongAdder completedFlows = new LongAdder();
    private final LongAdder failedFlows = new LongAdder();
    private final LongAdder compensationsRun = new LongAdder();
    private final LongAdder stepExecutions = new LongAdder();
    // PERF-064: lifecycle baselines snapshotted on start()/close() instead of LongAdder.reset(),
    // which is intrinsically racy under concurrent updates. Visible per-generation count is
    // (totalSinceForever - baselineAtLifecycleTransition); baseline writes are atomic volatile
    // longs, so a stale worker that survives interruptAndJoinRunningThreads' 5s join timeout
    // cannot leave the next generation with a non-zero residual.
    private volatile long nonDurableParkedAtClose;
    private final FlowParkCheckpoint.Attempt parkAttempt = this::persistParkSnapshot;
    private volatile long parkedFlowsBaseline;
    private volatile long completedFlowsBaseline;
    private volatile long failedFlowsBaseline;
    private volatile long compensationsRunBaseline;
    private volatile long stepExecutionsBaseline;
    private final AtomicInteger queueDepth = new AtomicInteger();
    private volatile FlowSnapshotStore snapshotStore;
    private volatile IdempotencyGuard guard;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean shutdownFinalized;

    /* default */ CoreFlowRuntime(FlowEngineConfig config, FlowProgressPublisher progressPublisher) {
        this.config = Objects.requireNonNull(config, "config");
        this.progressPublisher = Objects.requireNonNull(progressPublisher, "progressPublisher");
        this.terminalStateCatalog = new TerminalStateCatalog(config.terminalCatalogMaxSize());
    }

    public FlowScheduler scheduler() {
        return scheduler;
    }

    /* default */ ConcurrentMap<PlanKey, CoreFlowExecutionPlan> planCatalog() {
        return planCatalog;
    }

    /* default */ ConcurrentMap<MigrationKey, FlowDefinitionMigration> migrations() {
        return migrations;
    }

    /* default */ void clearLookupSuppressionAfterPlanCompile() {
        parkedLookupMisses.clearAll();
    }

    public FlowEngineStats stats() {
        return new FlowEngineStats(
                activeFlows.get(),
                Math.max(0L, parkedFlows.sum() - parkedFlowsBaseline),
                completedFlows.sum() - completedFlowsBaseline,
                failedFlows.sum() - failedFlowsBaseline,
                compensationsRun.sum() - compensationsRunBaseline,
                stepExecutions.sum() - stepExecutionsBaseline,
                0,
                -1
        );
    }

    public void start() {
        if (started && !closed) {
            return;
        }
        if (config.persistenceEnabled()) {
            Optional<FlowSnapshotStore> store = KernelProviders.flowSnapshotStore();
            if (store.isEmpty()) {
                throw FlowEngineException.startupFailure(
                        config.engineName(),
                        new IllegalStateException("FlowSnapshotStore must be bound when persistence is enabled")
                );
            }
            snapshotStore = store.get();
        }
        guard = KernelProviders.idempotencyGuard().orElseGet(CoreIdempotencyGuard::new);
        lifecycleGeneration.incrementAndGet();
        resetLifecycleTotals();
        closed = false;
        shutdownFinalized = false;
        started = true;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        interruptAndJoinRunningThreads();
        shutdownFinalized = true;
        runningThreads.removeIf(thread -> !thread.isAlive());
        nonDurableParkedAtClose = FlowParkCheckpoint.countNonDurable(parkedInstances.values());
        liveInstances.clear();
        parkedInstances.clear();
        terminalStateCatalog.clear();
        parkedLookupMisses.clearAll();
        activeFlows.set(0);
        queueDepth.set(0);
        parkedFlowsBaseline = parkedFlows.sum();
    }

    @SuppressWarnings("java:S2142")
    private void interruptAndJoinRunningThreads() {
        Thread[] threads = runningThreads.toArray(Thread[]::new);
        boolean interrupted = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            long deadline = System.nanoTime() + 5_000_000_000L;
            try {
                while (thread.isAlive() && System.nanoTime() < deadline) {
                    thread.join(java.time.Duration.ofMillis(100));
                }
            } catch (InterruptedException _) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public void assertStarted() {
        ensureStarted();
    }

    private static void decrementToZeroFloor(AtomicInteger counter) {
        int current = counter.get();
        while (current > 0 && !counter.compareAndSet(current, current - 1)) {
            current = counter.get();
        }
    }

    private void decrementLifecycleCounterOnExit(AtomicInteger counter) {
        if (!closed && !shutdownFinalized) {
            counter.decrementAndGet();
            return;
        }
        decrementToZeroFloor(counter);
    }

    private void ensureStarted() {
        if (!started || closed) {
            throw engineNotStarted();
        }
    }

    private boolean isCurrentLifecycle(RuntimeFlowInstance instance) {
        return instance.lifecycleGeneration() == lifecycleGeneration.get();
    }

    private boolean isActiveLifecycle(RuntimeFlowInstance instance) {
        return started && !closed && isCurrentLifecycle(instance);
    }

    private boolean isStaleLifecycle(RuntimeFlowInstance instance) {
        return !isCurrentLifecycle(instance);
    }

    private boolean cleanupOnly(RuntimeFlowInstance instance) {
        return shutdownFinalized || isStaleLifecycle(instance);
    }

    private void resetLifecycleTotals() {
        activeFlows.set(0);
        queueDepth.set(0);
        // PERF-064: snapshot baselines instead of LongAdder.reset() — atomic per-counter,
        // race-safe against stale workers that may have survived the close() join timeout.
        parkedFlowsBaseline = parkedFlows.sum();
        completedFlowsBaseline = completedFlows.sum();
        failedFlowsBaseline = failedFlows.sum();
        compensationsRunBaseline = compensationsRun.sum();
        stepExecutionsBaseline = stepExecutions.sum();
    }

    private FlowEngineException engineNotStarted() {
        return FlowEngineException.startupFailure(
                config.engineName(),
                new IllegalStateException("Flow engine is not started"));
    }

    /** Structured, not concatenated — the reason is what lets a caller tell a race from a fault. */
    private FlowEngineException notParked(FlowKey key) {
        return FlowEngineException.notParked(
                config.engineName(), key.instanceIdMost(), key.instanceIdLeast());
    }

    private void schedule(CoreFlowExecutionPlan plan, FlowContext context) {
        ensureStarted();
        FlowKey key = FlowKey.from(context);
        parkedLookupMisses.clearMiss(key);
        if (terminalStateCatalog.isTerminal(key)) {
            return;
        }

        RuntimeFlowInstance instance = liveInstances.compute(key, (flowKey, current) -> {
            if (current != null) {
                current.attachPlan(plan);
                return current;
            }
            RuntimeFlowInstance restored = restoreFromSnapshot(flowKey, plan, null, false);
            return restored != null
                    ? restored
                    : RuntimeFlowInstance.fromContext(plan, context, lifecycleGeneration.get());
        });

        if (instance.isTerminal() || terminalStateCatalog.isTerminal(key)) {
            // FLOW-107: catalog miss with a terminal snapshot row means the in-memory entry
            // was evicted under bounded LRU. Re-cache the terminal state so subsequent
            // resubmits short-circuit on the in-memory fast path.
            if (instance.isTerminal()) {
                terminalStateCatalog.recordTerminal(key, instance.state());
            }
            liveInstances.remove(key, instance);
            return;
        }

        instance.captureEventEngine();

        int startStep = instance.beginScheduleForSchedule();
        if (startStep == RuntimeFlowInstance.PARKED_SCHEDULE_NOOP) {
            ensureParkedRegistration(instance);
            return;
        }
        if (startStep < 0) {
            return;
        }
        if (instance.state() == FlowState.PARKED) {
            ensureParkedRegistration(instance);
            instance.markNotScheduled();
            return;
        }

        if (parkedInstances.remove(key) != null) {
            parkedFlows.decrement();
        }
        launch(instance, startStep);
    }

    private void park(FlowContext context) {
        ensureStarted();
        FlowKey key = FlowKey.from(context);
        parkedLookupMisses.clearMiss(key);
        RuntimeFlowInstance instance = liveInstances.get(key);
        if (instance == null || instance.state() == FlowState.PARKED || instance.isTerminal()) {
            return;
        }
        synchronized (instance.monitor()) {
            if (instance.state() == FlowState.PARKED || instance.isTerminal()) {
                return;
            }
            instance.state(FlowState.PARKED);
            ensureParkedRegistration(instance);
            FlowParkCheckpoint.persist(instance, instance.currentStep(), parkAttempt);
        }
    }

    private void wake(FlowContext context) {
        ensureStarted();
        wake(FlowKey.from(context), context.state());
    }

    /**
     * Key-addressed wake (SPI {@code FlowScheduler.wake(long, long)}).
     *
     * <p>Resolves inside the engine, so a choreography bridge no longer probes first: the
     * two-call form could not see a live instance on its way to PARK, and paid a second
     * store read on a genuine miss. The fallback event that probe emitted still fires from
     * the one store read that remains - see {@link #resolveParkedInstance}.
     */
    private void wake(FlowKey key, FlowState requestedState) {
        parkedLookupMisses.clearMiss(key);
        if (terminalStateCatalog.isTerminal(key)) {
            return;
        }

        RuntimeFlowInstance instance = resolveParkedInstance(key, requestedState);

        if (instance.isTerminal() || terminalStateCatalog.isTerminal(key)) {
            liveInstances.remove(key, instance);
            return;
        }

        instance.captureEventEngine();

        int startStep = instance.beginScheduleAfterWake();
        if (startStep < 0) {
            return;
        }
        launch(instance, startStep);
    }

    private RuntimeFlowInstance resolveParkedInstance(FlowKey key, FlowState requestedState) {
        RuntimeFlowInstance instance = parkedInstances.remove(key);
        if (instance != null) {
            parkedFlows.decrement();
            return instance;
        }
        RuntimeFlowInstance live = liveInstances.get(key);
        if (live != null) {
            if (live.state() == FlowState.PARKED) {
                return live;
            }
            if (requestedState == FlowState.PARKED && live.isScheduled()) {
                return live;
            }
            throw notParked(key);
        }
        // In-memory miss → durable store, the one probe this path pays. ADR-013 §8 telemetry
        // contract: emit whichever way the read goes, because `restored` is the flag that
        // separates a genuine cross-engine restore from a stale wake for a key this engine
        // will never know. Emitting only on the hit would leave the miss - the case an
        // operator most needs to see - dark on the key-addressed path.
        long fallbackStartNanos = System.nanoTime();
        RuntimeFlowInstance restored = restoreParkedFromSnapshot(key, false);
        WakeOnLoadFallbackEvent.emit(config.engineName(), key.instanceIdMost(),
                key.instanceIdLeast(), restored != null, System.nanoTime() - fallbackStartNanos);
        if (restored == null) {
            throw notParked(key);
        }
        return restored;
    }

    private RuntimeFlowInstance restoreParkedFromSnapshot(FlowKey key) {
        return restoreParkedFromSnapshot(key, true);
    }

    private RuntimeFlowInstance restoreParkedFromSnapshot(FlowKey key, boolean registerParked) {
        RuntimeFlowInstance restored = restoreFromSnapshot(key, null, FlowState.PARKED, true);
        if (restored == null) {
            return null;
        }
        RuntimeFlowInstance concurrent = liveInstances.putIfAbsent(key, restored);
        RuntimeFlowInstance resolved = concurrent != null ? concurrent : restored;
        if (resolved.state() != FlowState.PARKED) {
            return null;
        }
        if (registerParked && parkedInstances.putIfAbsent(key, resolved) == null) {
            parkedFlows.increment();
        }
        return resolved;
    }

    private RuntimeFlowInstance restoreFromSnapshot(
            FlowKey key,
            CoreFlowExecutionPlan directPlan,
            FlowState requiredState,
            boolean suppressRepeatedMisses) {
        FlowSnapshot persisted = loadSnapshot(key, requiredState, suppressRepeatedMisses);
        if (persisted == null) {
            return null;
        }
        // ADR-064 A1: wake only. The resubmit path fixes the target version at the caller's plan,
        // which makes the chain's terminating condition path-dependent — it keeps refusing instead.
        FlowSnapshot migrated = directPlan == null ? migrateIfNeeded(persisted) : null;
        FlowSnapshot resumable = migrated == null ? persisted : migrated;
        CoreFlowExecutionPlan resolvedPlan = resolvePlanForSnapshot(directPlan, resumable);
        if (resolvedPlan == null) {
            if (suppressRepeatedMisses) {
                parkedLookupMisses.recordMiss(key);
            }
            return null;
        }
        parkedLookupMisses.clearMiss(key);
        boolean migrationPersisted = false;
        if (migrated != null) {
            // ADR-064 A3. Not persisting would leave the row naming a version the saga no longer
            // runs, and would make transform purity an unstated obligation by re-running the chain
            // on every wake.
            migrationPersisted = persistMigratedSnapshot(migrated);
            FlowDefinitionMigratedEvent.emit(
                    config.engineName(), migrated.definitionName(),
                    migrated.instanceIdMost(), migrated.instanceIdLeast(),
                    persisted.definitionVersion(), migrated.definitionVersion());
        }
        RuntimeFlowInstance instance =
                RuntimeFlowInstance.fromSnapshot(resolvedPlan, resumable, lifecycleGeneration.get());
        if (migrationPersisted) {
            // ADR-013 §5, as on every other save. The migrated snapshot is built from the loaded one
            // and carries ITS schemaVersion, so the instance seeds the version the row held BEFORE
            // the migration write — which the store has already advanced. Without the bump the
            // saga's first checkpoint after a migration arrives one version behind and a durable
            // store rejects it as OPTIMISTIC_LOCK_CONFLICT: migration would work only on stores
            // that ignore the field, which is exactly where every migration case is exercised.
            instance.markPersisted();
        }
        return instance;
    }

    private FlowSnapshot loadSnapshot(FlowKey key, FlowState requiredState, boolean suppressRepeatedMisses) {
        if (snapshotStore == null) {
            return null;
        }
        if (suppressRepeatedMisses && parkedLookupMisses.hasMiss(key)) {
            return null;
        }
        FlowSnapshot snapshot = snapshotStore.load(key.instanceIdMost(), key.instanceIdLeast()).orElse(null);
        if (snapshot == null || !matchesRequiredState(snapshot, requiredState)) {
            if (suppressRepeatedMisses) {
                parkedLookupMisses.recordMiss(key);
            }
            return null;
        }
        parkedLookupMisses.clearMiss(key);
        return snapshot;
    }


    private void ensureParkedRegistration(RuntimeFlowInstance instance) {
        parkedLookupMisses.clearMiss(instance.key());
        if (parkedInstances.putIfAbsent(instance.key(), instance) == null) {
            parkedFlows.increment();
        }
    }

    private boolean matchesRequiredState(FlowSnapshot persisted, FlowState requiredState) {
        return requiredState == null || persisted.state() == requiredState;
    }

    private CoreFlowExecutionPlan resolvePlanForSnapshot(CoreFlowExecutionPlan directPlan, FlowSnapshot persisted) {
        if (directPlan == null) {
            CoreFlowExecutionPlan catalogPlan = resolveVersionedPlan(persisted);
            if (catalogPlan == null) {
                return null;
            }
            validateSnapshotStepBounds(catalogPlan, persisted);
            return catalogPlan;
        }
        if (!directPlan.definitionName().equals(persisted.definitionName())) {
            throw new FlowEngineException(
                    "Plan definition mismatch: scheduled '"
                    + directPlan.definitionName()
                    + "' but snapshot belongs to '"
                    + persisted.definitionName() + "'");
        }
        validateSnapshotVersion(directPlan, persisted);
        validateSnapshotStepBounds(directPlan, persisted);
        return directPlan;
    }

    /**
     * Applies the version guard to a caller-supplied plan (ADR-064 obligation 4).
     *
     * <p>{@code schedule()} resubmits against a plan the application already holds — plausibly the
     * newest it compiled — for an instance that may be parked under an older one. Without this the
     * guarantee held only on {@code wake()}: where the two versions happen to line up at the parked
     * index, the saga resumes on the wrong definition exactly as it did before this epic, reached
     * through a different entry point rather than fixed.
     */
    private void validateSnapshotVersion(CoreFlowExecutionPlan plan, FlowSnapshot persisted) {
        if (persisted.state().isTerminal()) {
            return;
        }
        if (persisted.definitionVersion() == FlowSnapshot.VERSION_ABSENT) {
            throw FlowEngineException.schemaMismatchDefinitionVersionAbsent(
                    config.engineName(), persisted.currentStep());
        }
        if (plan.definitionVersion() != persisted.definitionVersion()) {
            throw FlowEngineException.schemaMismatchDefinitionVersionUnresolved(
                    config.engineName(), persisted.definitionVersion());
        }
    }

    /**
     * Walks adjacent registered migrations until the snapshot names a version this engine hosts.
     *
     * <p>Stops at the FIRST reachable registered version rather than running to the newest, and that
     * is forced by the data structure rather than chosen: the catalogue is keyed by
     * {@code PlanKey(name, version)} and offers point-gets only, so "newest" would need a
     * name-to-versions scan on the resume success path.
     *
     * <p>Returns {@code null} when no migration applies — including when the version is already
     * hosted, absent, or the snapshot is terminal. Null rather than the input, because the caller has
     * to know whether a migration happened in order to persist it, and answering that by comparing
     * object identity would be exactly the identity-sensitive operation carriers must avoid.
     *
     * <p>A transform that throws propagates. Application code failing here must surface rather than
     * be folded into a wake that quietly does nothing.
     */
    // Two small value keys allocated per hop. Justified rather than hoisted: both are keyed by the
    // version, which is precisely what each hop advances, so there is nothing loop-invariant to lift.
    // Cold path — a resume that needs migrating at all — and bounded by MAX_MIGRATION_HOPS.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private FlowSnapshot migrateIfNeeded(FlowSnapshot persisted) {
        if (!needsMigration(persisted)) {
            return null;
        }
        refuseRowThatCannotBeWalked(persisted);
        FlowSnapshot current = persisted;
        for (int hop = 0; hop < MAX_MIGRATION_HOPS; hop++) {
            FlowDefinitionMigration migration =
                    migrations.get(new MigrationKey(current.definitionName(), current.definitionVersion()));
            if (migration == null) {
                return null;
            }
            current = applyHop(migration, current);
            if (planCatalog.containsKey(new PlanKey(current.definitionName(), current.definitionVersion()))) {
                return current;
            }
        }
        return null;
    }

    /**
     * Whether this snapshot needs a walk and this runtime may perform one.
     *
     * <p><b>Lifecycle write-fence.</b> {@code persistSnapshot} carries the same fence, with a comment
     * explaining why: a worker on a closed or not-yet-started runtime must not re-establish a
     * non-terminal row another runtime may already have reclaimed. It is hoisted to here rather than
     * applied at the save because the alternative — migrate in memory, skip the write — resumes the
     * saga on a version its row does not name, which is exactly what A3 exists to prevent. A migration
     * that cannot be persisted must not happen at all. The per-instance generation half of that fence
     * does not apply: there is no instance yet, and the restore binds to the current generation.
     *
     * <p>Read once, before the walk, rather than again before the save. {@code started} and
     * {@code closed} are plain volatiles here as everywhere else in this class, so a {@code close()}
     * landing mid-walk is not caught — the same tolerance the surrounding code already has, over a
     * window of at most {@link #MAX_MIGRATION_HOPS} in-memory transforms.
     */
    private boolean needsMigration(FlowSnapshot persisted) {
        return started
                && !closed
                && !persisted.state().isTerminal()
                && persisted.definitionVersion() != FlowSnapshot.VERSION_ABSENT
                && !planCatalog.containsKey(
                        new PlanKey(persisted.definitionName(), persisted.definitionVersion()));
    }

    /**
     * Refuses a row that needs a walk but records no step identity, naming the reason that is true.
     *
     * <p>{@code applyHop} builds the transform's input from the parked step's name, so without this
     * the row would reach {@code orElseThrow} as a bare {@code NoSuchElementException} on a path where
     * every other refusal carries a reason.
     *
     * <p>Declining silently is not enough either, and that is the subtler half. Falling through leaves
     * the row to {@code resolveVersionedPlan}, which refuses first — before
     * {@link #validateSnapshotStepIdentity} ever runs — with {@code DEFINITION_VERSION_UNRESOLVED}.
     * That reason's documented remedy is "deploy the missing version, or register the missing
     * transform", and here a transform may well already be registered: the row is unresumable because
     * it records no step identity, which no deployment fixes. A refusal that steers an operator to the
     * wrong runbook is worse than a raw exception, because it looks actionable.
     */
    private void refuseRowThatCannotBeWalked(FlowSnapshot persisted) {
        if (persisted.currentStepName().isEmpty()) {
            emitSchemaMismatch(persisted, persisted.currentStep(), -1,
                    FlowEngineException.REASON_STEP_IDENTITY_ABSENT, null, null);
            throw FlowEngineException.schemaMismatchStepIdentityAbsent(
                    config.engineName(), persisted.currentStep());
        }
        // Second input the transform cannot be handed: FlowMigrationState requires identities for the
        // live stack, so a row without them reaches its compact constructor as a bare
        // IllegalArgumentException — the same shape of unreasoned failure the cursor half above exists
        // to prevent, on the same path, one component over. Refused here rather than left to the
        // post-transform guard because there is no transform to run.
        int live = persisted.stackPointer();
        if (live > 0 && persisted.compensationStepNames().length == 0) {
            emitSchemaMismatch(persisted, live, -1,
                    FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_ABSENT, null, null);
            throw FlowEngineException.schemaMismatchCompensationStackIdentityAbsent(
                    config.engineName(), live);
        }
    }

    /** Applies one adjacent hop, rebuilding the snapshot at {@code version + 1}. */
    private static FlowSnapshot applyHop(FlowDefinitionMigration migration, FlowSnapshot from) {
        FlowMigrationState before = new FlowMigrationState(
                from.currentStep(),
                from.currentStepName().orElseThrow(),
                from.compensationStack(),
                from.compensationStepNames(),
                from.stackPointer(),
                from.opaqueState());
        FlowMigrationState after = Objects.requireNonNull(
                migration.migrate(before), "FlowDefinitionMigration must not return null");
        return new FlowSnapshot(
                from.instanceIdMost(), from.instanceIdLeast(),
                from.definitionName(), from.definitionVersion() + 1,
                after.parkedStep(), Optional.of(after.parkedStepName()),
                from.state(), from.lastUpdate(), from.timeout(),
                after.compensationStack(), after.compensationStepNames(), after.stackPointer(),
                after.opaqueState(), from.schemaVersion());
    }

    private boolean persistMigratedSnapshot(FlowSnapshot migrated) {
        if (snapshotStore == null) {
            return false;
        }
        FlowSnapshotWriter.save(snapshotStore, migrated);
        return true;
    }

    private CoreFlowExecutionPlan resolveVersionedPlan(FlowSnapshot persisted) {
        if (persisted.state().isTerminal()) {
            return planCatalog.get(new PlanKey(persisted.definitionName(), persisted.definitionVersion()));
        }
        if (persisted.definitionVersion() == FlowSnapshot.VERSION_ABSENT) {
            throw FlowEngineException.schemaMismatchDefinitionVersionAbsent(
                    config.engineName(), persisted.currentStep());
        }
        PlanKey key = new PlanKey(persisted.definitionName(), persisted.definitionVersion());
        CoreFlowExecutionPlan plan = planCatalog.get(key);
        if (plan != null) {
            return plan;
        }
        if (!hostsDefinition(persisted.definitionName())) {
            return null;
        }
        throw FlowEngineException.schemaMismatchDefinitionVersionUnresolved(
                config.engineName(), persisted.definitionVersion());
    }

    private boolean hostsDefinition(String definitionName) {
        for (PlanKey registered : planCatalog.keySet()) {
            if (registered.name().equals(definitionName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fail-closed guard against resuming a persisted saga against an incompatible (changed) plan.
     *
     * <p>A snapshot persists {@code currentStep} as a bare zero-based index into the plan it was
     * parked under. If a later deployment removes (or reorders away) steps, that index can point past
     * the end of the currently-registered plan; replaying it blindly would resume at the wrong step —
     * a data-corruption-class outcome — so resume must reject the mismatch rather than proceed.
     *
     * <p>Per {@code docs/subsystems/flow.md}, waking a non-terminal saga whose persisted
     * {@code currentStep} no longer indexes a step in the active definition raises
     * {@code EX-FLOW-7002 / phase=SCHEMA_MISMATCH} (Glass-Box rawArgs via
     * {@link FlowEngineException#schemaMismatch(String, int)}) and requires manual intervention.
     *
     * <p>This method is the <b>bounds/arity</b> half. Since 0.11 it delegates to
     * {@link #validateSnapshotStepIdentity} for the half it structurally cannot cover: a same-arity
     * reorder leaves the index in range, so only comparing step identities detects it (ADR-062).
     * Terminal snapshots ({@link FlowState#isTerminal()}) are exempt — they are never resumed.
     */
    private void validateSnapshotStepBounds(CoreFlowExecutionPlan plan, FlowSnapshot persisted) {
        if (persisted.state().isTerminal()) {
            return;
        }
        int step = persisted.currentStep();
        int stepCount = plan.stepCount();
        // step < 0 makes the invariant explicit (a corrupted snapshot writing a sentinel index also
        // fails closed, not just the redeploy-removed-step case).
        if (step < 0 || step >= stepCount) {
            emitSchemaMismatch(persisted, step, stepCount, FlowEngineException.REASON_STEP_OUT_OF_RANGE, null, null);
            throw FlowEngineException.schemaMismatch(config.engineName(), step);
        }
        validateSnapshotStepIdentity(plan, persisted, step, stepCount);
        validateCompensationStackBounds(persisted, stepCount);
        validateCompensationStackIdentity(plan, persisted);
    }

    /**
     * Rejects a resume whose compensation stack no longer indexes the plan.
     *
     * <p>The two guards above validate where the saga <em>resumes</em>. They say nothing about the
     * steps it has already completed, and those are exactly what a rollback walks — so a saga can pass
     * both and still hold a stack that is meaningless in the plan it just bound to. A migration makes
     * this ordinary rather than exceptional: a transform may rewrite the stack, and
     * {@code FlowMigrationState} documents that carrying it across a version boundary unchanged is
     * wrong, but documenting an obligation is not enforcing it.
     *
     * <p>Checked here rather than at compensation time because {@code runCompensationStep} reads the
     * plan by bare index <em>outside</em> its own catch, so a stale entry there aborts the remaining
     * unwind and skips {@code finalizeFailedInstance} — leaving the saga mid-compensation with its
     * idempotency guard still held. Refusing the resume leaves the row intact instead.
     *
     * <p>This is the bounds half only. An entry that indexes the plan but addresses a different step
     * than it did when it was pushed is not detectable from indices alone; that is
     * {@link #validateCompensationStackIdentity}, which runs after this one so that a structurally
     * broken stack is diagnosed as broken rather than as a mismatch.
     *
     * @param persisted the snapshot being resumed
     * @param stepCount the step count of the plan the resume would bind to
     */
    private void validateCompensationStackBounds(FlowSnapshot persisted, int stepCount) {
        int live = persisted.stackPointer();
        if (live == 0) {
            return;
        }
        // One defensive copy on the resume path. Cold — a resume already cost a snapshot-store read —
        // and FlowSnapshot exposes no per-entry accessor to borrow instead.
        int[] stack = persisted.compensationStack();
        for (int index = 0; index < live; index++) {
            int entry = stack[index];
            if (entry < 0 || entry >= stepCount) {
                // Both step-name fields stay absent: the offending value is a stack entry, so neither
                // "the step the snapshot names" nor "the step the plan has there" is a truthful answer.
                emitSchemaMismatch(persisted, entry, stepCount,
                        FlowEngineException.REASON_COMPENSATION_STACK_OUT_OF_RANGE, null, null);
                throw FlowEngineException.schemaMismatchCompensationStack(config.engineName(), entry);
            }
        }
    }

    /**
     * Rejects a resume whose compensation stack indexes the plan but no longer addresses the same steps
     * (ADR-064 A5).
     *
     * <p>ADR-062's argument for the cursor, applied to the stack: a same-arity reorder leaves every
     * entry in range, so bounds cannot see it. What makes this the more dangerous of the two halves is
     * that nothing throws. An out-of-range entry raises at {@code plan.stepAt} inside failure handling —
     * loud, and the parked row survives to be fixed. An in-range entry that now addresses a different
     * step resolves to a perfectly valid descriptor, and the unwind either skips a compensation that was
     * owed (the addressed step happens to declare none) or runs a <em>different</em> step's
     * compensation. Both are silent, and a compensation is a side effect: by the time anything can
     * observe the mistake it has already been made.
     *
     * <p>A live stack with no identities at all is refused rather than admitted, on ADR-062 obligation
     * 6's reasoning — admitting it would leave a permanent branch where the stack is still trusted by
     * position. That case is reachable independently of the cursor guards: a row carrying a definition
     * version and a cursor identity but no stack identities is what an application
     * {@code FlowSnapshotStore} produces when its schema does not carry the column.
     *
     * <p>One further defensive copy on the cold resume path, over the one the bounds guard already
     * makes. Kept separate rather than threaded through a shared array, because each guard owning its
     * own contract is the shape the cursor pair already established in this class.
     *
     * @param plan      the resolved plan the resume would bind to
     * @param persisted the snapshot being resumed, whose stack is already known to index that plan
     */
    private void validateCompensationStackIdentity(CoreFlowExecutionPlan plan, FlowSnapshot persisted) {
        int live = persisted.stackPointer();
        if (live == 0) {
            return;
        }
        String[] names = persisted.compensationStepNames();
        if (names.length == 0) {
            // No offending entry to name when none of them is named, so the live depth is the
            // diagnostic — it says how much rollback the refusal is protecting.
            emitSchemaMismatch(persisted, live, plan.stepCount(),
                    FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_ABSENT, null, null);
            throw FlowEngineException.schemaMismatchCompensationStackIdentityAbsent(
                    config.engineName(), live);
        }
        int[] stack = persisted.compensationStack();
        for (int index = 0; index < live; index++) {
            int entry = stack[index];
            String planStepName = plan.stepAt(entry).name();
            if (!names[index].equals(planStepName)) {
                emitSchemaMismatch(persisted, entry, plan.stepCount(),
                        FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_MISMATCH,
                        names[index], planStepName);
                throw FlowEngineException.schemaMismatchCompensationStackIdentity(
                        config.engineName(), entry);
            }
        }
    }

    /**
     * Rejects a resume whose persisted step index is in range but no longer names the same step
     * (ADR-062).
     *
     * <p>This is the case the bounds check cannot reach. A same-arity reorder leaves the index valid,
     * so without comparing identities the saga would resume on a different step than it parked at —
     * silently, and with the wrong compensation stack semantics behind it.
     *
     * @param plan      the resolved plan the resume would bind to
     * @param persisted the snapshot being resumed
     * @param step      the persisted step index, already known to be in range
     * @param stepCount the plan's step count, for the diagnostic event
     */
    private void validateSnapshotStepIdentity(CoreFlowExecutionPlan plan,
                                              FlowSnapshot persisted,
                                              int step,
                                              int stepCount) {
        String planStepName = plan.stepAt(step).name();
        Optional<String> persistedName = persisted.currentStepName();
        if (persistedName.isEmpty()) {
            // Written before 0.11. Resuming it would mean trusting the index again, which is the
            // behaviour this guard exists to remove — so it is refused rather than assumed safe.
            emitSchemaMismatch(persisted, step, stepCount,
                    FlowEngineException.REASON_STEP_IDENTITY_ABSENT, null, planStepName);
            throw FlowEngineException.schemaMismatchStepIdentityAbsent(config.engineName(), step);
        }
        if (!persistedName.get().equals(planStepName)) {
            emitSchemaMismatch(persisted, step, stepCount, FlowEngineException.REASON_STEP_IDENTITY_MISMATCH,
                    persistedName.get(), planStepName);
            throw FlowEngineException.schemaMismatchStepIdentity(config.engineName(), step);
        }
    }

    private void emitSchemaMismatch(FlowSnapshot persisted, int step, int stepCount,
                                    String reason, String persistedStepName, String planStepName) {
        FlowSchemaMismatchEvent.emit(
                config.engineName(), persisted.definitionName(),
                persisted.instanceIdMost(), persisted.instanceIdLeast(), step, stepCount,
                reason, persistedStepName, planStepName);
    }

    private void launch(RuntimeFlowInstance instance, int startStep) {
        if (!isActiveLifecycle(instance)) {
            instance.markNotScheduled();
            return;
        }
        int admitted = activeFlows.incrementAndGet();
        if (admitted > config.maxConcurrentFlows()) {
            activeFlows.decrementAndGet();
            instance.markNotScheduled();
            liveInstances.remove(instance.key(), instance);
            throw new FlowEngineException(
                    "Flow scheduling rejected: maxConcurrentFlows limit reached (" + config.maxConcurrentFlows() + ')');
        }
        queueDepth.incrementAndGet();
        Thread thread = Thread.ofVirtual()
                .name("exeris-flow-" + instance.key().instanceIdMost() + '-' + instance.key().instanceIdLeast())
                .unstarted(() -> runInstance(instance, startStep));
        runningThreads.add(thread);
        try {
            thread.start();
        } catch (RuntimeException | Error startFailure) { //NOPMD AvoidCatchingGenericException
            // Only runInstance()'s finally releases these, and a thread that never started never
            // reaches it. Left in place, each refused start costs one activeFlows slot and one
            // queueDepth permanently, so the engine's budget shrinks per occurrence until every
            // launch is rejected with no flow running.
            runningThreads.remove(thread);
            queueDepth.decrementAndGet();
            activeFlows.decrementAndGet();
            instance.markNotScheduled();
            throw startFailure;
        }
    }

    private void runInstance(RuntimeFlowInstance instance, int startStep) {
        try {
            if (!initializeRunState(instance)) {
                return;
            }
            int stepIndex = Math.max(0, startStep);
            while (isActiveLifecycle(instance)) {
                stepIndex = runStep(instance, stepIndex);
                if (stepIndex < 0) {
                    return;
                }
            }
        } finally {
            instance.markNotScheduled();
            if (isCurrentLifecycle(instance)) {
                decrementLifecycleCounterOnExit(queueDepth);
                decrementLifecycleCounterOnExit(activeFlows);
            }
            runningThreads.remove(Thread.currentThread());
            honourDeferredWake(instance);
        }
    }

    /**
     * Re-submits a wake that arrived while this run still held the instance.
     *
     * <p>Ordering is load-bearing: this runs after the exiting thread has deregistered itself and
     * released its lifecycle counters, so {@code close()}'s bounded join never observes a half-torn
     * set. The re-submission hands off to a fresh Virtual Thread rather than nesting, so this thread
     * still exits immediately.
     *
     * <p>Refusals here are legitimately silent. A terminal instance has already finished — there is
     * nothing to wake. A superseded lifecycle generation means the engine restarted underneath this
     * run, and re-launching would resurrect a reclaimed snapshot row rather than resume anything.
     */
    private void honourDeferredWake(RuntimeFlowInstance instance) {
        if (!instance.claimPendingWake()) {
            return;
        }
        if (instance.isTerminal() || !isActiveLifecycle(instance)) {
            return;
        }
        int startStep = instance.beginScheduleAfterWake();
        if (startStep < 0) {
            return;
        }
        // The instance is RUNNING again from here, so it must not remain in the parked index. The
        // immediate path sheds that registration inside resolveParkedInstance; the deferred path
        // reaches launch() without passing through it, so it sheds it here. Conditional on the
        // remove, exactly as the schedule() path is, so a wake resolved through a branch that never
        // registered cannot double-decrement.
        if (parkedInstances.remove(instance.key()) != null) {
            parkedFlows.decrement();
        }
        try {
            launch(instance, startStep);
        } catch (RuntimeException | Error failure) { //NOPMD AvoidCatchingGenericException — see below
            // This runs from runInstance()'s finally, so an exception leaving here would replace
            // whatever the run was already failing with — and destroy the wake either way, since
            // claimPendingWake() consumed it and beginScheduleAfterWake() flipped the instance to
            // RUNNING. The saga would sit marked RUNNING with no thread and no wake left to arrive,
            // indistinguishable from working until someone asks why it never finished.
            restoreParkedAfterFailedWake(instance, failure);
        }
    }

    /** Puts back everything the claimed-but-unlaunched wake took, so it stays recoverable. */
    private void restoreParkedAfterFailedWake(RuntimeFlowInstance instance, Throwable failure) {
        instance.abandonScheduleAfterWake();
        if (!instance.isTerminal()) {
            // Both registries. The commoner failure here is maxConcurrentFlows — a load condition,
            // not an OOM — and launch() drops the instance from liveInstances before throwing.
            // Restoring only the parked index leaves it parked but invisible to every liveInstances
            // lookup, so park() and its neighbours no-op on it; a later wake still recovers it
            // through resolveParkedInstance, which is why this reads as nothing being wrong.
            liveInstances.putIfAbsent(instance.key(), instance);
            if (parkedInstances.putIfAbsent(instance.key(), instance) == null) {
                parkedFlows.increment();
            }
        }
        FlowDeferredWakeFailedEvent event = new FlowDeferredWakeFailedEvent();
        event.definitionName = instance.definitionName();
        event.exceptionType = failure.getClass().getName();
        event.commit();
    }

    /**
     * Executes one step of the flow loop within the context of a running instance.
     * Called by {@link #runInstance} on each while-loop iteration.
     *
     * <p>Returns the next step index to process, or {@link #EXIT_RUN_LOOP} to terminate.
     * Must be called from OUTSIDE any synchronized block.
     */
    private int runStep(RuntimeFlowInstance instance, int stepIndex) {
        StepPlan plan = prepareStep(instance, stepIndex);
        if (plan.exitCode != STEP_PROCEED) {
            return plan.exitCode;
        }
        if (plan.timeoutOverrunNanos != NO_TIMEOUT_OVERRUN) {
            return failOnTimeout(instance, stepIndex, plan.timeoutOverrunNanos);
        }
        FlowOutcome outcome = executeStep(instance, stepIndex, plan.action);
        return finalizeStep(instance, plan.step, stepIndex, outcome);
    }

    /**
     * Synchronized prep phase: bounds-check, snapshot the step, enforce the absolute saga
     * deadline (DIST-303), and consult the {@code IdempotencyGuard}. Returns a {@link StepPlan}
     * describing whether to proceed, time-out, or short-circuit with an exit code.
     */
    private StepPlan prepareStep(RuntimeFlowInstance instance, int stepIndex) {
        synchronized (instance.monitor()) {
            if (stepIndex >= instance.plan().stepCount()) {
                complete(instance);
                return StepPlan.exit(EXIT_RUN_LOOP);
            }
            instance.currentStep(stepIndex);
            FlowStepDescriptor step = instance.plan().stepAt(stepIndex);

            // DIST-303: timeoutNanos == Long.MAX_VALUE encodes Instant.MAX (no timeout).
            long now = System.nanoTime();
            long deadline = instance.timeoutNanos();
            if (deadline != Long.MAX_VALUE && deadline < now) {
                return StepPlan.timedOut(step, now - deadline);
            }
            if (guard != null && !guard.tryClaimStep(
                    instance.key().instanceIdMost(),
                    instance.key().instanceIdLeast(),
                    stepIndex)) {
                int nextGuardIndex = instance.plan().nextStep(stepIndex);
                if (nextGuardIndex < 0) {
                    complete(instance);
                    return StepPlan.exit(EXIT_RUN_LOOP);
                }
                return StepPlan.exit(nextGuardIndex);
            }
            return StepPlan.proceed(step, step.action());
        }
    }

    /**
     * Synchronized post-execution phase: re-check lifecycle and dispatch the outcome through
     * {@link #applyOutcome}.
     */
    private int finalizeStep(
            RuntimeFlowInstance instance, FlowStepDescriptor step, int stepIndex, FlowOutcome outcome) {
        synchronized (instance.monitor()) {
            if (!isCurrentLifecycle(instance)) {
                if (outcome == FlowOutcome.COMPLETE) {
                    complete(instance);
                }
                return EXIT_RUN_LOOP;
            }
            return applyOutcome(instance, step, stepIndex, outcome);
        }
    }

    /**
     * Emits {@link FlowTimeoutEvent} and drives the instance through the compensation path
     * to {@link FlowState#FAILED_ROLLEDBACK}. Always returns {@link #EXIT_RUN_LOOP}.
     */
    private int failOnTimeout(RuntimeFlowInstance instance, int stepIndex, long overrunNanos) {
        FlowTimeoutEvent.emit(
                config.engineName(),
                instance.definitionName(),
                instance.key().instanceIdMost(),
                instance.key().instanceIdLeast(),
                stepIndex,
                overrunNanos);
        fail(instance, stepIndex);
        return EXIT_RUN_LOOP;
    }

    /**
     * Carrier for {@link #prepareStep} → {@link #runStep} hand-off.
     *
     * <ul>
     *   <li>{@code exitCode == STEP_PROCEED}: caller MUST execute {@code action}.</li>
     *   <li>otherwise: caller MUST short-circuit with {@code exitCode}.</li>
     *   <li>{@code timeoutOverrunNanos != NO_TIMEOUT_OVERRUN}: caller MUST run the timeout path.</li>
     * </ul>
     *
     * <p>Allocated once per step iteration on the cold prep path (already inside
     * {@code synchronized}); JIT escape analysis routinely scalarizes this carrier on the
     * Loom virtual-thread dispatch loop, so no heap pressure is introduced on the hot path.
     */
    private record StepPlan(
            FlowStepDescriptor step,
            FlowStepAction     action,
            int                exitCode,
            long               timeoutOverrunNanos) {

        /* default */ static StepPlan proceed(FlowStepDescriptor step, FlowStepAction action) {
            return new StepPlan(step, action, STEP_PROCEED, NO_TIMEOUT_OVERRUN);
        }

        /* default */ static StepPlan timedOut(FlowStepDescriptor step, long overrunNanos) {
            return new StepPlan(step, null, STEP_PROCEED, overrunNanos);
        }

        /* default */ static StepPlan exit(int exitCode) {
            return new StepPlan(null, null, exitCode, NO_TIMEOUT_OVERRUN);
        }
    }

    /**
     * Must be called outside any synchronized block.
     * Returns {@code false} if {@code runInstance} should return early.
     */
    private boolean initializeRunState(RuntimeFlowInstance instance) {
        synchronized (instance.monitor()) {
            if (!isActiveLifecycle(instance) || instance.isTerminal()) {
                return false;
            }
            if (instance.state() == FlowState.PARKED) {
                ensureParkedRegistration(instance);
                return false;
            }
            instance.state(FlowState.RUNNING);
        }
        return true;
    }

    /**
     * Must be called from WITHIN {@code synchronized(instance.monitor())}.
     * Returns next step index, or {@code -1} to exit {@code runInstance}.
     */
    private int applyOutcome(
            RuntimeFlowInstance instance, FlowStepDescriptor step, int stepIndex, FlowOutcome outcome) {
        return switch (outcome) {
            case CONTINUE -> applyContinueOutcome(instance, step, stepIndex);
            case COMPLETE -> {
                applyCompleteOutcome(instance, step);
                yield -1;
            }
            case PARK -> {
                applyParkOutcome(instance, stepIndex);
                yield -1;
            }
            default -> -1;
        };
    }

    private int applyContinueOutcome(
            RuntimeFlowInstance instance, FlowStepDescriptor step, int stepIndex) {
        if (instance.state() == FlowState.PARKED) {
            return -1;
        }
        if (step.hasCompensation() && config.compensationEnabled()) {
            instance.pushCompensation(step.stepId());
        }
        int nextStep = instance.plan().nextStep(stepIndex);
        if (nextStep < 0) {
            complete(instance);
            return -1;
        }
        return nextStep;
    }

    private void applyCompleteOutcome(RuntimeFlowInstance instance, FlowStepDescriptor step) {
        if (step.hasCompensation() && config.compensationEnabled()) {
            instance.pushCompensation(step.stepId());
        }
        complete(instance);
    }

    private void applyParkOutcome(RuntimeFlowInstance instance, int stepIndex) {
        instance.state(FlowState.PARKED);
        ensureParkedRegistration(instance);
        FlowParkCheckpoint.persist(instance, stepIndex, parkAttempt);
    }

    // step action is a user-supplied SPI callback; any Exception is a step failure (Errors propagate)
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private FlowOutcome executeStep(RuntimeFlowInstance instance, int stepIndex, FlowStepAction action) {
        try {
            stepExecutions.increment();
            FlowOutcome outcome = action.execute(instance.contextView());
            if (outcome == FlowOutcome.FAIL) {
                fail(instance, stepIndex);
            }
            return outcome;
        } catch (Exception cause) {
            FlowStepFailedEvent.emit(
                    instance.definitionName(),
                    stepIndex,
                    instance.key().instanceIdMost(),
                    instance.key().instanceIdLeast(),
                    cause);
            fail(instance, stepIndex);
            return FlowOutcome.FAIL;
        }
    }

    private void fail(RuntimeFlowInstance instance, int stepIndex) {
        boolean staleLifecycle = isStaleLifecycle(instance);
        boolean cleanupOnly = cleanupOnly(instance);
        transitionToCompensating(instance, stepIndex, cleanupOnly);
        runCompensations(instance, cleanupOnly);
        finalizeFailedInstance(instance, stepIndex, cleanupOnly, staleLifecycle);
    }

    private void transitionToCompensating(RuntimeFlowInstance instance, int stepIndex, boolean cleanupOnly) {
        instance.currentStep(stepIndex);
        instance.state(FlowState.COMPENSATING);
        if (!cleanupOnly) {
            persistSnapshot(instance, FlowState.COMPENSATING, stepIndex);
        }
    }

    private void runCompensations(RuntimeFlowInstance instance, boolean cleanupOnly) {
        if (!config.compensationEnabled()) {
            return;
        }
        for (int index = instance.stackPointer() - 1; index >= 0; index--) {
            runCompensationStep(instance, index, cleanupOnly);
        }
    }

    private void runCompensationStep(RuntimeFlowInstance instance, int stackIndex, boolean cleanupOnly) {
        int compensationStep = instance.compensationStepAt(stackIndex);
        FlowStepDescriptor descriptor = instance.plan().stepAt(compensationStep);
        if (!descriptor.hasCompensation()) {
            return;
        }
        try {
            instance.currentStep(compensationStep);
            if (!cleanupOnly) {
                compensationsRun.increment();
            }
            descriptor.compensation().execute(instance.contextView());
        // cleanup must continue across all per-step compensation failures (Errors still propagate)
        } catch (Exception compensationCause) { //NOPMD AvoidCatchingGenericException
            FlowStepFailedEvent.emit(
                    instance.definitionName(),
                    compensationStep,
                    instance.key().instanceIdMost(),
                    instance.key().instanceIdLeast(),
                    compensationCause);
        }
    }

    private void finalizeFailedInstance(
            RuntimeFlowInstance instance,
            int stepIndex,
            boolean cleanupOnly,
            boolean staleLifecycle) {
        instance.state(FlowState.FAILED_ROLLEDBACK);
        if (!staleLifecycle && guard != null) {
            guard.releaseInstance(instance.key().instanceIdMost(), instance.key().instanceIdLeast());
        }
        parkedLookupMisses.clearMiss(instance.key());
        if (!cleanupOnly) {
            failedFlows.increment();
            terminalStateCatalog.recordTerminal(instance.key(), FlowState.FAILED_ROLLEDBACK);
        }
        if (!staleLifecycle) {
            persistSnapshot(instance, FlowState.FAILED_ROLLEDBACK, stepIndex);
        }
        if (!cleanupOnly) {
            progressPublisher.publishProgress(instance, stepIndex, FlowState.FAILED_ROLLEDBACK);
        }
        liveInstances.remove(instance.key(), instance);
        if (!cleanupOnly && parkedInstances.remove(instance.key(), instance)) {
            parkedFlows.decrement();
        }
    }

    private void complete(RuntimeFlowInstance instance) {
        boolean staleLifecycle = isStaleLifecycle(instance);
        boolean cleanupOnly = cleanupOnly(instance);
        instance.state(FlowState.COMPLETED);
        parkedLookupMisses.clearMiss(instance.key());
        if (!cleanupOnly) {
            terminalStateCatalog.recordTerminal(instance.key(), FlowState.COMPLETED);
        }
        releaseInstanceClaims(instance, staleLifecycle);
        if (!cleanupOnly) {
            completedFlows.increment();
            progressPublisher.publishProgress(instance, instance.currentStep(), FlowState.COMPLETED);
        }
        liveInstances.remove(instance.key(), instance);
        if (!cleanupOnly && parkedInstances.remove(instance.key(), instance)) {
            parkedFlows.decrement();
        }
        deleteSnapshot(instance, staleLifecycle);
    }

    private void releaseInstanceClaims(RuntimeFlowInstance instance, boolean staleLifecycle) {
        if (staleLifecycle || guard == null) {
            return;
        }
        guard.releaseInstance(instance.key().instanceIdMost(), instance.key().instanceIdLeast());
    }

    private void deleteSnapshot(RuntimeFlowInstance instance, boolean staleLifecycle) {
        if (staleLifecycle || snapshotStore == null) {
            return;
        }
        // FLOW-107: in bounded-catalog mode the durable snapshot is the idempotency fence
        // for COMPLETED entries that may have been evicted from the in-memory catalog.
        // Retain the snapshot here; lifetime is governed by DIST-303 saga TTL retention.
        if (terminalStateCatalog.isBounded()) {
            return;
        }
        try {
            snapshotStore.delete(instance.key().instanceIdMost(), instance.key().instanceIdLeast());
        } catch (RuntimeException ignored) { //NOPMD AvoidCatchingGenericException — best-effort snapshot deletion
            // Best-effort deletion — completion is already recorded; do not fail the flow.
        }
    }

    /** Parked sagas the store refused: wakeable now, gone after a restart. */
    /* default */ long nonDurableParkedFlows() {
        return closed ? nonDurableParkedAtClose
                      : FlowParkCheckpoint.countNonDurable(parkedInstances.values());
    }

    private void persistParkSnapshot(RuntimeFlowInstance instance, int stepIndex) {
        persistSnapshot(instance, FlowState.PARKED, stepIndex);
    }

    private void persistSnapshot(RuntimeFlowInstance instance, FlowState state, int stepIndex) {
        if (snapshotStore == null) {
            return;
        }
        // Lifecycle write-fence: a worker whose runtime is no longer active — closed/not-started or
        // a retired generation — must not re-establish a NON-TERMINAL checkpoint in the shared
        // snapshot store. In the multi-runtime restart model (a new CoreFlowRuntime rebuilt on the
        // same store, as production restart and the saga-recovery TCK both do) an abandoned worker
        // from the closed runtime can otherwise land a save(PARKED) AFTER the rebuilt runtime's
        // complete()->deleteSnapshot() reclaim and resurrect the row, breaking the unbounded-mode
        // "deleted on complete()" invariant (docs/subsystems/flow.md). isStaleLifecycle alone is
        // insufficient: the abandoned worker's own generation counter is unchanged by the *other*
        // runtime's start(), so the guard must also reject a closed/not-started runtime
        // (started && !closed). Terminal finalizations are deliberately exempt: a late FAIL after
        // close() MUST still persist FAILED_ROLLEDBACK on the same runtime (cleanup contract — see
        // CoreFlowRuntimeTest.lateFailedExitAfterCloseStillReleasesClaimsAndFinalizesSnapshot), and
        // a terminal state is not a resurrected checkpoint. The exemption is unconditional on the
        // terminal state: in the two-runtime case a late gen-1 FAIL after gen-2's complete() reclaim
        // may leave an orphaned FAILED_ROLLEDBACK row, but that is benign — gen-2's (and any later
        // generation's) terminalStateCatalog records COMPLETED, so no re-execution follows.
        // Sibling of the staleLifecycle/cleanupOnly guards on deleteSnapshot and finalizeFailedInstance.
        if (!isActiveLifecycle(instance) && !state.isTerminal()) {
            return;
        }
        parkedLookupMisses.clearMiss(instance.key());
        FlowSnapshotWriter.save(snapshotStore, instance, state, stepIndex);
        // ADR-013 §5: durable stores advance schema_version by one on every accepted write.
        // Mirror that increment locally so the next save carries the now-current expected
        // version. In-memory bindings ignore schemaVersion, so the bump is harmless there.
        instance.markPersisted();
    }

    private final class Scheduler implements FlowScheduler {

        @Override
        public void schedule(FlowExecutionPlan plan, FlowContext context) {
            if (context == null) {
                throw new FlowEngineException("FlowContext must not be null");
            }
            CoreFlowRuntime.this.schedule(castPlan(plan), context);
        }

        @Override
        public void park(FlowContext context) {
            if (context == null) {
                throw new FlowEngineException("FlowContext must not be null");
            }
            CoreFlowRuntime.this.park(context);
        }

        @Override
        public void wake(FlowContext context) {
            if (context == null) {
                throw new FlowEngineException("FlowContext must not be null");
            }
            CoreFlowRuntime.this.wake(context);
        }

        @Override
        public void wake(long instanceIdMost, long instanceIdLeast) {
            ensureStarted();
            CoreFlowRuntime.this.wake(new FlowKey(instanceIdMost, instanceIdLeast),
                    FlowState.PARKED);
        }

        @Override
        public Optional<FlowContext> lookupParked(long instanceIdMost, long instanceIdLeast) {
            FlowKey key = new FlowKey(instanceIdMost, instanceIdLeast);
            if (terminalStateCatalog.isTerminal(key)) {
                return Optional.empty();
            }
            RuntimeFlowInstance parked = parkedInstances.get(key);
            if (parked != null) {
                parkedLookupMisses.clearMiss(key);
                return Optional.of(parked.contextView());
            }
            RuntimeFlowInstance live = liveInstances.get(key);
            if (live != null && live.state() == FlowState.PARKED) {
                parkedLookupMisses.clearMiss(key);
                return Optional.of(live.contextView());
            }
            // In-memory miss → fall back to durable snapshot store. Distributed-saga JFR
            // contract (ADR-013 §8): emit WakeOnLoadFallbackEvent so operators can monitor
            // cross-engine wake patterns and slow durable-store reads.
            long fallbackStartNanos = System.nanoTime();
            RuntimeFlowInstance restored = restoreParkedFromSnapshot(key);
            WakeOnLoadFallbackEvent.emit(
                    config.engineName(),
                    instanceIdMost,
                    instanceIdLeast,
                    restored != null,
                    System.nanoTime() - fallbackStartNanos);
            return restored == null ? Optional.empty() : Optional.of(restored.contextView());
        }

        private static CoreFlowExecutionPlan castPlan(FlowExecutionPlan plan) {
            if (plan == null) {
                throw new FlowEngineException("FlowExecutionPlan must not be null");
            }
            if (!(plan instanceof CoreFlowExecutionPlan corePlan)) {
                throw new FlowEngineException(
                        "Unsupported FlowExecutionPlan implementation: " + plan.getClass().getName());
            }
            return corePlan;
        }
    }

    /**
     * In-memory idempotency fence for terminal flow instances.
     *
     * <p>Two operating modes selected at construction by {@link FlowEngineConfig#terminalCatalogMaxSize()}:
     * <ul>
     *   <li><b>{@code maxSize == 0} — unbounded.</b> Backed by {@link ConcurrentHashMap}; entries
     *       accumulate for the lifetime of the engine. Backward-compatible with v0.6 behavior.</li>
     *   <li><b>{@code maxSize  > 0} — bounded LRU.</b> Backed by {@link LinkedHashMap} with
     *       access-order; the least-recently-accessed entry is evicted when the cap is reached.
     *       Snapshot deletion is suppressed in this mode (see {@link #deleteSnapshot}) so that
     *       the durable {@link FlowSnapshotStore} retains the COMPLETED row as the persistent
     *       idempotency fence; long-running runtimes therefore stay memory-bounded without
     *       weakening idempotency.</li>
     * </ul>
     *
     * <p>Lookups in bounded mode go through {@code synchronized} on a dedicated lock — the path is
     * cold (resubmit guard, called once per schedule attempt), so the lock cost is acceptable; a
     * read-write lock can be introduced if profiling later shows contention.
     */
    private static final class TerminalStateCatalog {

        private final int maxSize;
        private final Map<FlowKey, FlowState> map;
        private final Object lock = new Object();

        /* default */ TerminalStateCatalog(int maxSize) {
            this.maxSize = maxSize;
            if (maxSize == 0) {
                this.map = new ConcurrentHashMap<>();
            } else {
                this.map = new LinkedHashMap<>(16, 0.75f, true) {
                    private static final long serialVersionUID = 1L;
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<FlowKey, FlowState> eldest) {
                        return size() > maxSize;
                    }
                };
            }
        }

        /* default */ boolean isBounded() {
            return maxSize > 0;
        }

        /* default */ boolean isTerminal(FlowKey key) {
            if (maxSize == 0) {
                return map.containsKey(key);
            }
            synchronized (lock) {
                // get() — not containsKey — to mark the entry as recently accessed under accessOrder=true.
                return map.get(key) != null;
            }
        }

        /* default */ void recordTerminal(FlowKey key, FlowState state) {
            if (maxSize == 0) {
                map.put(key, state);
                return;
            }
            synchronized (lock) {
                map.put(key, state);
            }
        }

        /* default */ void clear() {
            if (maxSize == 0) {
                map.clear();
                return;
            }
            synchronized (lock) {
                map.clear();
            }
        }
    }
}
