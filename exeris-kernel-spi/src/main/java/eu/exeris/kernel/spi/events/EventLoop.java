/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.events.EventEngineException;

/**
 * SPI: Asynchronous event processing loop.
 *
 * <h2>Processor Registration</h2>
 * <p>Processors are registered <em>before</em> {@link #start()} is called. Registering
 * a processor after start is implementation-defined — some implementations may allow it
 * (Community) while others may not (Enterprise, due to fixed routing table).
 *
 * @implSpec After {@link #stop()} returns, every event that was in the queue when
 *           {@link #stop()} was called has been processed. {@link #start()} and {@link #stop()}
 *           are both idempotent: applying either to a loop already in that state is a no-op, not
 *           an error.
 * @apiNote Events pushed after {@link #stop()} is called may be silently dropped — stop
 *          publishing before stopping the loop if none may be lost.
 * @implNote Community drains the {@link EventQueue} and forks handler invocations into a
 *           structured scope, one virtual thread per invocation, so every handler completes
 *           before the tick finishes and no thread outlives it. Enterprise runs a lock-free
 *           single-thread loop with optional CPU affinity, draining the ring buffer in batches
 *           through a raw-address routing table with zero heap allocation per tick and a
 *           deterministic processing order.
 * @since 0.5
 * @see EventBatchProcessor
 * @see EventQueue
 */
public interface EventLoop {

    /**
     * Puts the loop into the state where it drains {@link EventQueue} ticks and dispatches to the
     * registered processors.
     *
     * @throws EventEngineException {@code EX-EVENT-6001} if the loop cannot be started, unless the
     *         binding raises a more specific {@code EX-EVENT-*} code
     * @implSpec Idempotent — calling it on an already-running loop has no effect.
     * @implNote Community opens a structured scope and forks the loop task into it; Enterprise
     *           starts the lock-free loop on a dedicated carrier (or virtual) thread.
     */
    void start();

    /**
     * Stops the loop, blocking until everything already queued has been processed — so a normal
     * return means the backlog is gone, not merely that dispatch has ceased.
     *
     * @implSpec Idempotent — calling it on an already-stopped loop has no effect.
     * @implNote Community shuts down the structured scope and joins every pending task;
     *           Enterprise drains the ring buffer to zero, then halts the loop thread.
     */
    void stop();

    /**
     * Reports whether the loop is currently draining and dispatching, and therefore whether a
     * newly published event will be picked up.
     *
     * @return {@code true} between a successful {@link #start()} and the {@link #stop()} that
     *         follows it
     */
    boolean isRunning();

    /**
     * Binds a batch processor to an event type, so each tick's drained events of that type are
     * handed to it as one batch rather than one at a time.
     *
     * <p>This is what makes the fixed cost per batch instead of per event — one database
     * statement for N outbox rows, one flush for N payloads.
     *
     * @param eventType the event type name to process (e.g. {@code "UserCreated"})
     * @param processor the batch processor (non-null)
     * @implSpec Several processors may be registered for one event type; no ordering between them
     *           is guaranteed.
     * @apiNote Register before {@link #start()}. Whether a later registration is honoured is
     *          implementation-defined — the Enterprise routing table is fixed at start and
     *          registering after it is undefined behaviour.
     * @implNote Community forks each registered processor to run concurrently against its own
     *           copy of the batch, with no ordering barrier between the forks.
     */
    void registerProcessor(String eventType, EventBatchProcessor processor);
}

