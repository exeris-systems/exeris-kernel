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
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowState;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class RuntimeFlowInstance implements RuntimeFlowContextStateView { // NOPMD

    /* default */ static final int PARKED_SCHEDULE_NOOP = -2;

    private static final int[]    EMPTY_STACK        = new int[0];
    private static final String[] EMPTY_STEP_NAMES   = new String[0];
    private static final byte[]   EMPTY_OPAQUE_STATE = new byte[0];

    private final FlowKey key;
    private final String definitionName;
    /** The definition version this instance runs under; written into every snapshot (ADR-064). */
    private final int definitionVersion;
    private final long lifecycleGeneration;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final Object monitor = new Object();
    private final RuntimeFlowContext contextView;
    private volatile CoreFlowExecutionPlan plan;
    private volatile EventEngine eventEngine;
    private volatile FlowState state;
    /** A wake refused because a run still held the instance; guarded by {@code monitor}. */
    private boolean wakePending;
    private volatile int currentStep;
    private volatile long timeoutNanos;
    private int[] compensationStack;
    private int stackPointer;
    // ADR-013 §5: optimistic-lock version that round-trips with the durable snapshot store.
    // fromContext seeds this to FlowSnapshot.SCHEMA_VERSION_INITIAL (new instance — never
    // saved); fromSnapshot reads back the loaded version so subsequent saves stay aligned
    // with the on-disk row. Bumped via markPersisted() after every accepted save (INSERT
    // and UPDATE both advance by one in any compliant durable store).
    //
    // AtomicLong rather than volatile long: read-modify-write on volatile is not atomic
    // (java:S3078) and we cannot rely on the per-instance monitor for every call site of
    // markPersisted() to make the increment safe — the durable-store contract permits
    // multiple persist paths, and AtomicLong removes the dependency on caller discipline.
    private final AtomicLong schemaVersion;

    /**
     * Carrier for {@link RuntimeFlowInstance} construction parameters. Groups identity,
     * persisted state, and the compensation stack into a single record so the constructor
     * stays under the SonarQube S107 arity limit (≤ 7).
     *
     * <p>The {@code int[] compensationStack} component is shared by reference with the
     * surrounding instance — this preserves the historical ownership semantics (the array
     * is mutated in place by {@code pushCompensation()} / {@code compensationStepAt()})
     * and keeps the bootstrap allocation-free. Because the record holds a mutable array
     * it is <em>not</em> a Valhalla value-type candidate; the carrier is intentionally
     * private and consumed within a single constructor frame so no escape can race the
     * subsequent in-place mutation.
     *
     * <p>{@code java:S6218}: the default record {@code equals}/{@code hashCode}/
     * {@code toString} compare {@code int[]} by identity rather than by content. This is
     * acceptable here because {@code Seed} is a private one-shot carrier — never compared,
     * hashed, or logged. Suppressing rather than overriding keeps the carrier lean and
     * avoids accidental array copies on a path that runs once per flow instance.
     */
    @SuppressWarnings("java:S6218")
    private record Seed(FlowKey key,
                        String definitionName,
                        int definitionVersion,
                        long lifecycleGeneration,
                        CoreFlowExecutionPlan plan,
                        FlowState state,
                        int currentStep,
                        long timeoutNanos,
                        int[] compensationStack,
                        int stackPointer,
                        long schemaVersion) { }

    private RuntimeFlowInstance(Seed seed) {
        this.key = seed.key();
        this.definitionName = seed.definitionName();
        this.definitionVersion = seed.definitionVersion();
        this.lifecycleGeneration = seed.lifecycleGeneration();
        this.contextView = new RuntimeFlowContext(seed.key(), seed.definitionName(), this);
        this.plan = seed.plan();
        this.state = seed.state();
        this.currentStep = seed.currentStep();
        this.timeoutNanos = seed.timeoutNanos();
        this.compensationStack = seed.compensationStack();
        this.stackPointer = seed.stackPointer();
        this.schemaVersion = new AtomicLong(seed.schemaVersion());
    }

    public static RuntimeFlowInstance fromContext(
            CoreFlowExecutionPlan plan,
            FlowContext context,
            long lifecycleGeneration) {
        long timeoutNanos = context.timeoutNanos() > 0
                ? context.timeoutNanos()
                : System.nanoTime() + plan.timeoutDurationNanos();
        return new RuntimeFlowInstance(new Seed(
                FlowKey.from(context),
                context.definitionName(),
                plan.definitionVersion(),
                lifecycleGeneration,
                plan,
                context.state(),
                Math.max(0, context.currentStep()),
                timeoutNanos,
                new int[Math.max(4, plan.stepCount())],
                0,
                FlowSnapshot.SCHEMA_VERSION_INITIAL
        ));
    }

    public static RuntimeFlowInstance fromSnapshot(
            CoreFlowExecutionPlan plan,
            FlowSnapshot snapshot,
            long lifecycleGeneration) {
        long timeoutNanos;
        if (Instant.MAX.equals(snapshot.timeout())) {
            timeoutNanos = Long.MAX_VALUE;
        } else {
            long remainingNanos = Duration.between(Instant.now(), snapshot.timeout()).toNanos();
            timeoutNanos = System.nanoTime() + Math.max(0L, remainingNanos);
        }
        int[] snapshotStack = snapshot.compensationStack();
        int[] compensationStack = snapshotStack.length == 0
                ? new int[Math.max(4, plan.stepCount())]
                : snapshotStack;
        return new RuntimeFlowInstance(new Seed(
                new FlowKey(snapshot.instanceIdMost(), snapshot.instanceIdLeast()),
                snapshot.definitionName(),
                plan.definitionVersion(),
                lifecycleGeneration,
                plan,
                snapshot.state(),
                snapshot.currentStep(),
                timeoutNanos,
                compensationStack,
                snapshot.stackPointer(),
                snapshot.schemaVersion()
        ));
    }

    public FlowKey key() {
        return key;
    }

    public String definitionName() {
        return definitionName;
    }

    public Object monitor() {
        return monitor;
    }

    public long lifecycleGeneration() {
        return lifecycleGeneration;
    }

    public CoreFlowExecutionPlan plan() {
        return plan;
    }

    @Override
    public FlowState state() {
        return state;
    }

    public void state(FlowState newState) {
        this.state = newState;
    }

    @Override
    public int currentStep() {
        return currentStep;
    }

    public void currentStep(int step) {
        this.currentStep = step;
    }

    @Override
    public long timeoutNanos() {
        return timeoutNanos;
    }

    public int stackPointer() {
        return stackPointer;
    }

    public int compensationStepAt(int index) {
        return compensationStack[index];
    }

    public void markNotScheduled() {
        synchronized (monitor) {
            scheduled.set(false);
        }
    }

    public boolean isScheduled() {
        return scheduled.get();
    }

    public void attachPlan(CoreFlowExecutionPlan candidate) {
        if (plan == null && candidate != null) {
            plan = candidate;
        }
    }

    public void captureEventEngine() {
        if (KernelProviders.EVENT_ENGINE.isBound()) {
            eventEngine = KernelProviders.eventEngine();
        }
    }

    public EventEngine eventEngine() {
        return eventEngine;
    }

    public void pushCompensation(int stepId) {
        if (stackPointer == compensationStack.length) {
            compensationStack = Arrays.copyOf(compensationStack, compensationStack.length * 2);
        }
        compensationStack[stackPointer] = stepId;
        stackPointer++;
    }

    public int beginScheduleForSchedule() {
        synchronized (monitor) {
            if (scheduled.get() || isTerminal()) {
                return -1;
            }
            if (state == FlowState.PARKED) {
                return PARKED_SCHEDULE_NOOP;
            }
            scheduled.set(true);
            return currentStep;
        }
    }

    public int beginScheduleAfterWake() {
        synchronized (monitor) {
            if (isTerminal()) {
                return -1;
            }
            if (scheduled.get()) {
                // A run still owns this instance, so it cannot be re-submitted yet. Recording the
                // intent under the same monitor that guards `scheduled` makes the refusal and the
                // deferral atomic: FlowScheduler.wake() promises to re-submit the flow for
                // execution, and dropping the request here left a parked saga stranded with no
                // trace — a choreography wake is one event per business trigger, not a poll.
                wakePending = true;
                return -1;
            }
            scheduled.set(true);
            state = FlowState.RUNNING;
            return Math.min(currentStep + 1, plan.stepCount());
        }
    }

    /**
     * Claims a wake that arrived while a run was still in flight, if there was one.
     *
     * <p>Called once by the draining run so the deferred wake is honoured exactly once; a second
     * caller sees nothing pending.
     *
     * @return {@code true} if a deferred wake was claimed by this caller
     */
    public boolean claimPendingWake() {
        synchronized (monitor) {
            if (!wakePending) {
                return false;
            }
            wakePending = false;
            return true;
        }
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public FlowSnapshot toSnapshot(FlowState snapshotState, int stepIndex) {
        int[] stack = stackPointer == 0 ? EMPTY_STACK : Arrays.copyOf(compensationStack, stackPointer);
        return new FlowSnapshot(
                key.instanceIdMost(),
                key.instanceIdLeast(),
                definitionName,
                definitionVersion,
                Math.max(0, stepIndex),
                stepNameAt(Math.max(0, stepIndex)),
                snapshotState,
                Instant.now(),
                timeoutInstant(),
                stack,
                compensationStepNames(),
                stackPointer,
                EMPTY_OPAQUE_STATE,
                schemaVersion.get()
        );
    }

    /**
     * Resolves the identity of every live compensation-stack entry (ADR-064 A5).
     *
     * <p>Derived at snapshot time rather than tracked alongside {@code compensationStack}, because in
     * memory the two can never disagree: every push comes from a descriptor the bound plan resolved, so
     * a parallel in-heap array would be redundant state whose only distinctive behaviour is the way it
     * desynchronises — {@code pushCompensation} grows the stack by doubling off {@code
     * compensationStack.length} alone, and a second array that missed a growth would silently shift.
     *
     * <p>Cold path: a snapshot is written on park, eviction or a terminal transition, never per step.
     *
     * @return one name per live entry, or an empty array when nothing is live
     */
    private String[] compensationStepNames() {
        CoreFlowExecutionPlan currentPlan = plan;
        if (stackPointer == 0 || currentPlan == null) {
            return EMPTY_STEP_NAMES;
        }
        String[] names = new String[stackPointer];
        for (int index = 0; index < stackPointer; index++) {
            names[index] = currentPlan.stepAt(compensationStack[index]).name();
        }
        return names;
    }

    /**
     * Resolves the identity of the step this snapshot parks at (ADR-062).
     *
     * <p>Empty when the index addresses no step — which happens on completion, where
     * {@code stepIndex == stepCount}. That is not a missing identity: a terminal snapshot is never
     * resumed, so the resume-side identity check skips it before the absence could matter.
     *
     * @param stepIndex the index being recorded
     * @return the step's name, or empty when the index addresses no step
     */
    private Optional<String> stepNameAt(int stepIndex) {
        CoreFlowExecutionPlan currentPlan = plan;
        if (currentPlan == null || stepIndex >= currentPlan.stepCount()) {
            return Optional.empty();
        }
        return Optional.of(currentPlan.stepAt(stepIndex).name());
    }

    /**
     * Returns the optimistic-lock version expected by the durable snapshot store
     * for the next save (ADR-013 §5).
     */
    public long schemaVersion() {
        return schemaVersion.get();
    }

    /**
     * Advances the local view of {@code schemaVersion} after a successful save.
     * Compliant durable stores advance the on-disk version by exactly one on every
     * accepted write (INSERT or UPDATE), so the caller bumps locally by one
     * regardless of which path the store took. The bump is atomic via {@link AtomicLong},
     * so this method is safe under concurrent persist paths without external locking.
     */
    public void markPersisted() {
        schemaVersion.incrementAndGet();
    }

    public RuntimeFlowContext contextView() {
        return contextView;
    }

    private Instant timeoutInstant() {
        if (timeoutNanos == Long.MAX_VALUE) {
            return Instant.MAX;
        }
        long remainingNanos = Math.max(0L, timeoutNanos - System.nanoTime());
        return Instant.now().plusNanos(remainingNanos);
    }
}
