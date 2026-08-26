/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.outbox;

import eu.exeris.kernel.core.concurrent.StructuredScope;
import eu.exeris.kernel.core.events.jfr.OutboxLoopFailureEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Core: Transactional Outbox Orchestrator — guaranteed at-least-once delivery state machine.
 *
 * <h2>State Machine</h2>
 * <pre>
 *   IDLE ──start()──► POLLING ──events found──► FLUSHING ──all ACKed──► IDLE
 *                        │                          │
 *                        │ (empty poll)             │ (partial fail)
 *                        ▼                          ▼
 *                     WAITING                    RETRYING ──max retries──► DLQ
 *                        │
 *                    LockSupport.parkNanos(pollInterval)
 *                        └──► POLLING
 * </pre>
 *
 * <h2>At-Least-Once Guarantee</h2>
 * <p>The orchestrator polls the {@link OutboxEventStore} for pending events, dispatches
 * them to the {@link OutboxBrokerPort}, and only calls {@link OutboxEventStore#markDelivered}
 * after the broker confirms acknowledgement. A JVM crash between poll and mark leaves
 * events in the store — they will be re-polled on the next boot cycle.
 *
 * <h2>Concurrency (JEP 525, Java 26)</h2>
 * <p>The poll-flush loop runs on a single virtual thread managed through
 * {@code StructuredScope.openWithoutBindings()}. Calling {@link #start()}
 * spawns an internal owner virtual thread ({@code ownerThread}) that opens the scope,
 * forks the poll-flush task, and manages the scope lifetime until {@link #stop()}
 * signals shutdown and the owner thread joins. {@code open()}, {@code fork()},
 * {@code join()}, and {@code close()} are all invoked by that same internal owner
 * thread, satisfying the Java 26 owner-thread rule.
 *
 * <h2>Decomposition (v0.8 Sprint 1 QA-014)</h2>
 * <p>State transitions and the {@link java.lang.invoke.VarHandle} CAS primitive
 * live in {@link OutboxStateMachine}; batch flush + retry + DLQ delivery live in
 * {@link OutboxBatchFlusher}. This orchestrator owns only lifecycle (start/stop/
 * close), the owner virtual thread and {@code StructuredScope} wiring,
 * the poll-flush tick loop, and the fluent builder.
 *
 * @since 0.5.0
 */
public final class OutboxOrchestrator implements AutoCloseable {

    private final OutboxEventStore eventStore;
    private final int              batchSize;
    private final long             pollIntervalNanos;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final OutboxStateMachine stateMachine;
    private final OutboxBatchFlusher batchFlusher;

    private volatile Thread ownerThread;

    @SuppressWarnings("PMD.LawOfDemeter") // builder field access is the canonical Builder pattern.
    private OutboxOrchestrator(Builder builder) {
        this.eventStore        = builder.eventStore;
        this.batchSize         = builder.batchSize;
        this.pollIntervalNanos = builder.pollIntervalNanos;
        this.stateMachine      = new OutboxStateMachine(running::get);
        this.batchFlusher      = new OutboxBatchFlusher(
                builder.eventStore,
                builder.brokerPort,
                stateMachine,
                builder.maxRetries,
                builder.pollIntervalNanos);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Starts the outbox poll-flush loop on a dedicated owner virtual thread.
     *
     * <p>A single virtual thread is started; it is the owner of a
     * {@code StructuredScope} that drives the loop. All scope operations
     * (open, fork, join, close) happen on that owner thread — satisfying the
     * Java 26 owner-thread rule. The calling thread returns immediately.
     * Idempotent — if already running, this is a no-op.
     * STOPPED is terminal: calling {@code start()} after {@link #stop()} throws
     * {@link IllegalStateException}. Create a new instance to restart.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (stateMachine.isStopped()) {
            running.set(false);
            throw new IllegalStateException(
                    "OutboxOrchestrator cannot be restarted after stop(); create a new instance.");
        }
        stateMachine.transitionTo(OutboxStateMachine.POLLING, 0);

        // Owner VT — the sole thread that may call fork/join/close on the scope.
        // Exempt from the structured-scope rule per docs/modules/02-core.md §3(b):
        // long-lived background maintenance loops whose lifetime equals the subsystem.
        ownerThread = Thread.ofVirtual().start(this::ownerLoop);
    }

    /**
     * Signals a graceful stop and waits for the owner thread to terminate.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        stateMachine.forceTransitionToStopped();

        Thread owner = this.ownerThread;
        if (owner != null) {
            owner.interrupt();
            try {
                owner.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the current state name for diagnostics and JFR.
     *
     * @return one of: IDLE, POLLING, FLUSHING, WAITING, RETRYING, STOPPED
     */
    public String currentState() {
        return stateMachine.currentStateName();
    }

    // =========================================================================
    // Internal — owner loop (all scope operations here, same thread)
    // =========================================================================

    /**
     * Runs entirely on the owner VT. Opens the scope, runs the poll-flush loop
     * as a single forked task, joins when stopped, closes.
     *
     * <p>This design ensures {@code fork()}, {@code join()}, and {@code close()}
     * are always called by the same owner thread — Java 26 owner-thread rule satisfied.
     */
    private void ownerLoop() {
        // openWithoutBindings, not a compromise: the owner thread is started by a plain
        // Thread.ofVirtual() above, so it carries no ScopedValue bindings for a child to inherit.
        // The StructuredTaskScope this replaces propagated an empty set too.
        try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
            StructuredScope.ForkedTask<Void> loop = scope.fork(() -> {
                runLoop();
                return null;
            });
            try {
                scope.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            // join() waits for the task and returns normally whether it succeeded or threw — the
            // caller inspects state(). Discarding the handle here made every escaping Throwable
            // vanish: `running` stayed true, the state machine kept reporting a live poll loop, and
            // the outbox stalled for good with unpublished events piling up behind a green health
            // check. executeTick() absorbs RuntimeException itself, so what reaches this is what the
            // loop was never going to survive — an Error such as NoClassDefFoundError from a broker
            // driver. Nothing here can recover it; what it can do is stop the lie.
            reportLoopFailure(loop);
        }
    }

    private void reportLoopFailure(StructuredScope.ForkedTask<Void> loop) {
        if (loop.state() != StructuredScope.State.FAILED) {
            return;
        }
        OutboxLoopFailureEvent event = new OutboxLoopFailureEvent();
        event.exceptionType = loop.exception().getClass().getName();
        event.stateAtFailure = stateMachine.currentStateName();
        event.commit();

        // The state machine, not `running`. `running` is stop()'s latch: stop() gates on
        // compareAndSet(true, false), so clearing it here would make the kernel's later shutdown
        // return immediately without interrupting the owner, joining it, or forcing the stopped
        // transition — disarming the drain in exactly the failure case this reporting exists to
        // surface. What falsely claimed to be running was the state machine, and that is what this
        // corrects; forceTransitionToStopped() is what stop() itself calls, so a later stop() finds
        // nothing left to do rather than finding its own guard already tripped.
        stateMachine.forceTransitionToStopped();
    }

    private void runLoop() {
        while (running.get()) {
            if (stateMachine.isStopped()) {
                break;
            }
            executeTick();
        }
    }

    @SuppressWarnings({
        "PMD.AvoidCatchingGenericException", // tick body must wrap broker/store RuntimeException defensively.
        "PMD.LawOfDemeter"                   // batch.size() on List parameter is the canonical pattern.
    })
    private void executeTick() {
        try {
            List<OutboxBrokerPort.OutboxEntry> batch = eventStore.pollPending(batchSize);
            int batchCount = batch.size();

            if (batchCount == 0) {
                stateMachine.transitionTo(OutboxStateMachine.WAITING, 0);
                LockSupport.parkNanos(pollIntervalNanos);
                stateMachine.transitionTo(OutboxStateMachine.POLLING, 0);
                return;
            }

            stateMachine.transitionTo(OutboxStateMachine.FLUSHING, batchCount);
            batchFlusher.flush(batch);
            stateMachine.transitionTo(OutboxStateMachine.POLLING, 0);

        } catch (RuntimeException _) {
            stateMachine.transitionTo(OutboxStateMachine.WAITING, 0);
            LockSupport.parkNanos(pollIntervalNanos);
            stateMachine.transitionTo(OutboxStateMachine.POLLING, 0);
        }
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /** Creates a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link OutboxOrchestrator}.
     */
    public static final class Builder {

        private OutboxEventStore eventStore;
        private OutboxBrokerPort brokerPort;
        private int              batchSize         = 500;
        private long             pollIntervalNanos  = 100_000_000L; // 100 ms
        private int              maxRetries        = 5;

        /** The store to poll for pending outbox events. */
        public Builder eventStore(OutboxEventStore store) {
            this.eventStore = Objects.requireNonNull(store, "eventStore");
            return this;
        }

        /** The broker port to which flushed events are delivered. */
        public Builder brokerPort(OutboxBrokerPort port) {
            this.brokerPort = Objects.requireNonNull(port, "brokerPort");
            return this;
        }

        /** Maximum events per poll-flush cycle (default: 500). */
        public Builder batchSize(int size) {
            if (size <= 0) {
                throw new IllegalArgumentException("batchSize must be > 0");
            }
            this.batchSize = size;
            return this;
        }

        /** Idle wait between empty polls in nanoseconds (default: 100 ms). */
        public Builder pollIntervalNanos(long nanos) {
            if (nanos <= 0) {
                throw new IllegalArgumentException("pollIntervalNanos must be > 0");
            }
            this.pollIntervalNanos = nanos;
            return this;
        }

        /** Maximum per-event retry attempts before DLQ (default: 5). */
        public Builder maxRetries(int retries) {
            if (retries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0");
            }
            this.maxRetries = retries;
            return this;
        }

        /** Builds the orchestrator. Does not start the loop. */
        public OutboxOrchestrator build() {
            Objects.requireNonNull(eventStore, "eventStore is required");
            Objects.requireNonNull(brokerPort, "brokerPort is required");
            return new OutboxOrchestrator(this);
        }
    }
}
