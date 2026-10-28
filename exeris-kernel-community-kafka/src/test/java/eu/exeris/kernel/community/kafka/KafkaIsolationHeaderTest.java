/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KafkaIsolationHeader}: the header name, the four-byte big-endian value and
 * the rule that anything other than a well-formed value reads as the unscoped ordinal.
 */
@DisplayName("KafkaIsolationHeader — isolation ordinal on the wire (ADR-101)")
class KafkaIsolationHeaderTest {

    @Test
    @DisplayName("write() stores the ordinal as four big-endian bytes under the documented name")
    void writeIsBigEndianUnderTheName() {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>("topic", new byte[0]);

        KafkaIsolationHeader.write(record.headers(), 101);

        Header header = record.headers().lastHeader(KafkaIsolationHeader.NAME);
        assertThat(header).isNotNull();
        assertThat(header.value()).containsExactly(0x00, 0x00, 0x00, 0x65);
    }

    @Test
    @DisplayName("write() then read() round-trips a positive ordinal")
    void writeThenReadRoundTrips() {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>("topic", new byte[0]);

        KafkaIsolationHeader.write(record.headers(), 102);

        assertThat(KafkaIsolationHeader.read(record.headers())).isEqualTo(102);
    }

    @Test
    @DisplayName("read() of an absent header is the unscoped ordinal 0")
    void absentHeaderIsUnscoped() {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>("topic", new byte[0]);

        assertThat(KafkaIsolationHeader.read(record.headers())).isZero();
    }

    @Test
    @DisplayName("read() of a value that is not four bytes is the unscoped ordinal 0")
    void wrongLengthValueIsUnscoped() {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>("topic", new byte[0]);
        record.headers().add(KafkaIsolationHeader.NAME, new byte[] {0x00, 0x00, 0x65});

        assertThat(KafkaIsolationHeader.read(record.headers())).isZero();
    }
}
