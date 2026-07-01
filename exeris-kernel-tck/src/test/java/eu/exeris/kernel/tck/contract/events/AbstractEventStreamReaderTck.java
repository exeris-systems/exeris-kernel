/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStream;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.events.StreamId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * TCK: Abstract base for {@link EventStreamReader} contract verification (since 0.7.0).
 *
 * <h2>Role</h2>
 * <p>This suite defines the binding-agnostic obligations every {@link EventStreamReader}
 * implementation must satisfy. Concrete bindings (Kafka driver in 0.7 Sprint 5b, Postgres
 * outbox replay, Enterprise off-heap log) extend it and provide {@link #createReader()}
 * plus {@link #seedStream(StreamId, int)} to populate the underlying durable log.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>{@code replayFrom} / {@code replayFromVersion} / {@code replayByType} return a non-null
 *       {@link EventStream} for known coordinates.</li>
 *   <li>The returned {@link EventStream} is {@link AutoCloseable} with an idempotent
 *       {@code close()} (calling twice does NOT throw).</li>
 *   <li>The reader's own {@link EventStreamReader#close()} is idempotent and does not invalidate
 *       previously returned streams.</li>
 *   <li>Each {@link EventPayload} handed to the iterator arrives at {@code refCount == 1};
 *       consumer closes own each payload (no broadcast retain protocol on replay).</li>
 *   <li>Replay from a stream that has no events returns an empty (but valid) stream.</li>
 *   <li><b>Ordering (ADR-049):</b> {@code replayFromVersion} yields a stream's events in strictly
 *       ascending {@code committedSequence} order — the read side of the durable-log ordering
 *       boundary. The end-to-end append→replay ordering round-trip is exercised by the concrete
 *       binding integration tests (Postgres outbox, Kafka), which control payload identity.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityKafkaEventStreamReaderTckIT extends AbstractEventStreamReaderTck {
 *     \@Override protected EventStreamReader createReader() { return kafkaReaderFromTestcontainer(); }
 *     \@Override protected void seedStream(StreamId id, int eventCount) { kafkaProducer.send(...); }
 * }
 * }</pre>
 *
 * @since 0.7.0
 * @see EventStreamReader
 * @see EventStream
 * @see StreamId
 */
@DisplayName("EventStreamReader TCK — replay contract")
public abstract class AbstractEventStreamReaderTck {

    /** Creates a fresh, ready-to-use {@link EventStreamReader}. The TCK calls {@code close()} on tearDown. */
    protected abstract EventStreamReader createReader();

    /**
     * Populates the durable log so {@code replayFrom(streamId, ...)} returns {@code eventCount}
     * events. The seeded events are expected to occur strictly before {@link Instant#now()}.
     */
    protected abstract void seedStream(StreamId streamId, int eventCount);

    private EventStreamReader reader;

    @BeforeEach
    final void setUp() {
        reader = createReader();
    }

    @AfterEach
    final void tearDown() {
        if (reader != null) {
            reader.close();
        }
    }

    private static StreamId freshStreamId(String streamType) {
        UUID id = UUID.randomUUID();
        return new StreamId(id.getMostSignificantBits(), id.getLeastSignificantBits(), streamType);
    }

    @Nested
    @DisplayName("Replay query API")
    class ReplayQuery {

        @Test
        @DisplayName("replayFrom() with a seeded stream returns a non-null EventStream")
        void replayFromReturnsNonNullStream() {
            StreamId streamId = freshStreamId("ReplayFromSeed");
            seedStream(streamId, 3);

            try (EventStream stream = reader.replayFrom(streamId, Instant.EPOCH)) {
                assertThat(stream)
                        .as("replayFrom() MUST return a non-null EventStream for a seeded stream")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("replayFromVersion() with a seeded stream returns a non-null EventStream")
        void replayFromVersionReturnsNonNullStream() {
            StreamId streamId = freshStreamId("ReplayFromVersionSeed");
            seedStream(streamId, 5);

            try (EventStream stream = reader.replayFromVersion(streamId, 0L)) {
                assertThat(stream).isNotNull();
            }
        }

        @Test
        @DisplayName("replayByType() returns a non-null EventStream")
        void replayByTypeReturnsNonNullStream() {
            try (EventStream stream = reader.replayByType("AnyTypeName", Instant.EPOCH)) {
                assertThat(stream)
                        .as("replayByType() MUST return a non-null EventStream (possibly empty)")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("Replay of an unseeded stream returns an empty (but valid) EventStream")
        void replayOfUnseededStreamReturnsEmptyStream() {
            StreamId unseeded = freshStreamId("UnseededStream");

            try (EventStream stream = reader.replayFrom(unseeded, Instant.EPOCH)) {
                Iterator<EventPayload> it = stream.iterator();
                assertThat(it.hasNext())
                        .as("Replay of an unseeded stream MUST return an empty iterator, not throw")
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("EventStream lifecycle — RAII + idempotent close")
    class StreamLifecycle {

        @Test
        @DisplayName("EventStream.close() is idempotent — calling twice does NOT throw")
        void streamCloseIsIdempotent() {
            StreamId streamId = freshStreamId("StreamCloseIdem");
            seedStream(streamId, 1);

            EventStream stream = reader.replayFrom(streamId, Instant.EPOCH);
            stream.close();
            assertThatCode(stream::close)
                    .as("EventStream.close() MUST be idempotent — second call is a safe no-op")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Each replayed payload arrives at refCount=1; consumer owns the close")
        void replayedPayloadHasSingleRef() {
            StreamId streamId = freshStreamId("ReplayRefCount");
            seedStream(streamId, 2);

            List<Integer> initialRefCounts = new ArrayList<>();
            try (EventStream stream = reader.replayFrom(streamId, Instant.EPOCH)) {
                for (EventPayload payload : stream) {
                    try (payload) {
                        initialRefCounts.add(payload.refCount());
                    }
                }
            }

            assertThat(initialRefCounts)
                    .as("Replay MUST hand the consumer a single-owner payload (refCount=1) per event "
                            + "— no broadcast retain protocol applies on replay")
                    .allSatisfy(count ->
                            assertThat(count)
                                    .as("EventPayload.refCount() at hand-off")
                                    .isEqualTo(1));
        }
    }

    @Nested
    @DisplayName("Reader lifecycle — close + null-arg defence")
    class ReaderLifecycle {

        @Test
        @DisplayName("EventStreamReader.close() is idempotent")
        void readerCloseIsIdempotent() {
            EventStreamReader spare = createReader();
            spare.close();
            assertThatCode(spare::close)
                    .as("EventStreamReader.close() MUST be idempotent — second call is a safe no-op")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Null arguments raise NullPointerException")
        void nullArgsRaiseNpe() {
            StreamId streamId = freshStreamId("NullArgs");
            seedStream(streamId, 0);

            assertThatNullPointerException().isThrownBy(() -> reader.replayFrom(null, Instant.EPOCH));
            assertThatNullPointerException().isThrownBy(() -> reader.replayFrom(streamId, null));
            assertThatNullPointerException().isThrownBy(() -> reader.replayFromVersion(null, 0L));
            assertThatNullPointerException().isThrownBy(() -> reader.replayByType(null, Instant.EPOCH));
            assertThatNullPointerException().isThrownBy(() -> reader.replayByType("Any", null));
        }
    }
}
