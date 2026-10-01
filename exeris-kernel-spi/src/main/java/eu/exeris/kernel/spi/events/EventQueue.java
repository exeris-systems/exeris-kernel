/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SPI: Durable event queue with back-pressure semantics.
 *
 * <h2>Unit of Enqueue</h2>
 * <p>The queue stores {@link EventDescriptor} + {@link EventPayload} as an atomic pair.
 * The queue holds a reference to the payload (incrementing its refCount on push) for as long as
 * the event waits, and passes that reference on to whoever drains it — the reference is settled
 * by the drain sink's {@link EventPayload#close()}, not by the queue.
 *
 * <p><b>Allocation:</b> allocates (a capacity-bound field at construction; a heap binding
 * allocates one link node per enqueued event and pre-allocates no storage; a native ring-buffer
 * binding pre-allocates storage sized to {@link #capacity()} at construction)
 * <p><b>Thread confinement:</b> virtual-thread-safe — {@link #push} is the publisher side and
 * parks the calling virtual thread when a blocking-mode queue is full, without pinning its
 * carrier; {@link #poll} and {@link #drain} are the {@link EventLoop}'s side of the same queue
 * <p><b>Ownership:</b> {@link #push} transfers the payload to the queue, which holds a reference
 * until the event is drained; {@link #poll} and {@link #drain} pass that same reference on to the
 * sink, which owes the matching {@link EventPayload#close()}
 *
 * @implSpec An implementation retains the payload on a successful {@link #push}, so a queued
 *           event's backing memory outlives the publisher's own reference, and hands that
 *           reference to the sink when the event is drained — it does not close it itself. A push
 *           that does not succeed (full, or interrupted) releases whatever it retained, leaving
 *           the caller's reference count exactly as it was.
 * @implNote The Community binding is a bounded heap queue over {@code LinkedBlockingDeque}; a
 *           native binding is a lock-free off-heap ring buffer (power-of-2 capacity, VarHandle
 *           CAS on head/tail) that stores the descriptor and payload references in two
 *           consecutive slots.
 * @since 0.5
 */
public interface EventQueue {

    /**
     * Enqueues an event at the tail, transferring ownership of {@code payload} to the queue for
     * as long as the event is held.
     *
     * @param descriptor routing metadata (non-null)
     * @param payload    RAII payload — ownership transferred to queue on success (non-null)
     * @return {@code true} if accepted; {@code false} if the queue is at capacity and the
     *         implementation refuses rather than blocks
     * @implSpec The queue increments the payload's reference count before enqueuing and releases
     *           that reference when the event is drained. A push that returns {@code false}, or
     *           that unwinds on interruption, releases its own retain so the caller's reference
     *           count is exactly what it was before the call.
     * @apiNote After a push that returns {@code true} the caller must not close the payload — the
     *          queue owns it until drain. After a push that returns {@code false} the caller still
     *          owns the payload and is responsible for closing or re-offering it; discarding it
     *          without closing leaks the backing slab on an off-heap binding.
     * @implNote In blocking mode the Community binding parks the calling virtual thread on a full
     *           queue and never returns {@code false}; in fail-fast mode it returns {@code false},
     *           which the engine translates into {@code EX-EVENT-6002}.
     */
    boolean push(EventDescriptor descriptor, EventPayload payload);

    /**
     * Removes the head event and hands its two halves back separately — the descriptor as the
     * return value, the payload through {@code payloadSink}. Never blocks.
     *
     * @param payloadSink receives the payload paired with the returned descriptor (non-null)
     * @return the head descriptor, or {@code null} if the queue is empty — in which case
     *         {@code payloadSink} is not invoked
     * @implSpec The sink is invoked exactly once per non-{@code null} return, before the method
     *           returns, and ownership of the payload passes to it at that point.
     * @apiNote The sink owes the matching {@link EventPayload#close()} — the reference the queue
     *          took at {@link #push} is the one the sink now holds.
     */
    EventDescriptor poll(Consumer<EventPayload> payloadSink);

    /**
     * Removes up to {@code maxItems} events from the head in one pass, handing each
     * (descriptor, payload) pair to {@code sink} in queue order.
     *
     * @param sink     receives each (descriptor, payload) pair (non-null)
     * @param maxItems maximum events to drain (must be &gt; 0)
     * @return number of events actually drained; {@code 0} when the queue was empty, and less
     *         than {@code maxItems} when it drained dry before the budget was spent
     * @implSpec Indices are consumed head-first and the sink is invoked once per drained event,
     *           each invocation transferring ownership of that payload; an implementation stops
     *           at the first empty slot rather than waiting for more work.
     * @apiNote The sink owes {@link EventPayload#close()} on every payload it is handed,
     *          including those handed to it before an exception unwound the drain.
     * @implNote A native binding advances the head pointer in a single batch operation to
     *           minimise per-element overhead.
     */
    int drain(BiConsumer<EventDescriptor, EventPayload> sink, int maxItems);

    /**
     * Reports how many events are currently enqueued and not yet drained — the numerator of the
     * back-pressure ratio operators watch.
     *
     * @return the number of events waiting, between {@code 0} and {@link #capacity()}; a
     *         concurrently mutated queue may have moved on by the time the caller reads it
     */
    int size();

    /**
     * Reports the fixed bound on how many events may wait at once — the point at which
     * {@link #push} starts to block or refuse.
     *
     * @return the queue's maximum depth; constant for the life of the queue
     */
    int capacity();

    /**
     * Reports whether the queue currently holds no events.
     *
     * @return {@code true} when {@link #size()} is {@code 0}
     * @apiNote A racy observation: work may be enqueued the instant after this returns
     *          {@code true}. Use it for diagnostics, not to decide that a drain loop may exit.
     */
    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Reports whether the queue has reached {@link #capacity()} and the next {@link #push} would
     * therefore block or be refused.
     *
     * @return {@code true} when {@link #size()} has reached {@link #capacity()}
     * @apiNote A racy observation, and not a substitute for checking what {@link #push} returns:
     *          a slot may free up, or be taken, between this call and the push.
     */
    default boolean isFull() {
        return size() >= capacity();
    }
}
