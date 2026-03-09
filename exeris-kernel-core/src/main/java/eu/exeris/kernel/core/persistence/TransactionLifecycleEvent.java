/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted by {@link TransactionOrchestrator} on commit, rollback, or retry exhaustion.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every critical transaction lifecycle event MUST produce a JFR record.
 * SRE tooling watches for {@code retryExhausted=true} as an early deadlock/hotspot signal.
 *
 * <h2>Virtual-Thread Safety</h2>
 * <p>All emit methods are static and allocate a fresh {@code TransactionLifecycleEvent}
 * instance per invocation, guarded by {@link FlightRecorder#isInitialized()}.
 * No shared mutable state — safe under concurrent Virtual Thread execution.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.persistence.TransactionLifecycle")
@Label("Transaction Lifecycle")
@Category({"Exeris Kernel", "Persistence"})
@Description("Emitted when a managed transaction commits, rolls back, or exhausts retries.")
@StackTrace(false)
final class TransactionLifecycleEvent extends Event {

    @Label("Outcome")
    @Description("COMMIT, WORK_COMPLETE, ROLLBACK, or RETRY_EXHAUSTED")
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

    /* default */ static void recordCommit(int attempt, long durationNs) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        if (!evt.isEnabled()) {
            return;
        }
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
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.begin();
        evt.outcome        = "WORK_COMPLETE";
        evt.attemptNumber  = attempt;
        evt.retryExhausted = false;
        evt.durationNs     = durationNs;
        evt.commit();
    }

    /* default */ static void recordRollback(int attempt) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.begin();
        evt.outcome        = "ROLLBACK";
        evt.attemptNumber  = attempt;
        evt.retryExhausted = false;
        evt.commit();
    }

    /* default */ static void recordRetryExhausted(int totalAttempts) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransactionLifecycleEvent evt = new TransactionLifecycleEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.begin();
        evt.outcome        = "RETRY_EXHAUSTED";
        evt.attemptNumber  = totalAttempts;
        evt.retryExhausted = true;
        evt.commit();
    }
}
