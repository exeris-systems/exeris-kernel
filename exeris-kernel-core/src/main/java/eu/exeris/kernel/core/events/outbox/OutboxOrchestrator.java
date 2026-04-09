/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events.outbox;

import eu.exeris.kernel.core.events.jfr.OutboxBatchFlushedEvent;
import eu.exeris.kernel.core.events.jfr.OutboxDlqEvent;
import eu.exeris.kernel.core.events.jfr.OutboxStateTransitionEvent;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import jdk.jfr.FlightRecorder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
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
 * {@code StructuredTaskScope.open(Joiner.awaitAll())}. Calling {@link #start()}
 * spawns an internal owner virtual thread ({@code ownerThread}) that opens the scope,
 * forks the poll-flush task, and manages the scope lifetime until {@link #stop()}
 * signals shutdown and the owner thread joins. {@code open()}, {@code fork()},
 * {@code join()}, and {@code close()} are all invoked by that same internal owner
 * thread, satisfying the Java 26 owner-thread rule.
 *
 * <h2>VarHandle State Transitions</h2>
 * <p>State is stored in a {@code volatile int} field. All transitions use
 * {@link VarHandle#compareAndSet} or {@link VarHandle#getAndSet} — O(1), lock-free,
 * no {@code synchronized}.
 *
 * @since 0.5.0
 */
// TooManyMethods, CyclomaticComplexity, GodClass: state machine + outbox lifecycle + builder
// cannot be split without introducing artificial coordination abstractions.
// ATFD driven by necessary port interfaces (eventStore, brokerPort) + VarHandle state access.
@SuppressWarnings({
    "PMD.TooManyMethods",
    "PMD.AvoidCatchingGenericException", 
    "PMD.CyclomaticComplexity", 
    "PMD.GodClass"
})
public final class OutboxOrchestrator implements AutoCloseable {

    // ── State machine ordinals ────────────────────────────────────────────────
    private static final int STATE_IDLE     = 0;
    private static final int STATE_POLLING  = 1;
    private static final int STATE_FLUSHING = 2;
    private static final int STATE_WAITING  = 3;
    private static final int STATE_RETRYING = 4;
    private static final int STATE_STOPPED  = 5;

    private static final int SINGLE_EVENT_PUBLISHED = 1;

    private static final String[] STATE_NAMES =
            {"IDLE", "POLLING", "FLUSHING", "WAITING", "RETRYING", "STOPPED"};

    private static final VarHandle STATE_VH;

    static {
        try {
            STATE_VH = MethodHandles.lookup()
                    .findVarHandle(OutboxOrchestrator.class, "state", int.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    // ── Configuration ─────────────────────────────────────────────────────────
    private final OutboxEventStore eventStore;
    private final OutboxBrokerPort brokerPort;
    private final int              batchSize;
    private final long             pollIntervalNanos;
    private final int              maxRetries;

    // ── Mutable state — all access via VarHandle or AtomicBoolean ─────────────
    @SuppressWarnings("PMD.UnusedPrivateField")
    private volatile int        state   = STATE_IDLE;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── Loop scope (owned by the thread that called start()) ──────────────────
    private volatile Thread ownerThread;

    @SuppressWarnings("PMD.LawOfDemeter")
    private OutboxOrchestrator(Builder builder) {
        OutboxEventStore store = builder.eventStore;
        OutboxBrokerPort port  = builder.brokerPort;
        this.eventStore        = store;
        this.brokerPort        = port;
        this.batchSize         = builder.batchSize;
        this.pollIntervalNanos = builder.pollIntervalNanos;
        this.maxRetries        = builder.maxRetries;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Starts the outbox poll-flush loop on a dedicated owner virtual thread.
     *
     * <p>A single virtual thread is started; it is the owner of a
     * {@code StructuredTaskScope} that drives the loop. All scope operations
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
        if ((int) STATE_VH.getVolatile(this) == STATE_STOPPED) {
            running.set(false);
            throw new IllegalStateException(
                    "OutboxOrchestrator cannot be restarted after stop(); create a new instance.");
        }
        transitionTo(STATE_POLLING, 0);

        // Owner VT — the sole thread that may call fork/join/close on the scope.
        // Exempt from StructuredTaskScope rule per docs/modules/02-core.md §3(b):
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
        int prev = (int) STATE_VH.getAndSet(this, STATE_STOPPED);
        emitTransition(STATE_NAMES[prev], STATE_NAMES[STATE_STOPPED], 0);

        Thread owner = this.ownerThread;
        if (owner != null) {
            owner.interrupt();
            try {
                owner.join();
            } catch (InterruptedException ex) {
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
        return STATE_NAMES[(int) STATE_VH.getVolatile(this)];
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
        try (StructuredTaskScope<Void, Void> scope =
                     StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            scope.fork(() -> {
                runLoop();
                return null;
            });
            try {
                scope.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =========================================================================
    // Internal — loop body
    // =========================================================================

    private void runLoop() {
        while (running.get()) {
            if ((int) STATE_VH.getVolatile(this) == STATE_STOPPED) {
                break;
            }
            executeTick();
        }
    }

    @SuppressWarnings("PMD.LawOfDemeter")
    private void executeTick() {
        try {
            List<OutboxBrokerPort.OutboxEntry> batch = eventStore.pollPending(batchSize);
            int batchCount = batch.size();

            if (batchCount == 0) {
                transitionTo(STATE_WAITING, 0);
                LockSupport.parkNanos(pollIntervalNanos);
                transitionTo(STATE_POLLING, 0);
                return;
            }

            transitionTo(STATE_FLUSHING, batchCount);
            flushBatch(batch);
            transitionTo(STATE_POLLING, 0);

        } catch (RuntimeException ex) {
            transitionTo(STATE_WAITING, 0);
            LockSupport.parkNanos(pollIntervalNanos);
            transitionTo(STATE_POLLING, 0);
        }
    }

    private void flushBatch(List<OutboxBrokerPort.OutboxEntry> batch) {
        long startNanos = System.nanoTime();
        int  failed     = 0;

        List<EventDescriptor> toDeliver = new ArrayList<>(batch.size());
        try {
            int rawPublished = brokerPort.publish(batch);
            int published = Math.max(0, Math.min(rawPublished, batch.size()));
            failed = batch.size() - published;

            for (int i = 0; i < published; i++) {
                toDeliver.add(batch.get(i).descriptor());
            }
            if (!toDeliver.isEmpty()) {
                eventStore.markDelivered(toDeliver);
            }

            if (failed > 0) {
                handlePartialFailure(batch, published);
            }
        } finally {
            closeEntryPayloads(batch);
            emitBatchFlushed(batch.size() - failed, System.nanoTime() - startNanos, failed);
        }
    }

    private void handlePartialFailure(List<OutboxBrokerPort.OutboxEntry> batch, int successCount) {
        transitionTo(STATE_RETRYING, 0);
        for (int i = successCount; i < batch.size(); i++) {
            OutboxBrokerPort.OutboxEntry entry = batch.get(i);
            try {
                boolean recovered = retryEntry(entry);
                if (!recovered) {
                    emitDlqTransition(entry, "max retries exhausted");
                    eventStore.moveToDlq(entry.descriptor(), entry.payload(), "max retries exhausted");
                }
            } catch (RuntimeException ex) {
                String message = ex.getMessage();
                String reason = (message == null || message.isBlank()) ? "exception" : message;
                emitDlqTransition(entry, reason);
                eventStore.moveToDlq(entry.descriptor(), entry.payload(), reason);
            }
        }
    }

    private void emitDlqTransition(OutboxBrokerPort.OutboxEntry entry, String reason) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        OutboxDlqEvent dlqEvt = new OutboxDlqEvent();
        if (dlqEvt.isEnabled()) {
            dlqEvt.eventType    = String.valueOf(entry.descriptor().eventTypeOrdinal());
            dlqEvt.streamIdHigh = entry.descriptor().streamIdHigh();
            dlqEvt.streamIdLow  = entry.descriptor().streamIdLow();
            dlqEvt.reason       = reason;
            dlqEvt.retryCount   = maxRetries;
            dlqEvt.commit();
        }
    }

    private boolean retryEntry(OutboxBrokerPort.OutboxEntry entry) {
        List<OutboxBrokerPort.OutboxEntry> single = List.of(entry);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (brokerPort.publish(single) == SINGLE_EVENT_PUBLISHED) {
                    eventStore.markDelivered(List.of(entry.descriptor()));
                    return true;
                }
            } catch (RuntimeException ex) {
                // retry on next attempt
            }
            LockSupport.parkNanos(pollIntervalNanos * attempt);
        }
        return false;
    }

    @SuppressWarnings("PMD.CloseResource")
    private static void closeEntryPayloads(List<OutboxBrokerPort.OutboxEntry> batch) {
        for (OutboxBrokerPort.OutboxEntry entry : batch) {
            EventPayload payload = entry.payload();
            if (payload != null && payload.isAlive()) {
                payload.close();
            }
        }
    }

    // ── State transitions ─────────────────────────────────────────────────────

    private void transitionTo(int next, int polledCount) {
        if (next != STATE_STOPPED && !running.get()) {
            return;
        }

        while (true) {
            int prev = (int) STATE_VH.getVolatile(this);
            if (next != STATE_STOPPED && prev == STATE_STOPPED) {
                return;
            }
            if (prev == next) {
                return;
            }
            if (STATE_VH.compareAndSet(this, prev, next)) {
                emitTransition(STATE_NAMES[prev], STATE_NAMES[next], polledCount);
                return;
            }
        }
    }

    // ── JFR helpers ──────────────────────────────────────────────────────────

    private static void emitTransition(String prev, String next, int polled) {
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

    private static void emitBatchFlushed(int size, long durationNanos, int failed) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        OutboxBatchFlushedEvent evt = new OutboxBatchFlushedEvent();
        if (evt.isEnabled()) {
            evt.batchSize          = size;
            evt.flushDurationNanos = durationNanos;
            evt.failedCount        = failed;
            evt.commit();
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
