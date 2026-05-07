/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventPayload;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heap-backed {@link EventPayload} carrying decoded Kafka record bytes for delivery to local
 * subscribers.
 *
 * <p>Functionally identical to {@code CommunityHeapEventPayload}; duplicated here so the Kafka
 * module does not depend on a package-private Community internal. The reference-count machinery
 * is preserved for symmetry with the broadcast RAII protocol — {@link #retain()} and
 * {@link #close()} adjust an {@link AtomicInteger}, and the underlying {@code byte[]} is GC'd
 * when the count reaches zero.
 *
 * @since 0.7.0
 */
final class KafkaHeapEventPayload implements EventPayload {

    private final byte[] bytes;
    private final AtomicInteger refCount;

    private KafkaHeapEventPayload(byte[] bytes) {
        this.bytes    = Objects.requireNonNull(bytes, "bytes");
        this.refCount = new AtomicInteger(1);
    }

    /** Wraps an already-allocated heap array; the array becomes payload-owned. */
    /* default */ static KafkaHeapEventPayload wrap(byte[] bytes) {
        return new KafkaHeapEventPayload(bytes);
    }

    @Override
    public MemorySegment segment() {
        if (refCount.get() == 0) {
            throw new IllegalStateException("KafkaHeapEventPayload accessed after release");
        }
        return MemorySegment.ofArray(bytes);
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public void retain() {
        int updated = refCount.updateAndGet(v -> v == 0 ? 0 : v + 1);
        if (updated == 0) {
            throw new IllegalStateException("KafkaHeapEventPayload retain after release");
        }
    }

    @Override
    public void close() {
        refCount.updateAndGet(v -> v == 0 ? 0 : v - 1);
    }

    @Override
    public int refCount() {
        return refCount.get();
    }

    @Override
    public boolean isAlive() {
        return refCount.get() > 0;
    }
}
