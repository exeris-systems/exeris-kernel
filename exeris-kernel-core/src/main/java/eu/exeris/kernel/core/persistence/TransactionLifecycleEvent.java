/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted by {@link TransactionOrchestrator} on begin, commit, rollback, or retry exhaustion.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every critical transaction lifecycle event MUST produce a JFR record.
 * SRE tooling watches for {@code retryExhausted=true} as an early deadlock/hotspot signal.
 * The full observable sequence for a managed transaction is:
 * {@code BEGIN → COMMIT | ROLLBACK}, and {@code RETRY_EXHAUSTED} if all attempts fail.
 *
 * <h2>Virtual-Thread Safety</h2>
 * <p>All emit methods check {@link #EVENT_TYPE} before allocating a
 * {@code TransactionLifecycleEvent}, eliminating avoidable heap pressure when
 * the event type is disabled.  No shared mutable state — safe under concurrent
 * Virtual Thread execution.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.persistence.TransactionLifecycle")
@Label("Transaction Lifecycle")
@Category({"Exeris Kernel", "Persistence"})
@Description("Emitted on each transaction lifecycle boundary: BEGIN, COMMIT, WORK_COMPLETE, ROLLBACK, RETRY_EXHAUSTED.")
@StackTrace(false)
final class TransactionLifecycleEvent extends Event {

    /** Cached event-type probe; {@code isEnabled()} is always dynamically evaluated. */
    private static final EventType EVENT_TYPE =
            EventType.getEventType(TransactionLifecycleEvent.class);

    @Label("Outcome")
    @Description("BEGIN, COMMIT, WORK_COMPLETE, ROLLBACK, or RETRY_EXHAUSTED")
    /* default */ String outcome;

    @Label("Attempt Number")
    @Description("Which attempt succeeded or failed (1-based)")
    /* default */ int attemptNumber;

    @Label("Retry Exhausted")
    @Description("true if all retry attempts were consumed without success")
    /* default */ boolean retryExhausted;

    @Label("Duration (ns)")
    @Description("Wall-clock duration of the transaction work in nanoseconds (0 for rollback/exhaustion)")
    /* default */ long durationNs;

    /* default */ static void recordBegin(int attempt) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        evt.begin();
        evt.outcome        = "BEGIN";
        evt.attemptNumber  = attempt;
        evt.retryExhausted = false;
        evt.durationNs     = 0L;
        evt.commit();
    }

    /* default */ static void recordCommit(int attempt, long durationNs) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        evt.begin();
        evt.outcome        = "COMMIT";
        evt.attemptNumber  = attempt;
        evt.retryExhausted = false;
        evt.durationNs     = durationNs;
        evt.commit();
    }

    /**
     * Emitted by {@link TransactionOrchestrator#execute} (manual-transaction mode) when
     * the work lambda returns without leaving an open transaction.
     *
     * <p>Core cannot claim a {@code COMMIT} here because the work lambda owns
     * {@code beginTransaction()} and {@code commit()} — Core only enforces the
     * "no dangling transaction" post-condition. The outcome is recorded as
     * {@code WORK_COMPLETE} to avoid conflating manual-mode success with a
     * managed {@code COMMIT}.
     */
    /* default */ static void recordWorkComplete(int attempt, long durationNs) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        evt.begin();
        evt.outcome        = "WORK_COMPLETE";
        evt.attemptNumber  = attempt;
        evt.retryExhausted = false;
        evt.durationNs     = durationNs;
        evt.commit();
    }

    /* default */ static void recordRollback(int attempt) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        evt.begin();
        evt.outcome        = "ROLLBACK";
        evt.attemptNumber  = attempt;
        evt.retryExhausted = false;
        evt.commit();
    }

    /* default */ static void recordRetryExhausted(int totalAttempts) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        evt.begin();
        evt.outcome        = "RETRY_EXHAUSTED";
        evt.attemptNumber  = totalAttempts;
        evt.retryExhausted = true;
        evt.commit();
    }
}
