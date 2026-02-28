/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SPI: Durable event queue with back-pressure semantics.
 *
 * <h2>Unit of Enqueue</h2>
 * <p>The queue stores {@link EventDescriptor} + {@link EventPayload} as an atomic pair.
 * The queue holds a reference to the payload (incrementing its refCount on push)
 * and releases it (decrementing refCount) when the event is drained and processed.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li><b>Standard</b>: bounded heap-based queue.
 *       Back-pressure: parks the calling virtual thread when full.</li>
 *   <li><b>High-performance native</b>: lock-free off-heap ring buffer (power-of-2 capacity,
 *       VarHandle CAS on head/tail). Stores descriptor and payload references as
 *       two consecutive slots. Returns {@code false} if full.</li>
 * </ul>
 *
 * @since 0.5.0
 */
public interface EventQueue {

    /**
     * Pushes an event (descriptor + payload) to the tail of the queue.
     *
     * <p><b>Ownership transfer:</b> The queue retains the payload (increments refCount)
     * for the duration it holds the event. Callers MUST NOT close the payload after
     * a successful push — the queue owns it until drain.
     *
     * <p>Standard implementations park the calling virtual thread if the queue is full (safe — VTs are cheap).
     * High-performance native implementations use CAS-based non-blocking push and return
     * {@code false} immediately if full.
     *
     * @param descriptor routing metadata (non-null)
     * @param payload    RAII payload — ownership transferred to queue on success (non-null)
     * @return {@code true} if accepted; {@code false} if full and non-blocking
     */
    boolean push(EventDescriptor descriptor, EventPayload payload);

    /**
     * Polls the head of the queue, returning the descriptor or {@code null} if empty.
     *
     * <p>Ownership of the corresponding {@link EventPayload} is transferred to the caller
     * via the {@code payloadSink} callback.
     *
     * <p>Never blocks.
     *
     * @param payloadSink receives the payload paired with the returned descriptor (non-null)
     * @return the head descriptor, or {@code null} if the queue is empty
     */
    EventDescriptor poll(Consumer<EventPayload> payloadSink);

    /**
     * Drains up to {@code maxItems} events into the given {@code sink}.
     *
     * <p>Each call to {@code sink} receives a (descriptor, payload) pair.
     * Ownership of each {@link EventPayload} is transferred to the sink —
     * the sink is responsible for calling {@code payload.close()}.
     *
     * <p>High-performance native implementations advance the head pointer in a single
     * batch operation to minimise per-element overhead.
     *
     * @param sink     receives each (descriptor, payload) pair (non-null)
     * @param maxItems maximum events to drain (must be &gt; 0)
     * @return number of events actually drained (0 if empty)
     */
    int drain(BiConsumer<EventDescriptor, EventPayload> sink, int maxItems);

    /** Current number of events waiting in the queue. */
    int size();

    /** Maximum capacity (fixed power-of-2 for Enterprise, configurable for Community). */
    int capacity();

    default boolean isEmpty() {
        return size() == 0;
    }

    default boolean isFull() {
        return size() >= capacity();
    }
}
