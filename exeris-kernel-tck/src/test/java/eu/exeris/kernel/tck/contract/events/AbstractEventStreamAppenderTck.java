/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.StreamId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * TCK: Abstract base for {@link EventStreamAppender} contract verification (since 0.7.0).
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>{@link EventStreamAppender#append(StreamId, EventDescriptor, EventPayload)} accepts a
 *       valid (streamId, descriptor, payload) triple without throwing.</li>
 *   <li>The appender takes ownership of the supplied {@link EventPayload}: after the call
 *       returns successfully, the payload's effective refCount has been adjusted by the binding
 *       (the caller MUST NOT call {@code close()} on the same reference). The TCK enforces this
 *       by passing a tracking payload and asserting at least one {@code close()} was issued by
 *       the appender or its downstream pipeline.</li>
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
 * @since 0.7.0
 * @see EventStreamAppender
 * @see StreamId
 */
@DisplayName("EventStreamAppender TCK — durable append contract")
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
    @DisplayName("append() with valid args completes without throwing")
    void appendWithValidArgsSucceeds() {
        StreamId streamId = freshStreamId();
        EventDescriptor descriptor = descriptor(streamId);

        assertThatCode(() -> appender.append(streamId, descriptor, EventPayload.empty()))
                .as("append() with non-null args MUST complete without throwing")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("append() takes ownership of the payload — at least one close() observed downstream")
    void appendTakesPayloadOwnership() {
        StreamId streamId = freshStreamId();
        EventDescriptor descriptor = descriptor(streamId);
        AtomicInteger closeCalls = new AtomicInteger();
        EventPayload tracking = trackingPayload(closeCalls);

        appender.append(streamId, descriptor, tracking);

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
                .isThrownBy(() -> appender.append(null, descriptor, EventPayload.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> appender.append(streamId, null, EventPayload.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> appender.append(streamId, descriptor, null));
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
