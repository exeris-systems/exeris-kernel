/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventStream;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.events.StreamId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Community Kafka binding of {@link EventStreamReader} (ADR-049), log-authoritative.
 *
 * <h2>Ordering</h2>
 * <p>{@link #replayFromVersion(StreamId, long)} yields a stream's events in ascending
 * {@code committedSequence} order — a stream's records all share one partition (key = {@code
 * streamId}), so {@link KafkaEventLog#readStreamFrames} returns them in offset order == append order
 * without a sort. {@link #replayByType} scans all partitions and orders by
 * {@code (occurredAt, committedSequence)}.
 *
 * <h2>Community Best-Effort (ADR-049)</h2>
 * <p>Replay is a bounded read-to-end of the log (O(topic)), materialized into a {@link KafkaEventStream}.
 * Correct and simple; scale optimisation (per-stream partition targeting / compacted-head checkpoint)
 * is deferred. Holds no live Kafka resource between calls — each replay opens and closes its own
 * scan consumer inside {@link KafkaEventLog}; {@link #close()} is a no-op.
 *
 * @since 0.10.0
 */
public final class KafkaEventStreamReader implements EventStreamReader {

    private final KafkaEventConfig config;
    private final ToIntFunction<String> typeOrdinalResolver;

    /**
     * @param config              the Kafka binding config (bootstrap servers, log topic, poll timeout)
     * @param typeOrdinalResolver resolves an event-type name to its ordinal for {@code replayByType}
     *                            (wire to {@code EventRegistry::ordinalOf}; {@code -1} = unknown)
     */
    public KafkaEventStreamReader(KafkaEventConfig config, ToIntFunction<String> typeOrdinalResolver) {
        this.config = Objects.requireNonNull(config, "config");
        this.typeOrdinalResolver = Objects.requireNonNull(typeOrdinalResolver, "typeOrdinalResolver");
    }

    @Override
    public EventStream replayFrom(StreamId streamId, Instant fromTimestamp) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(fromTimestamp, "fromTimestamp");
        long fromMillis = fromTimestamp.toEpochMilli();
        List<byte[]> frames = KafkaEventLog.readStreamFrames(config, streamId.streamIdHigh(), streamId.streamIdLow());
        List<byte[]> matched = new ArrayList<>(frames.size());
        for (byte[] frame : frames) {
            if (KafkaEventLogCodec.decodeDescriptor(frame).occurredAtEpochMs() >= fromMillis) {
                matched.add(frame);
            }
        }
        return new KafkaEventStream(matched);
    }

    @Override
    public EventStream replayFromVersion(StreamId streamId, long fromVersion) {
        Objects.requireNonNull(streamId, "streamId");
        List<byte[]> frames = KafkaEventLog.readStreamFrames(config, streamId.streamIdHigh(), streamId.streamIdLow());
        List<byte[]> matched = new ArrayList<>(frames.size());
        for (byte[] frame : frames) {
            if (KafkaEventLogCodec.decodeSequence(frame) >= fromVersion) {
                matched.add(frame);
            }
        }
        return new KafkaEventStream(matched);
    }

    @Override
    public EventStream replayByType(String eventType, Instant fromTimestamp) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(fromTimestamp, "fromTimestamp");
        int ordinal = typeOrdinalResolver.applyAsInt(eventType);
        long fromMillis = fromTimestamp.toEpochMilli();
        List<byte[]> matched = new ArrayList<>();
        for (byte[] frame : KafkaEventLog.readAllFrames(config)) {
            EventDescriptor descriptor = KafkaEventLogCodec.decodeDescriptor(frame);
            if (descriptor.eventTypeOrdinal() == ordinal && descriptor.occurredAtEpochMs() >= fromMillis) {
                matched.add(frame);
            }
        }
        // Cross-stream: partitions interleave, so impose a deterministic (occurredAt, sequence) order.
        matched.sort(Comparator
                .comparingLong((byte[] frame) -> KafkaEventLogCodec.decodeDescriptor(frame).occurredAtEpochMs())
                .thenComparingLong(KafkaEventLogCodec::decodeSequence));
        return new KafkaEventStream(matched);
    }

    @Override
    public void close() {
        // No shared resource: each replay opens and closes its own scan consumer inside KafkaEventLog.
        // Idempotent no-op; does not invalidate any stream returned earlier.
    }
}
