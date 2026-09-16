/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p><b>Allocation:</b> allocates (every component, and on an off-heap binding the events memory
 * partition and all slab pools, at {@link #start()}); a native binding allocates nothing on the
 * heap once {@code start()} has returned
 * <p><b>Ownership:</b> the engine owns its four components for its whole lifetime; the caller
 * that created it owes the {@link #close()}, and no component reference obtained from it survives
 * that call
 *
 * @implSpec Component accessors return the same instance for the life of a started engine, and
 *           {@link #close()} drains what is pending before releasing resources — an engine does
 *           not discard queued events on shutdown.
 * @implNote The Community binding composes heap-based components behind a structured-scope
 *           processing loop, with no ordering guarantee and no off-heap memory. The Enterprise
 *           binding composes off-heap slab pools for descriptors and payloads behind a lock-free
 *           ring-buffer queue with deterministic ordering.
 * @since 0.5
 * @see EventBus
 * @see EventQueue
 * @see EventLoop
 * @see EventRegistry
 */
public interface EventEngine extends AutoCloseable {

    /**
     * Exposes the publish/subscribe face of this engine.
     *
     * @return the engine's bus; never {@code null}
     * @apiNote Valid once {@link #start()} has returned; the behaviour of a bus obtained before
     *          start is undefined.
     */
    EventBus bus();

    /**
     * Exposes the back-pressure buffer that sits between publishers and the processing loop.
     *
     * @return the engine's queue; never {@code null}
     * @implNote Community: a bounded heap queue. Enterprise: a lock-free off-heap ring buffer of
     *           power-of-2 capacity.
     */
    EventQueue queue();

    /**
     * Exposes the loop that drains {@link #queue()} and drives the registered batch processors.
     *
     * @return the engine's loop; never {@code null}
     * @implNote Community: a structured-scope virtual-thread dispatcher. Enterprise: a lock-free
     *           single-thread loop with optional CPU affinity.
     */
    EventLoop loop();

    /**
     * Exposes the type system that maps event names to the integer ordinals every other component
     * routes on.
     *
     * @return the engine's registry; never {@code null}
     */
    EventRegistry registry();

    /**
     * Brings every component to a state where events may be published and processed — the point
     * before which the accessors above return nothing usable and after which an off-heap binding
     * no longer allocates.
     *
     * @throws EventEngineException {@code EX-EVENT-6001} if startup fails, unless the binding
     *         raises a more specific {@code EX-EVENT-*} code
     * @implSpec A successful start is not itself recorded — only a start that fails raises
     *           {@code EX-EVENT-6001}, unless the binding raises a more specific
     *           {@code EX-EVENT-*} code.
     * @implNote Community initialises its data structures on the heap. Enterprise claims the
     *           events memory partition, pre-allocates every slab pool and starts the lock-free
     *           event loop — after which it performs no further allocation.
     */
    void start();

    /**
     * Shuts the engine down without losing what is already queued, and gives back everything
     * {@link #start()} claimed.
     *
     * @implSpec Drains the pending events from the queue before stopping the loop, then releases
     *           all resources, off-heap memory included. Shutdown is a drain, not a discard.
     * @apiNote No component reference obtained from this engine — {@link #bus()}, {@link #queue()},
     *          {@link #loop()}, {@link #registry()} — may be used after this returns.
     */
    @Override
    void close();

    /**
     * Samples the engine's counters as a consistent snapshot, cheap enough to poll from a
     * telemetry path.
     *
     * @return the counters as of this call; a subsequent call returns a different snapshot rather
     *         than mutating this one
     * @implSpec O(1) — reads pre-computed counters. Performs no I/O and no allocation beyond the
     *           returned record, so a telemetry loop can call it at frequency.
     */
    EventEngineStats stats();
}

