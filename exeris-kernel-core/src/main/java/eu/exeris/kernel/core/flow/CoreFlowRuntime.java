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
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class CoreFlowRuntime { // NOPMD

    private final FlowEngineConfig config;
    private final FlowProgressPublisher progressPublisher;
    private final Scheduler scheduler = new Scheduler();
    private final ConcurrentMap<FlowKey, RuntimeFlowInstance> liveInstances = new ConcurrentHashMap<>();
    private final ConcurrentMap<FlowKey, RuntimeFlowInstance> parkedInstances = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CoreFlowExecutionPlan> planCatalog = new ConcurrentHashMap<>();
    private final ConcurrentMap<FlowKey, FlowState> terminalStateCatalog = new ConcurrentHashMap<>();
    private final Set<Thread> runningThreads = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeFlows = new AtomicInteger();
    private final LongAdder parkedFlows = new LongAdder();
    private final LongAdder completedFlows = new LongAdder();
    private final LongAdder failedFlows = new LongAdder();
    private final LongAdder compensationsRun = new LongAdder();
    private final LongAdder stepExecutions = new LongAdder();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private volatile FlowSnapshotStore snapshotStore;
    private volatile IdempotencyGuard guard;
    private volatile boolean started;
    private volatile boolean closed;

    /* default */ CoreFlowRuntime(FlowEngineConfig config, FlowProgressPublisher progressPublisher) {
        this.config = Objects.requireNonNull(config, "config");
        this.progressPublisher = Objects.requireNonNull(progressPublisher, "progressPublisher");
    }

    public FlowScheduler scheduler() {
        return scheduler;
    }

    /* default */ ConcurrentMap<String, CoreFlowExecutionPlan> planCatalog() {
        return planCatalog;
    }

    public FlowEngineStats stats() {
        return new FlowEngineStats(
                activeFlows.get(),
                parkedFlows.sum(),
                completedFlows.sum(),
                failedFlows.sum(),
                compensationsRun.sum(),
                stepExecutions.sum(),
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
        closed = false;
        started = true;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        interruptAndJoinRunningThreads();
        runningThreads.clear();
        liveInstances.clear();
        parkedInstances.clear();
        terminalStateCatalog.clear();
    }

    private void interruptAndJoinRunningThreads() {
        Thread[] threads = runningThreads.toArray(Thread[]::new);
        for (Thread thread : threads) {
            thread.interrupt();
        }
        boolean interrupted = false;
        for (Thread thread : threads) {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (thread.isAlive() && System.nanoTime() < deadline) {
                try {
                    thread.join(java.time.Duration.ofMillis(100));
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public void assertStarted() {
        ensureStarted();
    }

    private void ensureStarted() {
        if (!started || closed) {
            throw engineNotStarted();
        }
    }

    private FlowEngineException engineNotStarted() {
        return FlowEngineException.startupFailure(
                config.engineName(),
                new IllegalStateException("Flow engine is not started"));
    }

    private FlowEngineException notParked(FlowKey key) {
        return new FlowEngineException(
                "Cannot wake flow that is not currently parked: " + key);
    }

    private void schedule(CoreFlowExecutionPlan plan, FlowContext context) {
        ensureStarted();
        FlowKey key = FlowKey.from(context);
        if (terminalStateCatalog.containsKey(key)) {
            return;
        }

        RuntimeFlowInstance instance = liveInstances.compute(key, (flowKey, current) -> {
            if (current != null) {
                current.attachPlan(plan);
                return current;
            }
            RuntimeFlowInstance restored = restoreFromSnapshot(flowKey, plan);
            return restored != null ? restored : RuntimeFlowInstance.fromContext(plan, context);
        });

        if (instance.isTerminal() || terminalStateCatalog.containsKey(key)) {
            liveInstances.remove(key, instance);
            return;
        }

        instance.captureEventEngine();

        int startStep = instance.beginScheduleForSchedule();
        if (startStep < 0) {
            return;
        }

        if (parkedInstances.remove(key) != null) {
            parkedFlows.decrement();
        }
        launch(instance, startStep);
    }

    private void park(FlowContext context) {
        FlowKey key = FlowKey.from(context);
        RuntimeFlowInstance instance = liveInstances.get(key);
        if (instance == null || instance.state() == FlowState.PARKED || instance.isTerminal()) {
            return;
        }
        synchronized (instance.monitor()) {
            if (instance.state() == FlowState.PARKED || instance.isTerminal()) {
                return;
            }
            instance.state(FlowState.PARKED);
            parkedInstances.put(key, instance);
            parkedFlows.increment();
            persistSnapshot(instance, FlowState.PARKED, instance.currentStep());
        }
    }

    private void wake(FlowContext context) {
        ensureStarted();
        FlowKey key = FlowKey.from(context);
        if (terminalStateCatalog.containsKey(key)) {
            return;
        }

        RuntimeFlowInstance instance = parkedInstances.remove(key);
        if (instance != null) {
            parkedFlows.decrement();
        } else {
            RuntimeFlowInstance live = liveInstances.get(key);
            if (live != null && live.state() != FlowState.PARKED) {
                throw notParked(key);
            }
            instance = restoreFromSnapshot(key, null);
            if (instance == null) {
                throw notParked(key);
            }
            liveInstances.put(key, instance);
        }

        if (instance.isTerminal() || terminalStateCatalog.containsKey(key)) {
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

    private RuntimeFlowInstance restoreFromSnapshot(FlowKey key, CoreFlowExecutionPlan directPlan) {
        if (snapshotStore == null) {
            return null;
        }
        Optional<FlowSnapshot> snapshot = snapshotStore.load(key.instanceIdMost(), key.instanceIdLeast());
        if (snapshot.isEmpty()) {
            return null;
        }
        CoreFlowExecutionPlan resolvedPlan = directPlan;
        if (resolvedPlan == null) {
            resolvedPlan = planCatalog.get(snapshot.get().definitionName());
        } else if (!resolvedPlan.definitionName().equals(snapshot.get().definitionName())) {
            throw new FlowEngineException(
                    "Plan definition mismatch: scheduled '"
                    + resolvedPlan.definitionName()
                    + "' but snapshot belongs to '"
                    + snapshot.get().definitionName() + "'");
        }
        if (resolvedPlan == null) {
            throw new FlowEngineException(
                    "No compiled FlowExecutionPlan available for definition: " + snapshot.get().definitionName());
        }
        return RuntimeFlowInstance.fromSnapshot(resolvedPlan, snapshot.get());
    }

    private void launch(RuntimeFlowInstance instance, int startStep) {
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
        thread.start();
    }

    @SuppressWarnings("PMD.CognitiveComplexity")
    private void runInstance(RuntimeFlowInstance instance, int startStep) { // NOPMD
        try {
            synchronized (instance.monitor()) {
                if (closed || !started || instance.isTerminal()) {
                    return;
                }
                instance.state(FlowState.RUNNING);
            }

            int stepIndex = Math.max(0, startStep);

            while (!closed && started) {
                FlowStepDescriptor step;
                FlowStepAction stepAction;

                synchronized (instance.monitor()) {
                    if (stepIndex >= instance.plan().stepCount()) {
                        complete(instance);
                        return;
                    }
                    instance.currentStep(stepIndex);
                    step = instance.plan().stepAt(stepIndex);

                    if (guard != null && !guard.tryClaimStep(
                            instance.key().instanceIdMost(),
                            instance.key().instanceIdLeast(),
                            stepIndex)) {
                        int nextGuardIndex = instance.plan().nextStep(stepIndex);
                        if (nextGuardIndex < 0) {
                            complete(instance);
                            return;
                        }
                        stepIndex = nextGuardIndex;
                        continue;
                    }
                    stepAction = step.action();
                }

                FlowOutcome outcome = executeStep(instance, stepIndex, stepAction);

                synchronized (instance.monitor()) {
                    switch (outcome) {
                        case CONTINUE -> {
                            if (instance.state() == FlowState.PARKED) {
                                return;
                            }
                            if (step.hasCompensation() && config.compensationEnabled()) {
                                instance.pushCompensation(step.stepId());
                            }
                            int nextStep = instance.plan().nextStep(stepIndex);
                            if (nextStep < 0) {
                                complete(instance);
                                return;
                            }
                            stepIndex = nextStep;
                        }
                        case COMPLETE -> {
                            if (step.hasCompensation() && config.compensationEnabled()) {
                                instance.pushCompensation(step.stepId());
                            }
                            complete(instance);
                            return;
                        }
                        case PARK -> {
                            instance.state(FlowState.PARKED);
                            RuntimeFlowInstance previous = parkedInstances.putIfAbsent(instance.key(), instance);
                            persistSnapshot(instance, FlowState.PARKED, stepIndex);
                            if (previous == null) {
                                parkedFlows.increment();
                            }
                            return;
                        }
                        case FAIL -> {
                            return;
                        }
                    }
                }
            }
        } finally {
            instance.markNotScheduled();
            queueDepth.decrementAndGet();
            activeFlows.decrementAndGet();
            runningThreads.remove(Thread.currentThread());
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private FlowOutcome executeStep(RuntimeFlowInstance instance, int stepIndex, FlowStepAction action) {
        try {
            stepExecutions.increment();
            FlowOutcome outcome = action.execute(instance.contextView());
            if (outcome == FlowOutcome.FAIL) {
                fail(instance, stepIndex);
            }
            return outcome;
        } catch (Throwable cause) {
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

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void fail(RuntimeFlowInstance instance, int stepIndex) {
        instance.currentStep(stepIndex);
        instance.state(FlowState.COMPENSATING);
        persistSnapshot(instance, FlowState.COMPENSATING, stepIndex);

        if (config.compensationEnabled()) {
            for (int index = instance.stackPointer() - 1; index >= 0; index--) {
                int compensationStep = instance.compensationStepAt(index);
                FlowStepDescriptor descriptor = instance.plan().stepAt(compensationStep);
                if (!descriptor.hasCompensation()) {
                    continue;
                }
                try {
                    instance.currentStep(compensationStep);
                    compensationsRun.increment();
                    descriptor.compensation().execute(instance.contextView());
                } catch (Throwable compensationCause) {
                    FlowStepFailedEvent.emit(
                            instance.definitionName(),
                            compensationStep,
                            instance.key().instanceIdMost(),
                            instance.key().instanceIdLeast(),
                            compensationCause);
                }
            }
        }

        instance.state(FlowState.FAILED_ROLLEDBACK);
        if (guard != null) {
            guard.releaseInstance(instance.key().instanceIdMost(), instance.key().instanceIdLeast());
        }
        failedFlows.increment();
        terminalStateCatalog.put(instance.key(), FlowState.FAILED_ROLLEDBACK);
        persistSnapshot(instance, FlowState.FAILED_ROLLEDBACK, stepIndex);
        progressPublisher.publishProgress(instance, stepIndex, FlowState.FAILED_ROLLEDBACK);
        liveInstances.remove(instance.key());
        if (parkedInstances.remove(instance.key()) != null) {
            parkedFlows.decrement();
        }
    }

    private void complete(RuntimeFlowInstance instance) {
        instance.state(FlowState.COMPLETED);
        terminalStateCatalog.put(instance.key(), FlowState.COMPLETED);
        if (guard != null) {
            guard.releaseInstance(instance.key().instanceIdMost(), instance.key().instanceIdLeast());
        }
        completedFlows.increment();
        progressPublisher.publishProgress(instance, instance.currentStep(), FlowState.COMPLETED);
        liveInstances.remove(instance.key());
        if (parkedInstances.remove(instance.key()) != null) {
            parkedFlows.decrement();
        }
        if (snapshotStore != null) {
            try {
                snapshotStore.delete(instance.key().instanceIdMost(), instance.key().instanceIdLeast());
            } catch (RuntimeException ignored) {
                // Best-effort deletion — completion is already recorded; do not fail the flow.
            }
        }
    }

    private void persistSnapshot(RuntimeFlowInstance instance, FlowState state, int stepIndex) {
        if (snapshotStore == null) {
            return;
        }
        snapshotStore.save(instance.toSnapshot(state, stepIndex));
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
        public Optional<FlowContext> lookupParked(long instanceIdMost, long instanceIdLeast) {
            RuntimeFlowInstance parked = parkedInstances.get(new FlowKey(instanceIdMost, instanceIdLeast));
            return parked == null ? Optional.empty() : Optional.of(parked.contextView());
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
}
