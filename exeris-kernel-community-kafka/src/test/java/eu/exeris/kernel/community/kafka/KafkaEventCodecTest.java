/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KafkaEventCodec}.
 *
 * <p>The wire format is contract; these tests catch regressions in milliseconds without
 * spinning Testcontainers (the integration TCK exercises the full broker hop).
 */
@DisplayName("KafkaEventCodec — wire-format contract")
class KafkaEventCodecTest {

    @Nested
    @DisplayName("encode → decode roundtrip")
    class Roundtrip {

        @Test
        @DisplayName("empty payload preserves descriptor primitives bit-exact")
        void emptyPayloadRoundtrip() {
            EventDescriptor original = sampleDescriptor(123, EventDescriptor.FLAG_PERSISTENT, 1_700_000_000_000L);
            byte[] frame = KafkaEventCodec.encode(original, EventPayload.empty());

            assertThat(frame).hasSize(KafkaEventCodec.HEADER_SIZE);

            EventDescriptor decoded = KafkaEventCodec.decodeDescriptor(frame, 0);
            assertDescriptorsEqual(decoded, original);
            assertThat(KafkaEventCodec.decodePayloadBytes(frame)).isEmpty();
        }

        @Test
        @DisplayName("single-byte payload is preserved verbatim in the tail")
        void singleBytePayloadRoundtrip() {
            EventDescriptor original = sampleDescriptor(7, 0, 42L);
            byte[] payload = {(byte) 0xAB};
            byte[] frame = KafkaEventCodec.encode(original, heapPayload(payload));

            assertThat(frame).hasSize(KafkaEventCodec.HEADER_SIZE + 1);
            assertThat(KafkaEventCodec.decodePayloadBytes(frame)).containsExactly(payload);
        }

        @Test
        @DisplayName("multi-kilobyte payload is preserved byte-for-byte")
        void largePayloadRoundtrip() {
            byte[] payload = new byte[4096];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            EventDescriptor original = sampleDescriptor(99, EventDescriptor.FLAG_ORDERED, 0L);
            byte[] frame = KafkaEventCodec.encode(original, heapPayload(payload));

            assertThat(frame).hasSize(KafkaEventCodec.HEADER_SIZE + payload.length);

            EventDescriptor decoded = KafkaEventCodec.decodeDescriptor(frame, 0);
            assertDescriptorsEqual(decoded, original);
            assertThat(KafkaEventCodec.decodePayloadBytes(frame)).containsExactly(payload);
        }
    }

    @Nested
    @DisplayName("decodeDescriptor — malformed frame defence")
    class MalformedFrame {

        @Test
        @DisplayName("frame shorter than HEADER_SIZE is rejected with IllegalArgumentException")
        void shortFrameRejected() {
            byte[] tooShort = new byte[KafkaEventCodec.HEADER_SIZE - 1];
            assertThatThrownBy(() -> KafkaEventCodec.decodeDescriptor(tooShort, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(KafkaEventCodec.HEADER_SIZE));
        }

        @Test
        @DisplayName("zero-length frame is rejected — no buffer underrun")
        void emptyFrameRejected() {
            assertThatThrownBy(() -> KafkaEventCodec.decodeDescriptor(new byte[0], 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("isolation ordinal — carried by the header, never by the frame")
    class IsolationOrdinal {

        @Test
        @DisplayName("the frame bytes do not depend on the isolation ordinal")
        void frameIsIdenticalAcrossIsolationOrdinals() {
            EventDescriptor unscoped = EventDescriptor.of(
                    1L, 2L, 3L, 4L, 5, EventDescriptor.FLAG_PERSISTENT, 6L, 0);
            EventDescriptor scoped = EventDescriptor.of(
                    1L, 2L, 3L, 4L, 5, EventDescriptor.FLAG_PERSISTENT, 6L, 101);

            assertThat(KafkaEventCodec.encode(scoped, EventPayload.empty()))
                    .containsExactly(KafkaEventCodec.encode(unscoped, EventPayload.empty()));
        }

        @Test
        @DisplayName("a record without the header decodes as the unscoped ordinal 0")
        void recordWithoutHeaderDecodesUnscoped() {
            byte[] frame = KafkaEventCodec.encode(sampleDescriptor(5, 0, 6L), EventPayload.empty());
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>("topic", frame);

            int ordinal = KafkaIsolationHeader.read(record.headers());

            assertThat(ordinal).isZero();
            assertThat(KafkaEventCodec.decodeDescriptor(frame, ordinal).isolationOrdinal()).isZero();
        }

        @Test
        @DisplayName("header value 0x00000065 decodes as isolation ordinal 101")
        void headerValueDecodesAsOrdinal() {
            byte[] frame = KafkaEventCodec.encode(sampleDescriptor(5, 0, 6L), EventPayload.empty());
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>("topic", frame);
            record.headers().add(KafkaIsolationHeader.NAME, new byte[] {0x00, 0x00, 0x00, 0x65});

            int ordinal = KafkaIsolationHeader.read(record.headers());

            assertThat(ordinal).isEqualTo(101);
            assertThat(KafkaEventCodec.decodeDescriptor(frame, ordinal).isolationOrdinal()).isEqualTo(101);
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

    private static EventPayload heapPayload(byte[] bytes) {
        return KafkaHeapEventPayload.wrap(bytes);
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
