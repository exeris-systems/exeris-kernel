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

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
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

    private KafkaEventCodec() {
        // utility
    }

    /**
     * Encodes {@code (descriptor, payload)} into a fresh {@code byte[]} suitable for a
     * Kafka {@code ProducerRecord} value.
     */
    /* default */ static byte[] encode(EventDescriptor descriptor, EventPayload payload) {
        int payloadLength = payload.length();
        byte[] frame = new byte[HEADER_SIZE + payloadLength];
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(descriptor.eventIdHigh());
        buffer.putLong(descriptor.eventIdLow());
        buffer.putLong(descriptor.streamIdHigh());
        buffer.putLong(descriptor.streamIdLow());
        buffer.putInt(descriptor.eventTypeOrdinal());
        buffer.putInt(descriptor.flags());
        buffer.putLong(descriptor.occurredAtEpochMs());
        if (payloadLength > 0) {
            MemorySegment src = payload.segment();
            // MemorySegment.asByteBuffer is not always available for off-heap segments without a
            // wrapping arena; copy via toArray which works for both heap- and native-backed segments.
            byte[] payloadBytes = src.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            System.arraycopy(payloadBytes, 0, frame, HEADER_SIZE, payloadLength);
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
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        long eventIdHigh    = buffer.getLong();
        long eventIdLow     = buffer.getLong();
        long streamIdHigh   = buffer.getLong();
        long streamIdLow    = buffer.getLong();
        int  eventTypeOrd   = buffer.getInt();
        int  flags          = buffer.getInt();
        long occurredAtMs   = buffer.getLong();
        return new EventDescriptor(
                eventIdHigh, eventIdLow,
                streamIdHigh, streamIdLow,
                eventTypeOrd, flags, occurredAtMs);
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
}
