/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.events.EventEngineException;

/**
 * SPI: Central Event Engine facade.
 *
 * <h2>Composite Design</h2>
 * <p>The engine is a single point of access to all event subsystem components.
 * It is obtained via {@link EventProvider#createEngine} and propagated to all
 * subsystems via {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE}.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link EventProvider#createEngine} — created by the bootstrapper.</li>
 *   <li>{@link #start()} — initialises all components (allocates resources,
 *       starts processing loops).</li>
 *   <li>Runtime — subsystems call {@link #bus()}, {@link #queue()}, etc.</li>
 *   <li>{@link #close()} — graceful shutdown (drains pending events, releases memory).</li>
 * </ol>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li><b>Community</b>: heap-based components, {@code StructuredTaskScope} processing loop,
 *       no ordering guarantees, no off-heap memory.</li>
 *   <li><b>Enterprise</b>: off-heap slab pools for descriptors and payloads, lock-free ring buffer
 *       queue, deterministic event ordering, zero heap allocations after {@link #start()}.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see EventBus
 * @see EventQueue
 * @see EventLoop
 * @see EventRegistry
 */
public interface EventEngine extends AutoCloseable {

    /**
     * Returns the {@link EventBus} for publish/subscribe operations.
     *
     * <p>Available after {@link #start()} returns. Behaviour is undefined if called before start.
     *
     * @return non-null event bus
     */
    EventBus bus();

    /**
     * Returns the {@link EventQueue} for durable, ordered event storage.
     *
     * <p>Community: bounded heap queue.
     * Enterprise: lock-free off-heap ring buffer (power-of-2 capacity).
     *
     * @return non-null event queue
     */
    EventQueue queue();

    /**
     * Returns the {@link EventLoop} for async event processing.
     *
     * <p>Community: {@code StructuredTaskScope}-based virtual-thread dispatcher.
     * Enterprise: lock-free single-thread loop with optional CPU affinity.
     *
     * @return non-null event loop
     */
    EventLoop loop();

    /**
     * Returns the {@link EventRegistry} for event type management.
     *
     * @return non-null event registry
     */
    EventRegistry registry();

    /**
     * Starts all components of the event engine.
     *
     * <p>This method:
     * <ul>
     *   <li>For Community: initialises data structures on the heap.</li>
     *   <li>For Enterprise: claims the events memory partition, pre-allocates all slab pools,
     *       and starts the lock-free event loop. Zero allocations are performed after this call.</li>
     * </ul>
     *
     * <p>Emits a {@code EventEngineBootstrapEvent} JFR event on completion.
     *
     * @throws EventEngineException if startup fails
     */
    void start();

    /**
     * Gracefully shuts down the event engine.
     *
     * <p>Drains pending events from the queue, stops the event loop, and releases
     * all resources (including off-heap memory for Enterprise implementations).
     *
     * <p>After this method returns, all component references ({@link #bus()}, etc.)
     * MUST NOT be used.
     */
    @Override
    void close();

    /**
     * Returns a JFR-backed snapshot of engine statistics.
     *
     * <p>This method is O(1) — it reads pre-computed atomic counters.
     * Implementations MUST NOT perform I/O or allocations here.
     *
     * @return current engine stats snapshot
     */
    EventEngineStats stats();
}

