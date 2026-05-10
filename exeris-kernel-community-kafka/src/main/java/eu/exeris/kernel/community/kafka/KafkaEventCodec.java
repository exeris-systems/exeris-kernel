/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Wire codec for the {@code (EventDescriptor, EventPayload)} pair carried over a Kafka topic.
 *
 * <h2>Frame Layout (big-endian)</h2>
 * <pre>
 *   Offset  Size  Field
 *      0     8    eventIdHigh           (long)
 *      8     8    eventIdLow            (long)
 *     16     8    streamIdHigh          (long)
 *     24     8    streamIdLow           (long)
 *     32     4    eventTypeOrdinal      (int)
 *     36     4    flags                 (int)
 *     40     8    occurredAtEpochMs     (long)
 *     48     N    payload bytes (verbatim copy of {@link EventPayload#segment()})
 * </pre>
 * <p>Size: {@link #HEADER_SIZE} + {@code payload.length()}. Big-endian to match
 * {@link ByteBuffer#order(ByteOrder)} default and the existing Postgres outbox encoder.
 *
 * <h2>Allocation Discipline</h2>
 * <p>Encoding allocates a fresh {@code byte[]} sized exactly {@code HEADER_SIZE + payload.length()}
 * — Kafka's {@code ProducerRecord} owns this buffer until the broker acknowledges. Decoding
 * unwraps the consumer-supplied {@code byte[]} and copies the payload tail into a fresh
 * heap-backed {@link EventPayload} delivered to handlers; the descriptor reads primitives
 * directly off the buffer with zero allocation.
 *
 * @since 0.7.0
 */
final class KafkaEventCodec {

    /** Fixed-size header preceding the payload bytes. */
    /* default */ static final int HEADER_SIZE = 48;

    // ── MemoryLayout — single source of truth for header field offsets ───────
    // BIG_ENDIAN matches the existing Kafka wire format and the Postgres outbox encoder.
    // *_UNALIGNED variants are required because heap-backed segments produced by
    // MemorySegment.ofArray(byte[]) carry a 1-byte alignment constraint; the default
    // JAVA_LONG / JAVA_INT layouts demand 8 / 4-byte alignment and would throw at runtime.
    // VarHandles are static finals — initialised once at class-load, zero hot-path overhead.
    private static final ValueLayout.OfLong  LONG_BE_UNALIGNED =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt   INT_BE_UNALIGNED  =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    // Field-name constants — single source of truth for layout + VarHandle path lookup.
    private static final String F_EVENT_ID_HIGH      = "eventIdHigh";
    private static final String F_EVENT_ID_LOW       = "eventIdLow";
    private static final String F_STREAM_ID_HIGH     = "streamIdHigh";
    private static final String F_STREAM_ID_LOW      = "streamIdLow";
    private static final String F_EVENT_TYPE_ORDINAL = "eventTypeOrdinal";
    private static final String F_FLAGS              = "flags";
    private static final String F_OCCURRED_AT        = "occurredAtEpochMs";

    private static final MemoryLayout HEADER_LAYOUT = MemoryLayout.structLayout(
            LONG_BE_UNALIGNED.withName(F_EVENT_ID_HIGH),
            LONG_BE_UNALIGNED.withName(F_EVENT_ID_LOW),
            LONG_BE_UNALIGNED.withName(F_STREAM_ID_HIGH),
            LONG_BE_UNALIGNED.withName(F_STREAM_ID_LOW),
            INT_BE_UNALIGNED.withName(F_EVENT_TYPE_ORDINAL),
            INT_BE_UNALIGNED.withName(F_FLAGS),
            LONG_BE_UNALIGNED.withName(F_OCCURRED_AT)
    );

    private static final VarHandle VH_EVENT_ID_HIGH       =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_EVENT_ID_HIGH));
    private static final VarHandle VH_EVENT_ID_LOW        =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_EVENT_ID_LOW));
    private static final VarHandle VH_STREAM_ID_HIGH      =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_STREAM_ID_HIGH));
    private static final VarHandle VH_STREAM_ID_LOW       =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_STREAM_ID_LOW));
    private static final VarHandle VH_EVENT_TYPE_ORDINAL  =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_EVENT_TYPE_ORDINAL));
    private static final VarHandle VH_FLAGS               =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_FLAGS));
    private static final VarHandle VH_OCCURRED_AT         =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_OCCURRED_AT));

    // Separate 16-byte layout for the partition-key UUID so its offsets start at 0
    // (the header layout's stream-id fields live at offsets 16/24, not 0/8).
    private static final MemoryLayout STREAM_KEY_LAYOUT = MemoryLayout.structLayout(
            LONG_BE_UNALIGNED.withName(F_STREAM_ID_HIGH),
            LONG_BE_UNALIGNED.withName(F_STREAM_ID_LOW)
    );
    private static final VarHandle VH_KEY_STREAM_ID_HIGH  =
            STREAM_KEY_LAYOUT.varHandle(PathElement.groupElement(F_STREAM_ID_HIGH));
    private static final VarHandle VH_KEY_STREAM_ID_LOW   =
            STREAM_KEY_LAYOUT.varHandle(PathElement.groupElement(F_STREAM_ID_LOW));

    private KafkaEventCodec() {
        // utility
    }

    /**
     * Encodes {@code (descriptor, payload)} into a fresh {@code byte[]} suitable for a
     * Kafka {@code ProducerRecord} value. Header bytes are stamped via VarHandle directly
     * onto the wrapped {@link MemorySegment} — no transient {@link java.nio.ByteBuffer}
     * wrapper allocation on the publish hot path.
     */
    /* default */ static byte[] encode(EventDescriptor descriptor, EventPayload payload) {
        int payloadLength = payload.length();
        byte[] frame = new byte[HEADER_SIZE + payloadLength];
        MemorySegment dst = MemorySegment.ofArray(frame);
        VH_EVENT_ID_HIGH.set(dst,      0L, descriptor.eventIdHigh());
        VH_EVENT_ID_LOW.set(dst,       0L, descriptor.eventIdLow());
        VH_STREAM_ID_HIGH.set(dst,     0L, descriptor.streamIdHigh());
        VH_STREAM_ID_LOW.set(dst,      0L, descriptor.streamIdLow());
        VH_EVENT_TYPE_ORDINAL.set(dst, 0L, descriptor.eventTypeOrdinal());
        VH_FLAGS.set(dst,              0L, descriptor.flags());
        VH_OCCURRED_AT.set(dst,        0L, descriptor.occurredAtEpochMs());
        if (payloadLength > 0) {
            // Copy payload bytes directly segment-to-segment — works for heap- and
            // native-backed source segments without a temporary toArray() allocation.
            MemorySegment.copy(payload.segment(), 0L, dst, HEADER_SIZE, payloadLength);
        }
        return frame;
    }

    /**
     * Decodes the descriptor portion of a Kafka {@code ProducerRecord} value frame.
     * The payload tail is exposed via {@link #decodePayloadBytes(byte[])} as a fresh
     * heap-backed array sized to {@code frame.length - HEADER_SIZE}.
     *
     * @throws IllegalArgumentException if {@code frame.length < HEADER_SIZE}
     */
    /* default */ static EventDescriptor decodeDescriptor(byte[] frame) {
        if (frame.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Kafka frame is shorter than the fixed header (" + HEADER_SIZE
                    + " bytes): got " + frame.length);
        }
        MemorySegment src = MemorySegment.ofArray(frame);
        return new EventDescriptor(
                (long) VH_EVENT_ID_HIGH.get(src,      0L),
                (long) VH_EVENT_ID_LOW.get(src,       0L),
                (long) VH_STREAM_ID_HIGH.get(src,     0L),
                (long) VH_STREAM_ID_LOW.get(src,      0L),
                (int)  VH_EVENT_TYPE_ORDINAL.get(src, 0L),
                (int)  VH_FLAGS.get(src,              0L),
                (long) VH_OCCURRED_AT.get(src,        0L));
    }

    /**
     * Returns the payload bytes by copying the tail of the frame into a fresh heap array.
     * The returned array can be wrapped in a {@code CommunityHeapEventPayload} for handler delivery.
     */
    /* default */ static byte[] decodePayloadBytes(byte[] frame) {
        if (frame.length == HEADER_SIZE) {
            return new byte[0];
        }
        int payloadLength = frame.length - HEADER_SIZE;
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, HEADER_SIZE, payload, 0, payloadLength);
        return payload;
    }

    /**
     * Encodes the 16-byte big-endian stream UUID used as the Kafka partition key.
     * Same stream → same key bytes → same partition (per Kafka's default partitioner) →
     * ordered consumer delivery for a given saga / aggregate. Uses the shared
     * {@link VarHandle} layout so no transient {@link java.nio.ByteBuffer} wrapper is
     * allocated on the publish hot path.
     */
    /* default */ static byte[] streamKey(EventDescriptor descriptor) {
        byte[] key = new byte[Long.BYTES * 2];
        MemorySegment dst = MemorySegment.ofArray(key);
        VH_KEY_STREAM_ID_HIGH.set(dst, 0L, descriptor.streamIdHigh());
        VH_KEY_STREAM_ID_LOW.set(dst,  0L, descriptor.streamIdLow());
        return key;
    }
}
