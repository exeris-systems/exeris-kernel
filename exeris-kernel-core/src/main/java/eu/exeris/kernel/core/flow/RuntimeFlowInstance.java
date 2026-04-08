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
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class RuntimeFlowInstance { // NOPMD

    private static final int[]  EMPTY_STACK        = new int[0];
    private static final byte[] EMPTY_OPAQUE_STATE = new byte[0];

    private final FlowKey key;
    private final String definitionName;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final Object monitor = new Object();
    private final RuntimeFlowContext contextView = new RuntimeFlowContext(this);
    private volatile CoreFlowExecutionPlan plan;
    private volatile EventEngine eventEngine;
    private volatile FlowState state;
    private volatile int currentStep;
    private volatile long timeoutNanos;
    private int[] compensationStack;
    private int stackPointer;

    private RuntimeFlowInstance(FlowKey key,
                                String definitionName,
                                CoreFlowExecutionPlan plan,
                                FlowState state,
                                int currentStep,
                                long timeoutNanos,
                                int[] compensationStack,
                                int stackPointer) {
        this.key = key;
        this.definitionName = definitionName;
        this.plan = plan;
        this.state = state;
        this.currentStep = currentStep;
        this.timeoutNanos = timeoutNanos;
        this.compensationStack = compensationStack;
        this.stackPointer = stackPointer;
    }

    public static RuntimeFlowInstance fromContext(CoreFlowExecutionPlan plan, FlowContext context) {
        return new RuntimeFlowInstance(
                FlowKey.from(context),
                context.definitionName(),
                plan,
                context.state(),
                Math.max(0, context.currentStep()),
                context.timeoutNanos() > 0
                        ? context.timeoutNanos()
                        : System.nanoTime() + plan.timeoutDurationNanos(),
                new int[Math.max(4, plan.stepCount())],
                0
        );
    }

    public static RuntimeFlowInstance fromSnapshot(CoreFlowExecutionPlan plan, FlowSnapshot snapshot) {
        long remainingNanos = Duration.between(Instant.now(), snapshot.timeout()).toNanos();
        long timeoutNanos = System.nanoTime() + Math.max(0L, remainingNanos);
        return new RuntimeFlowInstance(
                new FlowKey(snapshot.instanceIdMost(), snapshot.instanceIdLeast()),
                snapshot.definitionName(),
                plan,
                snapshot.state(),
                snapshot.currentStep(),
                timeoutNanos,
                snapshot.compensationStack(),
                snapshot.stackPointer()
        );
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

    public CoreFlowExecutionPlan plan() {
        return plan;
    }

    public FlowState state() {
        return state;
    }

    public void state(FlowState newState) {
        this.state = newState;
    }

    public int currentStep() {
        return currentStep;
    }

    public void currentStep(int step) {
        this.currentStep = step;
    }

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
            scheduled.set(true);
            return state == FlowState.PARKED ? Math.min(currentStep + 1, plan.stepCount()) : currentStep;
        }
    }

    public int beginScheduleAfterWake() {
        synchronized (monitor) {
            if (scheduled.get() || isTerminal()) {
                return -1;
            }
            scheduled.set(true);
            state = FlowState.RUNNING;
            return Math.min(currentStep + 1, plan.stepCount());
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
                Math.max(0, stepIndex),
                snapshotState,
                Instant.now(),
                timeoutInstant(),
                stack,
                stackPointer,
                EMPTY_OPAQUE_STATE
        );
    }

    public RuntimeFlowContext contextView() {
        return contextView;
    }

    private Instant timeoutInstant() {
        long remainingNanos = timeoutNanos == Long.MAX_VALUE
                ? 0L
                : Math.max(0L, timeoutNanos - System.nanoTime());
        return Instant.now().plusNanos(remainingNanos);
    }
}
