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
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.persistence.TransactionLifecycle")
@Label("Transaction Lifecycle")
@Category({"Exeris Kernel", "Persistence"})
@Description("Emitted when a managed transaction commits, rolls back, or exhausts retries.")
@StackTrace(false) // hot path — stack trace too expensive
final class TransactionLifecycleEvent extends Event {

    @Label("Outcome")
    @Description("COMMIT, ROLLBACK, or RETRY_EXHAUSTED")
    /* default */ String outcome;

    @Label("Attempt Number")
    @Description("Which attempt succeeded or failed (1-based)")
    /* default */ int attemptNumber;

    @Label("Retry Exhausted")
    @Description("true if all retry attempts were consumed without success")
    /* default */ boolean retryExhausted;

    /* default */ void recordCommit(int attempt) {
        if (!isEnabled()) {
            return;
        }
        begin();
        this.outcome        = "COMMIT";
        this.attemptNumber  = attempt;
        this.retryExhausted = false;
        commit();
    }

    /* default */ void recordRollback(int attempt) {
        if (!isEnabled()) {
            return;
        }
        begin();
        this.outcome        = "ROLLBACK";
        this.attemptNumber  = attempt;
        this.retryExhausted = false;
        commit();
    }

    /* default */ void recordRetryExhausted(int totalAttempts) {
        if (!isEnabled()) {
            return;
        }
        begin();
        this.outcome        = "RETRY_EXHAUSTED";
        this.attemptNumber  = totalAttempts;
        this.retryExhausted = true;
        commit();
    }
}
