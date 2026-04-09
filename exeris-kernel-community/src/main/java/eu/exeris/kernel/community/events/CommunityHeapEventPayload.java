/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventPayload;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heap-backed EventPayload used by Community outbox adapters.
 */
final class CommunityHeapEventPayload implements EventPayload {

    private final byte[] bytes;
    private final MemorySegment segment;
    private final AtomicInteger refCount;

    /* default */ CommunityHeapEventPayload(byte[] bytes) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.segment = MemorySegment.ofArray(this.bytes).asReadOnly();
        this.refCount = new AtomicInteger(1);
    }

    /**
     * Package-private: takes ownership of the supplied array without copying.
     * Caller MUST NOT retain or modify the array after this call.
     */
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    /* default */ static CommunityHeapEventPayload wrap(byte[] bytes) {
        return new CommunityHeapEventPayload(bytes, false);
    }

    @SuppressWarnings({"PMD.UnusedFormalParameter", "unused"})
    private CommunityHeapEventPayload(byte[] bytes, boolean ignored) {
        this.bytes = bytes;
        this.segment = MemorySegment.ofArray(this.bytes).asReadOnly();
        this.refCount = new AtomicInteger(1);
    }

    @Override
    public MemorySegment segment() {
        if (!isAlive()) {
            throw new IllegalStateException("payload already released");
        }
        return segment;
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public void retain() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("payload already released");
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    @Override
    public void close() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                return;
            }
            if (refCount.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    @Override
    public int refCount() {
        return Math.max(0, refCount.get());
    }

    @Override
    public boolean isAlive() {
        return refCount.get() > 0;
    }

    /* default */ static byte[] copyBytes(EventPayload payload) {
        if (payload == null || payload.length() == 0) {
            return new byte[0];
        }
        return payload.segment().toArray(ValueLayout.JAVA_BYTE);
    }
}
