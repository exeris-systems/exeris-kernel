/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.ByteBuffer;

/**
 * Carries an event's isolation ordinal across the broker as a Kafka record header (ADR-101).
 *
 * <h2>Why a header, not the frame</h2>
 * <p>The value frame is a fixed binary layout shared with every consumer of the topic, and it
 * stays byte-identical. The ordinal rides alongside it, so a consumer that predates isolation
 * scoping reads the same frame it always did and ignores the header.
 *
 * <h2>Absence is unscoped</h2>
 * <p>A record without the header, or with a value that is not four bytes, decodes as the unscoped
 * ordinal {@code 0}. That is the only reading a record from a producer that never wrote the header
 * can have.
 *
 * <h2>Wire form</h2>
 * <p>{@value #NAME} carries the ordinal as four big-endian bytes.
 *
 * @since 0.13
 */
final class KafkaIsolationHeader {

    /** Record header name carrying the isolation ordinal. */
    /* default */ static final String NAME = "exeris-isolation-ordinal";

    private static final int VALUE_LENGTH = Integer.BYTES;

    private KafkaIsolationHeader() {
    }

    /**
     * Adds the isolation ordinal to a record's headers.
     *
     * @param headers          the headers of the record being produced (non-null)
     * @param isolationOrdinal the descriptor's isolation ordinal; {@code 0} means unscoped
     */
    /* default */ static void write(Headers headers, int isolationOrdinal) {
        headers.add(NAME, ByteBuffer.allocate(VALUE_LENGTH).putInt(isolationOrdinal).array());
    }

    /**
     * Reads the isolation ordinal from a consumed record's headers.
     *
     * @param headers the headers of the consumed record (non-null)
     * @return the ordinal carried by the header, or {@code 0} when the header is absent or its
     *         value is not four bytes
     */
    /* default */ static int read(Headers headers) {
        Header header = headers.lastHeader(NAME);
        if (header == null) {
            return 0;
        }
        byte[] value = header.value();
        if (value == null || value.length != VALUE_LENGTH) {
            return 0;
        }
        return ByteBuffer.wrap(value).getInt();
    }
}
