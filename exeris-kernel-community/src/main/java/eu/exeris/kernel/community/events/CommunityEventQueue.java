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
 * <h2>Back-pressure</h2>
 * <p>When the deque is full, {@link #push} parks the calling virtual thread (safe for VTs)
 * via {@code LinkedBlockingDeque.putLast()} — structured blocking, not busy-spin.
 * This ensures publishers never silently lose events due to overflow.
 *
 * <h2>Payload Ownership</h2>
 * <p>On each {@link #push}, the queue retains the payload (increments refCount) before
 * enqueuing. On {@link #drain} or {@link #poll}, the dequeued payload is handed to
 * the caller's sink — ownership transfers; the sink must call {@code payload.close()}.
 *
 * @since 0.5.0
 */
final class CommunityEventQueue implements EventQueue {

    private final BlockingDeque<Entry> deque;
    private final int                  capacity;

    private record Entry(EventDescriptor descriptor, EventPayload payload) {}

    @SuppressWarnings("PMD.CommentDefaultAccessModifier")
    CommunityEventQueue(int capacity) {
        this.capacity = capacity;
        this.deque    = new LinkedBlockingDeque<>(capacity);
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public boolean push(EventDescriptor descriptor, EventPayload payload) {
        payload.retain();
        try {
            deque.putLast(new Entry(descriptor, payload));
            return true;
        } catch (InterruptedException ex) {
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

