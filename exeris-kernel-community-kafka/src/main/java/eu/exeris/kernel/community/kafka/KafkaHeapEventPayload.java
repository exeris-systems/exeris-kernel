/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p>Mirrors the reference-count and lifecycle shape of {@code CommunityHeapEventPayload}
 * (the package-private Community heap payload) — duplicated here so the Kafka module does not
 * depend on a package-private Community internal — but the two are not functionally identical:
 * {@link #wrap(MemorySegment)} stores the given segment as-is, with no read-only view applied,
 * so a caller holding the segment returned by {@link #segment()} can write through it; Community's
 * payload wraps its backing array as a read-only segment. The reference-count machinery is
 * preserved for symmetry with the broadcast RAII protocol — {@link #retain()} and
 * {@link #close()} adjust an {@link AtomicInteger}, and the underlying buffer is released for
 * GC when the count reaches zero.
 *
 * <p>The payload holds a {@link MemorySegment} directly (typically a zero-copy slice over the
 * Kafka consumer record's value array — see {@link KafkaEventCodec#decodePayloadSegment(byte[])}).
 * {@link #segment()} returns the held segment as-is — no per-call
 * {@link MemorySegment#ofArray(byte[])} wrapper allocation.
 *
 * <p><b>Allocation:</b> {@link #wrap(MemorySegment)} allocates only the payload instance and its
 * reference-count holder — the segment itself is not copied; {@link #segment()} and
 * {@link #length()} return already-held state with no further allocation.
 * <p><b>Thread confinement:</b> none — {@link #retain()}, {@link #close()} and {@link #refCount()}
 * mutate or read an {@link AtomicInteger} and are safe to call from any thread.
 * <p><b>Ownership:</b> the holder of the last reference releases via {@link #close()}. Every
 * segment this module wraps is heap-{@code byte[]}-backed (see
 * {@link KafkaEventCodec#decodePayloadSegment(byte[])}), so release only drops the reference
 * count — the backing array is reclaimed by ordinary GC once unreachable, not returned to a pool;
 * {@link #wrap(MemorySegment)} does not itself inspect or enforce the segment's origin.
 *
 * @since 0.7
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
