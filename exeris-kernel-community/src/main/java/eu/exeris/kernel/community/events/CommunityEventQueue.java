/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventQueue;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Community: heap-backed, bounded {@link EventQueue} using {@link LinkedBlockingDeque}.
 *
 * <h2>Back-pressure (since 0.7.0)</h2>
 * <p>Two operating modes selected by the {@code busPublishFailFast} bit on
 * {@link eu.exeris.kernel.spi.events.EventEngineConfig}:
 * <ul>
 *   <li><b>Blocking (default — backward-compat).</b> {@link #push} parks the calling virtual
 *       thread via {@link LinkedBlockingDeque#putLast(Object)} when the queue is full —
 *       safe for VTs (no carrier pinning); the only way this mode loses an event is a
 *       {@code push}-caller interrupted while parked, in which case {@link #push} closes the
 *       payload, restores the interrupt status and returns {@code false}.</li>
 *   <li><b>Fail-fast (since 0.7.0).</b> {@link #push} uses
 *       {@link LinkedBlockingDeque#offerLast(Object)} and returns {@code false} when the
 *       queue is at capacity. {@link CommunityEventEngine} then translates {@code false}
 *       into {@link eu.exeris.kernel.spi.exceptions.events.EventBusException#publishOverflow(String, long, long)
 *       EventBusException.publishOverflow} carrying {@code rawArgs == [eventType, queueDepth, queueCapacity]}.</li>
 * </ul>
 *
 * <h2>Payload Ownership</h2>
 * <p>On each {@link #push}, the queue retains the payload (increments refCount) before
 * enqueuing. On {@link #drain} or {@link #poll}, the dequeued payload is handed to
 * the caller's sink — ownership transfers; the sink must call {@code payload.close()}.
 * On a failed push (full or interrupted), the queue closes its own retain so the
 * caller's reference count is unchanged.
 *
 * <p><b>Allocation:</b> allocates one {@code Entry} record per {@link #push}, released for GC
 * once {@link #drain} or {@link #poll} removes it.
 * <p><b>Thread confinement:</b> none — {@link LinkedBlockingDeque} is safe for concurrent
 * pushers and a concurrent drainer/poller with no external synchronization.
 * <p><b>Ownership:</b> see <i>Payload Ownership</i> above.
 *
 * @since 0.5
 */
final class CommunityEventQueue implements EventQueue {

    private final BlockingDeque<Entry> deque;
    private final int                  capacity;
    private final boolean              failFastOnFull;

    private record Entry(EventDescriptor descriptor, EventPayload payload) {}

    /* default */ CommunityEventQueue(int capacity) {
        this(capacity, false);
    }

    /* default */ CommunityEventQueue(int capacity, boolean failFastOnFull) {
        this.capacity       = capacity;
        this.deque          = new LinkedBlockingDeque<>(capacity);
        this.failFastOnFull = failFastOnFull;
    }

    /**
     * Retains {@code payload} and appends the (descriptor, payload) pair to the tail of the
     * backing {@link LinkedBlockingDeque}, using {@code putLast} (blocking) or
     * {@code offerLast} (non-blocking) depending on {@code failFastOnFull} — see the type-level
     * <i>Back-pressure</i> section for the two modes' semantics.
     *
     * @param descriptor routing metadata (non-null)
     * @param payload    the payload; retained on entry, ownership transfers to this queue on a
     *                   {@code true} return (non-null)
     * @return {@code true} if accepted; {@code false} only in fail-fast mode when the queue was
     *         at capacity, or in blocking mode if the calling thread was interrupted while
     *         parked
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public boolean push(EventDescriptor descriptor, EventPayload payload) {
        payload.retain();
        Entry entry = new Entry(descriptor, payload);
        try {
            if (failFastOnFull) {
                if (!deque.offerLast(entry)) {
                    payload.close();
                    return false;
                }
                return true;
            }
            deque.putLast(entry);
            return true;
        } catch (InterruptedException _) {
            payload.close();
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException ex) {
            payload.close();
            throw ex;
        }
    }

    /**
     * Removes and returns the head entry, handing its payload to {@code payloadSink} — never
     * blocks, since {@link LinkedBlockingDeque#pollFirst()} returns {@code null} on an empty
     * deque rather than waiting.
     *
     * @param payloadSink receives the head entry's payload (non-null)
     * @return the head descriptor, or {@code null} if the queue was empty
     */
    @Override
    public EventDescriptor poll(Consumer<EventPayload> payloadSink) {
        Entry entry = deque.pollFirst();
        if (entry == null) {
            return null;
        }
        payloadSink.accept(entry.payload());
        return entry.descriptor();
    }

    /**
     * Polls up to {@code maxItems} entries from the head, one {@link LinkedBlockingDeque}
     * {@code pollFirst()} at a time, handing each pair to {@code sink} as it is removed and
     * stopping early the first time the deque comes back empty.
     *
     * @param sink     receives each (descriptor, payload) pair as it is drained (non-null)
     * @param maxItems maximum entries to drain (must be greater than 0)
     * @return the number of entries actually drained; {@code 0} if the queue was empty
     */
    @Override
    public int drain(BiConsumer<EventDescriptor, EventPayload> sink, int maxItems) {
        int drained = 0;
        while (drained < maxItems) {
            Entry entry = deque.pollFirst();
            if (entry == null) {
                break;
            }
            sink.accept(entry.descriptor(), entry.payload());
            drained++;
        }
        return drained;
    }

    /**
     * Returns the current number of entries in the backing deque.
     *
     * @return the current size
     */
    @Override
    public int size() {
        return deque.size();
    }

    /**
     * Returns the fixed capacity this queue was constructed with.
     *
     * @return the capacity supplied to the constructor
     */
    @Override
    public int capacity() {
        return capacity;
    }
}

