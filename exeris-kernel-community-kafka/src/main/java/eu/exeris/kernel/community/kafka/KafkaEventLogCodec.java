/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * Wire codec for the durable event-log frame (ADR-049): the same {@code (EventDescriptor, payload)}
 * pair as {@link KafkaEventCodec} plus a leading 8-byte {@code committedSequence} — the 1-based
 * per-{@code StreamId} head the appender stamps and the reader replays.
 *
 * <h2>Frame Layout (big-endian)</h2>
 * <pre>
 *   Offset  Size  Field
 *      0     8    committedSequence     (long, 1-based per-stream head)
 *      8     8    eventIdHigh           (long)
 *     16     8    eventIdLow            (long)
 *     24     8    streamIdHigh          (long)
 *     32     8    streamIdLow           (long)
 *     40     4    eventTypeOrdinal      (int)
 *     44     4    flags                 (int)
 *     48     8    occurredAtEpochMs     (long)
 *     56     N    payload bytes
 * </pre>
 * <p>The partition key is the 16-byte stream UUID from {@link KafkaEventCodec#streamKey} — same
 * stream → same key → same partition → offset order == append order == {@code committedSequence}
 * order. Big-endian, unaligned VarHandles (heap-backed segments carry a 1-byte alignment).
 *
 * @since 0.10
 */
final class KafkaEventLogCodec {

    /** Fixed-size header preceding the payload bytes. */
    /* default */ static final int HEADER_SIZE = 56;

    private static final ValueLayout.OfLong LONG_BE_UNALIGNED =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt INT_BE_UNALIGNED =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private static final String F_COMMITTED_SEQUENCE = "committedSequence";
    private static final String F_EVENT_ID_HIGH      = "eventIdHigh";
    private static final String F_EVENT_ID_LOW       = "eventIdLow";
    private static final String F_STREAM_ID_HIGH     = "streamIdHigh";
    private static final String F_STREAM_ID_LOW      = "streamIdLow";
    private static final String F_EVENT_TYPE_ORDINAL = "eventTypeOrdinal";
    private static final String F_FLAGS              = "flags";
    private static final String F_OCCURRED_AT        = "occurredAtEpochMs";

    private static final MemoryLayout HEADER_LAYOUT = MemoryLayout.structLayout(
            LONG_BE_UNALIGNED.withName(F_COMMITTED_SEQUENCE),
            LONG_BE_UNALIGNED.withName(F_EVENT_ID_HIGH),
            LONG_BE_UNALIGNED.withName(F_EVENT_ID_LOW),
            LONG_BE_UNALIGNED.withName(F_STREAM_ID_HIGH),
            LONG_BE_UNALIGNED.withName(F_STREAM_ID_LOW),
            INT_BE_UNALIGNED.withName(F_EVENT_TYPE_ORDINAL),
            INT_BE_UNALIGNED.withName(F_FLAGS),
            LONG_BE_UNALIGNED.withName(F_OCCURRED_AT)
    );

    private static final VarHandle VH_COMMITTED_SEQUENCE =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_COMMITTED_SEQUENCE));
    private static final VarHandle VH_EVENT_ID_HIGH =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_EVENT_ID_HIGH));
    private static final VarHandle VH_EVENT_ID_LOW =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_EVENT_ID_LOW));
    private static final VarHandle VH_STREAM_ID_HIGH =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_STREAM_ID_HIGH));
    private static final VarHandle VH_STREAM_ID_LOW =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_STREAM_ID_LOW));
    private static final VarHandle VH_EVENT_TYPE_ORDINAL =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_EVENT_TYPE_ORDINAL));
    private static final VarHandle VH_FLAGS =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_FLAGS));
    private static final VarHandle VH_OCCURRED_AT =
            HEADER_LAYOUT.varHandle(PathElement.groupElement(F_OCCURRED_AT));

    private KafkaEventLogCodec() {
        // utility
    }

    /** Encodes {@code (committedSequence, descriptor, payload)} into a fresh log-frame byte[]. */
    /* default */ static byte[] encode(long committedSequence, EventDescriptor descriptor, EventPayload payload) {
        int payloadLength = payload.length();
        byte[] frame = new byte[HEADER_SIZE + payloadLength];
        MemorySegment dst = MemorySegment.ofArray(frame);
        VH_COMMITTED_SEQUENCE.set(dst, 0L, committedSequence);
        VH_EVENT_ID_HIGH.set(dst,      0L, descriptor.eventIdHigh());
        VH_EVENT_ID_LOW.set(dst,       0L, descriptor.eventIdLow());
        VH_STREAM_ID_HIGH.set(dst,     0L, descriptor.streamIdHigh());
        VH_STREAM_ID_LOW.set(dst,      0L, descriptor.streamIdLow());
        VH_EVENT_TYPE_ORDINAL.set(dst, 0L, descriptor.eventTypeOrdinal());
        VH_FLAGS.set(dst,              0L, descriptor.flags());
        VH_OCCURRED_AT.set(dst,        0L, descriptor.occurredAtEpochMs());
        if (payloadLength > 0) {
            MemorySegment.copy(payload.segment(), 0L, dst, HEADER_SIZE, payloadLength);
        }
        return frame;
    }

    /* default */ static long decodeSequence(byte[] frame) {
        requireHeader(frame);
        return (long) VH_COMMITTED_SEQUENCE.get(MemorySegment.ofArray(frame), 0L);
    }

    /* default */ static long decodeStreamIdHigh(byte[] frame) {
        requireHeader(frame);
        return (long) VH_STREAM_ID_HIGH.get(MemorySegment.ofArray(frame), 0L);
    }

    /* default */ static long decodeStreamIdLow(byte[] frame) {
        requireHeader(frame);
        return (long) VH_STREAM_ID_LOW.get(MemorySegment.ofArray(frame), 0L);
    }

    /* default */ static EventDescriptor decodeDescriptor(byte[] frame) {
        requireHeader(frame);
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

    /** Returns the payload tail as a zero-copy {@link MemorySegment} slice over {@code frame}. */
    /* default */ static MemorySegment decodePayloadSegment(byte[] frame) {
        requireHeader(frame);
        int payloadLength = frame.length - HEADER_SIZE;
        if (payloadLength == 0) {
            return MemorySegment.NULL.asSlice(0L, 0L);
        }
        return MemorySegment.ofArray(frame).asSlice(HEADER_SIZE, payloadLength);
    }

    private static void requireHeader(byte[] frame) {
        if (frame.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Kafka event-log frame is shorter than the fixed header (" + HEADER_SIZE
                    + " bytes): got " + frame.length);
        }
    }
}
