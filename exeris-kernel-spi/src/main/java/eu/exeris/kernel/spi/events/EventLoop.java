/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.events.EventEngineException;

/**
 * SPI: Asynchronous event processing loop.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li><b>Community</b>: Drains the {@link EventQueue} and forks handler invocations
 *       via {@code StructuredTaskScope} (JEP 525). One virtual thread per handler
 *       invocation. Structured concurrency ensures all handlers complete before the
 *       loop tick finishes — no orphan threads.</li>
 *   <li><b>Enterprise</b>: Lock-free single-thread event loop with optional CPU affinity.
 *       Drains the ring buffer in batches, dispatches via raw-address routing table.
 *       Zero heap allocation per tick. Deterministic processing order.</li>
 * </ul>
 *
 * <h2>Processor Registration</h2>
 * <p>Processors are registered <em>before</em> {@link #start()} is called. Registering
 * a processor after start is implementation-defined — some implementations may allow it
 * (Community) while others may not (Enterprise, due to fixed routing table).
 *
 * <h2>Stop Semantics</h2>
 * <p>After {@link #stop()} returns, the loop MUST have processed all events that were
 * in the queue at the time {@link #stop()} was called. Events pushed after {@link #stop()}
 * is called may be silently dropped — callers should stop publishing before stopping the loop.
 *
 * @since 0.5.0
 * @see EventBatchProcessor
 * @see EventQueue
 */
public interface EventLoop {

    /**
     * Starts the event processing loop.
     *
     * <p>Community: opens a {@code StructuredTaskScope} and forks the loop task.
     * Enterprise: starts the lock-free loop on a dedicated carrier (or virtual) thread.
     *
     * <p>Emits an {@code EventLoopStartedEvent} JFR event.
     *
     * <p>This method is idempotent — calling it on an already-running loop has no effect.
     *
     * @throws EventEngineException if the loop cannot be started
     */
    void start();

    /**
     * Requests a graceful stop of the event processing loop.
     *
     * <p>This method blocks until the loop has drained all pending events and stopped.
     * Community: shuts down the {@code StructuredTaskScope} and joins all pending tasks.
     * Enterprise: drains the ring buffer to zero, then halts the loop thread.
     *
     * <p>Emits an {@code EventLoopStoppedEvent} JFR event.
     *
     * <p>This method is idempotent — calling it on an already-stopped loop has no effect.
     */
    void stop();

    /**
     * Returns {@code true} if the loop is running and accepting events.
     *
     * @return {@code true} if running
     */
    boolean isRunning();

    /**
     * Registers a batch processor for events of the given type.
     *
     * <p>The processor receives a batch of descriptors per loop tick, enabling
     * batch-optimised processing (e.g., writing a batch to the outbox in a single
     * database statement).
     *
     * <p>Multiple processors may be registered for the same event type — they are
     * invoked in registration order.
     *
     * <p>For Enterprise: this method MUST be called before {@link #start()}.
     * Registering after start is undefined behaviour.
     *
     * @param eventType the event type name to process (e.g. {@code "UserCreated"})
     * @param processor the batch processor (non-null)
     */
    void registerProcessor(String eventType, EventBatchProcessor processor);
}

