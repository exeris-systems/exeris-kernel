/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <h2>Back-pressure (since 0.7.0 — EVENT-205b)</h2>
 * <p>Two operating modes selected by the {@code busPublishFailFast} bit on
 * {@link eu.exeris.kernel.spi.events.EventEngineConfig}:
 * <ul>
 *   <li><b>Blocking (default — backward-compat).</b> {@link #push} parks the calling virtual
 *       thread via {@link LinkedBlockingDeque#putLast(Object)} when the queue is full —
 *       safe for VTs (no carrier pinning), publishers never lose events.</li>
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
 * @since 0.5.0
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

    @Override
    public EventDescriptor poll(Consumer<EventPayload> payloadSink) {
        Entry entry = deque.pollFirst();
        if (entry == null) {
            return null;
        }
        payloadSink.accept(entry.payload());
        return entry.descriptor();
    }

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

    @Override
    public int size() {
        return deque.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }
}

