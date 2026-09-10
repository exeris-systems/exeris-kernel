/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.outbox;

import eu.exeris.kernel.core.events.jfr.OutboxStateTransitionEvent;
import jdk.jfr.FlightRecorder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.BooleanSupplier;

/**
 * Lock-free state machine for {@link OutboxOrchestrator}.
 *
 * <p>Extracted from {@link OutboxOrchestrator} in v0.8 Sprint 1 (QA-014) to close
 * the orchestrator's God-class suppression block. Owns the six-state ordinal
 * encoding, the {@link VarHandle} CAS transition primitive, and the JFR
 * {@link OutboxStateTransitionEvent} emit on every successful transition.
 *
 * <p>All transitions are lock-free O(1) — no {@code synchronized} block.
 * Reads use {@code getVolatile}; writes use {@code compareAndSet} or
 * {@code getAndSet}. STOPPED is a terminal absorbing state.
 *
 * <h2>State transitions</h2>
 * <pre>
 *   IDLE ──► POLLING ──► FLUSHING ──► POLLING
 *               │           │
 *               ▼           ▼
 *            WAITING     RETRYING ──► POLLING
 *               │
 *           parkNanos
 *               └──► POLLING
 *
 *   Any state ──► STOPPED  (terminal — set only by forceTransitionToStopped)
 * </pre>
 */
final class OutboxStateMachine {

    /* default */ static final int IDLE     = 0;
    /* default */ static final int POLLING  = 1;
    /* default */ static final int FLUSHING = 2;
    /* default */ static final int WAITING  = 3;
    /* default */ static final int RETRYING = 4;
    /* default */ static final int STOPPED  = 5;

    private static final String[] STATE_NAMES =
            {"IDLE", "POLLING", "FLUSHING", "WAITING", "RETRYING", "STOPPED"};

    private static final VarHandle STATE_VH;

    static {
        try {
            STATE_VH = MethodHandles.lookup()
                    .findVarHandle(OutboxStateMachine.class, "state", int.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @SuppressWarnings("PMD.UnusedPrivateField") // accessed via STATE_VH (VarHandle).
    private volatile int state = IDLE;

    private final BooleanSupplier runningCheck;

    /* default */ OutboxStateMachine(BooleanSupplier runningCheck) {
        this.runningCheck = runningCheck;
    }

    /* default */ String currentStateName() {
        return STATE_NAMES[(int) STATE_VH.getVolatile(this)];
    }

    /* default */ int currentOrdinal() {
        return (int) STATE_VH.getVolatile(this);
    }

    /* default */ boolean isStopped() {
        return currentOrdinal() == STOPPED;
    }

    /**
     * Attempts a CAS transition to {@code next}. No-op if already stopped (except
     * the transition to STOPPED itself) or if the running check returns false.
     * Emits an {@link OutboxStateTransitionEvent} on successful transition.
     */
    /* default */ void transitionTo(int next, int polledCount) {
        if (next != STOPPED && !runningCheck.getAsBoolean()) {
            return;
        }

        while (true) {
            int prev = currentOrdinal();
            if (next != STOPPED && prev == STOPPED) {
                return;
            }
            if (prev == next) {
                return;
            }
            if (STATE_VH.compareAndSet(this, prev, next)) {
                emitTransitionEvent(STATE_NAMES[prev], STATE_NAMES[next], polledCount);
                return;
            }
        }
    }

    /**
     * Force-transitions to STOPPED via {@code getAndSet}, returning the previous
     * ordinal. Used by {@code stop()} to bypass the running check.
     */
    /* default */ void forceTransitionToStopped() {
        int prev = (int) STATE_VH.getAndSet(this, STOPPED);
        emitTransitionEvent(STATE_NAMES[prev], STATE_NAMES[STOPPED], 0);
    }

    private static void emitTransitionEvent(String prev, String next, int polled) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        OutboxStateTransitionEvent evt = new OutboxStateTransitionEvent();
        if (evt.isEnabled()) {
            evt.previousState = prev;
            evt.nextState     = next;
            evt.polledCount   = polled;
            evt.commit();
        }
    }
}
