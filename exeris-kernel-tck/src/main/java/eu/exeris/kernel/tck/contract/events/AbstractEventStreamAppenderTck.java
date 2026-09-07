/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.AppendResult;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * TCK: Abstract base for {@link EventStreamAppender} contract verification (since 0.7.0; per-stream
 * ordering + optimistic-concurrency contract added in 0.10.0 per ADR-049).
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>{@link EventStreamAppender#append(StreamId, long, EventDescriptor, EventPayload)} with a
 *       valid triple and {@link EventStreamAppender#ANY_VERSION} returns a non-null
 *       {@link AppendResult} whose {@code committedSequence} is {@code >= 1}.</li>
 *   <li><b>Per-stream total order (ADR-049):</b> successive appends to the same {@link StreamId}
 *       return strictly monotonically increasing {@code committedSequence} values.</li>
 *   <li><b>Optimistic concurrency (ADR-049):</b> the first OCC append uses {@code expectedVersion == 0}
 *       and commits at {@code 1}; passing the previous {@code committedSequence} as the next
 *       {@code expectedVersion} commits at {@code +1}.</li>
 *   <li><b>Conflict is fail-closed (ADR-049):</b> a stale {@code expectedVersion} raises
 *       {@link EventStreamAppendConflictException} ({@code EX-EVENT-6008}) and does not advance
 *       the head.</li>
 *   <li>The appender takes ownership of the supplied {@link EventPayload}: after a successful call
 *       the payload's effective refCount has been adjusted by the binding (the caller MUST NOT call
 *       {@code close()} on the same reference). Enforced via a tracking payload asserting at least
 *       one {@code close()} downstream.</li>
 *   <li>Null arguments raise {@link NullPointerException}.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityKafkaEventStreamAppenderTckIT extends AbstractEventStreamAppenderTck {
 *     \@Override protected EventStreamAppender createAppender() { return kafkaAppenderFromTestcontainer(); }
 * }
 * }</pre>
 *
 * @since 0.7
 * @see EventStreamAppender
 * @see AppendResult
 * @see StreamId
 */
@DisplayName("EventStreamAppender TCK — durable append + ordering/OCC contract")
public abstract class AbstractEventStreamAppenderTck {

    /** Creates a fresh, ready-to-use {@link EventStreamAppender}. */
    protected abstract EventStreamAppender createAppender();

    private EventStreamAppender appender;

    @BeforeEach
    final void setUp() {
        appender = createAppender();
    }

    @AfterEach
    final void tearDown() {
        appender = null;
    }

    private static StreamId freshStreamId() {
        UUID id = UUID.randomUUID();
        return new StreamId(id.getMostSignificantBits(), id.getLeastSignificantBits(), "AppenderTck");
    }

    private static EventDescriptor descriptor(StreamId streamId) {
        UUID eventId = UUID.randomUUID();
        return new EventDescriptor(
                eventId.getMostSignificantBits(), eventId.getLeastSignificantBits(),
                streamId.streamIdHigh(), streamId.streamIdLow(),
                /* eventTypeOrdinal */ 7_777,
                EventDescriptor.FLAG_PERSISTENT,
                System.currentTimeMillis());
    }

    @Test
    @DisplayName("append() with ANY_VERSION returns a committed sequence >= 1")
    void appendWithAnyVersionReturnsSequence() {
        StreamId streamId = freshStreamId();

        AppendResult result = appender.append(
                streamId, EventStreamAppender.ANY_VERSION, descriptor(streamId), EventPayload.empty());

        assertThat(result)
                .as("append() MUST return a non-null AppendResult")
                .isNotNull();
        assertThat(result.committedSequence())
                .as("committedSequence MUST be a 1-based per-stream head")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Successive appends to one stream return strictly monotonic sequences (ADR-049)")
    void appendReturnsMonotonicSequencePerStream() {
        StreamId streamId = freshStreamId();

        long s1 = appender.append(streamId, EventStreamAppender.ANY_VERSION,
                descriptor(streamId), EventPayload.empty()).committedSequence();
        long s2 = appender.append(streamId, EventStreamAppender.ANY_VERSION,
                descriptor(streamId), EventPayload.empty()).committedSequence();
        long s3 = appender.append(streamId, EventStreamAppender.ANY_VERSION,
                descriptor(streamId), EventPayload.empty()).committedSequence();

        assertThat(s2).as("per-stream sequence MUST strictly increase").isGreaterThan(s1);
        assertThat(s3).as("per-stream sequence MUST strictly increase").isGreaterThan(s2);
    }

    @Test
    @DisplayName("Optimistic append: expectedVersion=0 commits at 1, then matching version commits at +1 (ADR-049)")
    void optimisticAppendSucceedsOnMatchingVersion() {
        StreamId streamId = freshStreamId();

        AppendResult first = appender.append(streamId, 0L, descriptor(streamId), EventPayload.empty());
        assertThat(first.committedSequence())
                .as("first OCC append (expectedVersion=0) MUST commit at 1")
                .isEqualTo(1L);

        AppendResult second = appender.append(
                streamId, first.committedSequence(), descriptor(streamId), EventPayload.empty());
        assertThat(second.committedSequence())
                .as("append at the observed head MUST commit at head+1")
                .isEqualTo(first.committedSequence() + 1L);
    }

    @Test
    @DisplayName("Optimistic append: a stale expectedVersion fails closed with EX-EVENT-6008 (ADR-049)")
    void optimisticAppendConflictOnStaleVersion() {
        StreamId streamId = freshStreamId();

        appender.append(streamId, 0L, descriptor(streamId), EventPayload.empty()); // head -> 1

        assertThatExceptionOfType(EventStreamAppendConflictException.class)
                .as("re-appending with the now-stale expectedVersion=0 MUST fail closed")
                .isThrownBy(() -> appender.append(streamId, 0L, descriptor(streamId), EventPayload.empty()));
    }

    @Test
    @DisplayName("append() takes ownership of the payload — at least one close() observed downstream")
    void appendTakesPayloadOwnership() {
        StreamId streamId = freshStreamId();
        AtomicInteger closeCalls = new AtomicInteger();
        EventPayload tracking = trackingPayload(closeCalls);

        appender.append(streamId, EventStreamAppender.ANY_VERSION, descriptor(streamId), tracking);

        assertThat(closeCalls.get())
                .as("append() MUST take ownership of the payload — caller's reference MUST be released "
                        + "by the appender or a downstream queue handoff (RAII contract from EventBus.publish)")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Null arguments raise NullPointerException")
    void nullArgsRaiseNpe() {
        StreamId streamId = freshStreamId();
        EventDescriptor descriptor = descriptor(streamId);

        assertThatNullPointerException()
                .isThrownBy(() -> appender.append(null, EventStreamAppender.ANY_VERSION, descriptor, EventPayload.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> appender.append(streamId, EventStreamAppender.ANY_VERSION, null, EventPayload.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> appender.append(streamId, EventStreamAppender.ANY_VERSION, descriptor, null));
    }

    @Test
    @DisplayName("append() with a valid triple completes without throwing")
    void appendWithValidArgsSucceeds() {
        StreamId streamId = freshStreamId();

        assertThatCode(() -> appender.append(
                streamId, EventStreamAppender.ANY_VERSION, descriptor(streamId), EventPayload.empty()))
                .as("append() with non-null args MUST complete without throwing")
                .doesNotThrowAnyException();
    }

    private static EventPayload trackingPayload(AtomicInteger closeCalls) {
        return new EventPayload() {
            private volatile boolean alive = true;
            @Override public java.lang.foreign.MemorySegment segment() { return java.lang.foreign.MemorySegment.NULL; }
            @Override public int     length()    { return 0; }
            @Override public int     refCount()  { return alive ? 1 : 0; }
            @Override public boolean isAlive()   { return alive; }
            @Override public void    retain()    { /* tracking only */ }
            @Override public void    close() {
                alive = false;
                closeCalls.incrementAndGet();
            }
        };
    }
}
