/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KafkaEventStream} (ADR-049 materialized replay). Exercises iteration order,
 * per-payload refCount, and RAII/close semantics without a broker (frames are built via
 * {@link KafkaEventLogCodec}).
 */
@DisplayName("KafkaEventStream — materialized replay iteration (ADR-049)")
class KafkaEventStreamTest {

    @Test
    @DisplayName("iterates frames in list order; each payload arrives at refCount 1")
    void iteratesInOrderAtRefCountOne() {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            frames.add(frame(i + 1L, new byte[]{(byte) i}));
        }

        List<Integer> seen = new ArrayList<>();
        try (EventStream stream = new KafkaEventStream(frames)) {
            for (EventPayload payload : stream) {
                try (payload) {
                    assertThat(payload.refCount()).isEqualTo(1);
                    seen.add((int) payload.segment().toArray(ValueLayout.JAVA_BYTE)[0]);
                }
            }
        }
        assertThat(seen).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("an empty frame list yields an empty iterator")
    void emptyStream() {
        try (EventStream stream = new KafkaEventStream(List.of())) {
            assertThat(stream.iterator().hasNext()).isFalse();
        }
    }

    @Test
    @DisplayName("next() past the end throws NoSuchElementException")
    void nextPastEndThrows() {
        try (EventStream stream = new KafkaEventStream(List.of())) {
            Iterator<EventPayload> iterator = stream.iterator();
            assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    @DisplayName("close() is idempotent")
    void closeIsIdempotent() {
        EventStream stream = new KafkaEventStream(List.of(frame(1L, new byte[0])));
        stream.close();
        assertThatCode(stream::close).doesNotThrowAnyException();
    }

    private static byte[] frame(long sequence, byte[] payload) {
        UUID eventId  = UUID.randomUUID();
        UUID streamId = UUID.fromString("01890000-0000-7000-8000-000000000009");
        EventDescriptor descriptor = EventDescriptor.of(
                eventId.getMostSignificantBits(),  eventId.getLeastSignificantBits(),
                streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                7, EventDescriptor.FLAG_PERSISTENT, 0L);
        return KafkaEventLogCodec.encode(sequence, descriptor, KafkaHeapEventPayload.wrap(payload));
    }
}
