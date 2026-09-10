/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KafkaEventLogCodec} (ADR-049 event-log frame). The wire format — leading
 * {@code committedSequence} + descriptor + payload — is contract; these run in milliseconds without
 * a broker (the Testcontainers TCK exercises the full hop).
 */
@DisplayName("KafkaEventLogCodec — event-log frame contract (ADR-049)")
class KafkaEventLogCodecTest {

    @Nested
    @DisplayName("encode → decode roundtrip")
    class Roundtrip {

        @Test
        @DisplayName("empty payload preserves sequence + stream id + descriptor bit-exact")
        void emptyPayloadRoundtrip() {
            EventDescriptor original = sampleDescriptor(123, EventDescriptor.FLAG_PERSISTENT, 1_700_000_000_000L);
            byte[] frame = KafkaEventLogCodec.encode(42L, original, EventPayload.empty());

            assertThat(frame).hasSize(KafkaEventLogCodec.HEADER_SIZE);
            assertThat(KafkaEventLogCodec.decodeSequence(frame)).isEqualTo(42L);
            assertThat(KafkaEventLogCodec.decodeStreamIdHigh(frame)).isEqualTo(original.streamIdHigh());
            assertThat(KafkaEventLogCodec.decodeStreamIdLow(frame)).isEqualTo(original.streamIdLow());
            assertDescriptorsEqual(KafkaEventLogCodec.decodeDescriptor(frame), original);
            assertThat(KafkaEventLogCodec.decodePayloadSegment(frame).byteSize()).isZero();
        }

        @Test
        @DisplayName("multi-kilobyte payload tail is preserved byte-for-byte; sequence roundtrips")
        void payloadRoundtrip() {
            byte[] payload = new byte[2048];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            EventDescriptor original = sampleDescriptor(7, EventDescriptor.FLAG_ORDERED, 99L);
            byte[] frame = KafkaEventLogCodec.encode(9_999L, original, KafkaHeapEventPayload.wrap(payload));

            assertThat(frame).hasSize(KafkaEventLogCodec.HEADER_SIZE + payload.length);
            assertThat(KafkaEventLogCodec.decodeSequence(frame)).isEqualTo(9_999L);
            assertDescriptorsEqual(KafkaEventLogCodec.decodeDescriptor(frame), original);
            byte[] tail = KafkaEventLogCodec.decodePayloadSegment(frame).toArray(ValueLayout.JAVA_BYTE);
            assertThat(tail).containsExactly(payload);
        }
    }

    @Nested
    @DisplayName("malformed frame defence")
    class Malformed {

        @Test
        @DisplayName("a frame shorter than HEADER_SIZE is rejected by every decoder")
        void shortFrameRejected() {
            byte[] tooShort = new byte[KafkaEventLogCodec.HEADER_SIZE - 1];
            assertThatThrownBy(() -> KafkaEventLogCodec.decodeSequence(tooShort))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(KafkaEventLogCodec.HEADER_SIZE));
            assertThatThrownBy(() -> KafkaEventLogCodec.decodeDescriptor(tooShort))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KafkaEventLogCodec.decodeStreamIdHigh(tooShort))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KafkaEventLogCodec.decodePayloadSegment(tooShort))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static EventDescriptor sampleDescriptor(int ordinal, int flags, long occurredAt) {
        UUID eventId  = UUID.fromString("01890000-0000-7000-8000-000000000001");
        UUID streamId = UUID.fromString("01890000-0000-7000-8000-000000000002");
        return EventDescriptor.of(
                eventId.getMostSignificantBits(),  eventId.getLeastSignificantBits(),
                streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                ordinal, flags, occurredAt);
    }

    private static void assertDescriptorsEqual(EventDescriptor actual, EventDescriptor expected) {
        assertThat(actual.eventIdHigh()).isEqualTo(expected.eventIdHigh());
        assertThat(actual.eventIdLow()).isEqualTo(expected.eventIdLow());
        assertThat(actual.streamIdHigh()).isEqualTo(expected.streamIdHigh());
        assertThat(actual.streamIdLow()).isEqualTo(expected.streamIdLow());
        assertThat(actual.eventTypeOrdinal()).isEqualTo(expected.eventTypeOrdinal());
        assertThat(actual.flags()).isEqualTo(expected.flags());
        assertThat(actual.occurredAtEpochMs()).isEqualTo(expected.occurredAtEpochMs());
    }
}
