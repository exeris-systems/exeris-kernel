/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.community.events.jfr.EventLogAppendConflictEvent;
import eu.exeris.kernel.spi.events.AppendResult;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongBinaryOperator;

/**
 * Community Kafka binding of {@link EventStreamAppender} (ADR-049), log-authoritative.
 *
 * <h2>Per-Stream Ordering + Optimistic Concurrency</h2>
 * <p>Each event is produced to a single durable log topic ({@link KafkaEventConfig#eventLogTopic()})
 * keyed by the 16-byte stream UUID, so a stream's events land on one partition in append order. The
 * 1-based {@code committedSequence} is stamped into the {@link KafkaEventLogCodec} frame — <b>the log
 * is the single source of truth</b> for a stream's head. An in-memory per-stream head cache is
 * consulted for the optimistic-concurrency check and recovered from the log tail
 * ({@link KafkaEventLog#readStreamFrames}) on a cache miss (cold start / restart).
 * <ul>
 *   <li>{@link EventStreamAppender#ANY_VERSION} — append unconditionally at {@code head + 1}.</li>
 *   <li>{@code expectedVersion >= 0} — require the head to equal it; on mismatch fail closed with
 *       {@link EventStreamAppendConflictException} ({@code EX-EVENT-6008}).</li>
 * </ul>
 *
 * <h2>Community Best-Effort Limitation (ADR-049)</h2>
 * <p>Kafka has no compare-and-set, so the OCC guarantee is <b>single-writer-per-stream</b>: two
 * appender instances writing the same stream concurrently cannot be detected (both would read the
 * same head). This is the documented Community-tier boundary; JVM-controlled / enterprise durability
 * (e.g. a compacted-head checkpoint or an off-heap log) is a later, separately-justified step.
 *
 * <p>The in-memory head and per-stream lock maps are keyed by {@code StreamId} with <b>no
 * eviction</b>, so they grow with distinct-stream cardinality — bounded for typical aggregate
 * cardinality, but a deployment with unbounded stream cardinality should treat a bounding/eviction
 * policy as part of the same deferred scale upgrade.
 *
 * <h2>The Wall</h2>
 * <p>{@code org.apache.kafka.clients.*} stays inside this module; the SPI sees only
 * {@link AppendResult} / {@link EventStreamAppendConflictException}. The {@link Producer} is owned by
 * the caller (mirrors {@link KafkaEventBrokerPort}) — this appender never closes it.
 *
 * @since 0.10.0
 */
public final class KafkaEventStreamAppender implements EventStreamAppender {

    private static final long EMPTY_HEAD = 0L;

    private final Producer<byte[], byte[]> producer;
    private final String logTopic;
    private final String engineName;
    // Head recovery seam (streamIdHigh, streamIdLow) -> current head; the default reads the log tail.
    // A unit test injects a fake to exercise the OCC / monotonic / failure logic without a broker.
    private final LongBinaryOperator headResolver;
    private final Map<StreamKey, Long> heads = new ConcurrentHashMap<>();
    // Per-stream lock: a ReentrantLock (not an intrinsic monitor) so the broker-ack wait inside the
    // critical section unmounts the virtual thread cleanly. Sequence-assignment + send stay under the
    // same lock — that serialization IS the ADR-049 per-StreamId total-ordering guarantee, not a cost
    // to optimize away (releasing before send would let concurrent same-stream appends reorder).
    private final Map<StreamKey, ReentrantLock> streamLocks = new ConcurrentHashMap<>();

    /**
     * @param producer   a configured Kafka producer ({@code acks=all} recommended); NOT closed here
     * @param config     the Kafka binding config (bootstrap servers, log topic, poll timeout)
     * @param engineName human-readable engine name for JFR telemetry
     */
    public KafkaEventStreamAppender(Producer<byte[], byte[]> producer, KafkaEventConfig config, String engineName) {
        this(producer, config, engineName, defaultHeadResolver(config));
    }

    // Package-private seam: a unit test injects a fake HeadResolver + a mock Producer to exercise the
    // OCC / monotonic-sequence / failure logic without a broker (the default resolver reads the log
    // tail via KafkaEventLog, which needs a real KafkaConsumer).
    /* default */ KafkaEventStreamAppender(Producer<byte[], byte[]> producer, KafkaEventConfig config,
                                           String engineName, LongBinaryOperator headResolver) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.logTopic = Objects.requireNonNull(config, "config").eventLogTopic();
        this.engineName = Objects.requireNonNull(engineName, "engineName");
        this.headResolver = Objects.requireNonNull(headResolver, "headResolver");
    }

    private static LongBinaryOperator defaultHeadResolver(KafkaEventConfig config) {
        return (streamIdHigh, streamIdLow) -> {
            List<byte[]> frames = KafkaEventLog.readStreamFrames(config, streamIdHigh, streamIdLow);
            return frames.isEmpty() ? EMPTY_HEAD : KafkaEventLogCodec.decodeSequence(frames.get(frames.size() - 1));
        };
    }

    @Override
    public AppendResult append(StreamId streamId, long expectedVersion,
                               EventDescriptor descriptor, EventPayload payload) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload, "payload");
        try (payload) { // RAII: the appender owns the payload and releases it exactly once
            return appendUnderStreamLock(streamId, expectedVersion, descriptor, payload);
        }
    }

    private AppendResult appendUnderStreamLock(StreamId streamId, long expectedVersion,
                                               EventDescriptor descriptor, EventPayload payload) {
        StreamKey key = new StreamKey(streamId.streamIdHigh(), streamId.streamIdLow());
        ReentrantLock lock = streamLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            Long cached = heads.get(key);
            long head = cached != null ? cached : recoverHead(key);
            if (expectedVersion != ANY_VERSION && expectedVersion != head) {
                EventLogAppendConflictEvent.emit(engineName, EventLogAppendConflictEvent.PHASE_HEAD_MISMATCH,
                        streamId.streamType(), expectedVersion, head);
                throw EventStreamAppendConflictException.versionConflict(
                        streamId.streamType(), expectedVersion, head);
            }
            long next = head + 1L;
            byte[] recordKey = KafkaEventCodec.streamKey(descriptor);
            byte[] frame = KafkaEventLogCodec.encode(next, descriptor, payload);
            send(streamId, recordKey, frame);
            heads.put(key, next);
            return new AppendResult(next);
        } finally {
            lock.unlock();
        }
    }

    private void send(StreamId streamId, byte[] recordKey, byte[] frame) {
        try {
            producer.send(new ProducerRecord<>(logTopic, recordKey, frame)).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            KafkaEventLogAppendFailedEvent.emit(engineName, streamId.streamType(),
                    KafkaEventLogAppendFailedEvent.REASON_INTERRUPTED, ex);
            throw new EventEngineException("Interrupted while appending to the Kafka event log", ex);
        } catch (ExecutionException ex) {
            KafkaEventLogAppendFailedEvent.emit(engineName, streamId.streamType(),
                    KafkaEventLogAppendFailedEvent.REASON_KAFKA, ex);
            throw new EventEngineException("Failed to append event to the Kafka event log", ex);
        }
    }

    private long recoverHead(StreamKey key) {
        return headResolver.applyAsLong(key.high(), key.low());
    }

    private value record StreamKey(long high, long low) {
    }
}
