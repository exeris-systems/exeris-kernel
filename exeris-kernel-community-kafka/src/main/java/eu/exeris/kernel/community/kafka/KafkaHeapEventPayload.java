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
 * {@link #close()} adjust an {@link AtomicInteger}, and the underlying buffer is released for
 * GC when the count reaches zero.
 *
 * <p>PERF-071: the payload now holds a {@link MemorySegment} directly (typically a zero-copy
 * slice over the Kafka consumer record's value array — see
 * {@link KafkaEventCodec#decodePayloadSegment(byte[])}). {@link #segment()} returns the held
 * segment as-is — no per-call {@link MemorySegment#ofArray(byte[])} wrapper allocation.
 *
 * @since 0.7.0
 */
final class KafkaHeapEventPayload implements EventPayload {

    private final MemorySegment segment;
    private final int length;
    private final AtomicInteger refCount;

    private KafkaHeapEventPayload(MemorySegment segment) {
        this.segment  = Objects.requireNonNull(segment, "segment");
        this.length   = Math.toIntExact(segment.byteSize());
        this.refCount = new AtomicInteger(1);
    }

    /**
     * Wraps a (typically slice-of-frame) {@link MemorySegment}; the segment becomes
     * payload-owned. Zero-copy production path: the segment is a slice over the
     * Kafka consumer record's value array — no extra allocation, no array copy.
     */
    /* default */ static KafkaHeapEventPayload wrap(MemorySegment segment) {
        return new KafkaHeapEventPayload(segment);
    }

    /**
     * Convenience wrapper for byte-array inputs (tests + legacy paths). Wraps the
     * array as a {@link MemorySegment} and delegates to {@link #wrap(MemorySegment)}.
     * The production decode path uses the segment-typed factory directly.
     */
    /* default */ static KafkaHeapEventPayload wrap(byte[] bytes) {
        return wrap(MemorySegment.ofArray(Objects.requireNonNull(bytes, "bytes")));
    }

    @Override
    public MemorySegment segment() {
        if (refCount.get() == 0) {
            throw new IllegalStateException("KafkaHeapEventPayload accessed after release");
        }
        return segment;
    }

    @Override
    public int length() {
        return length;
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
