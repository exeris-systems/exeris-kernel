/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L1 Unit: CommunityGraphBufferOps")
class CommunityGraphBufferOpsTest {

    @Test
    @DisplayName("decodeProperties returns '{}' for null or empty buffers")
    void decodePropertiesDefaultsForNullOrEmpty() {
        assertThat(CommunityGraphBufferOps.decodeProperties(null)).isEqualTo("{}");

        try (LoanedBuffer empty = new StubLoanedBuffer(8)) {
            empty.setSize(0);
            assertThat(CommunityGraphBufferOps.decodeProperties(empty)).isEqualTo("{}");
        }
    }

    @Test
    @DisplayName("decodeProperties decodes non-empty UTF-8 JSON")
    void decodePropertiesDecodesUtf8Json() {
        byte[] jsonBytes = "{\"weight\":1}".getBytes(StandardCharsets.UTF_8);
        try (LoanedBuffer buffer = StubLoanedBuffer.withBytes(jsonBytes)) {
            assertThat(CommunityGraphBufferOps.decodeProperties(buffer)).isEqualTo("{\"weight\":1}");
        }
    }

    @Test
    @DisplayName("toUuidJsonArray returns [] for empty input")
    void toUuidJsonArrayReturnsEmptyArrayForEmptyList() {
        assertThat(new String(
                CommunityGraphBufferOps.toUuidJsonArray(List.of()),
                StandardCharsets.UTF_8)).isEqualTo("[]");
    }

    @Test
    @DisplayName("toUuidJsonArray renders multiple UUIDs in order")
    void toUuidJsonArrayRendersMultipleUuids() {
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(new String(
                CommunityGraphBufferOps.toUuidJsonArray(List.of(first, second)),
                StandardCharsets.UTF_8)).isEqualTo(
                "[\"11111111-1111-1111-1111-111111111111\","
                        + "\"22222222-2222-2222-2222-222222222222\"]");
    }

    private static final class StubLoanedBuffer implements LoanedBuffer {
        private final MemorySegment segment;
        private final long capacity;
        private long size;

        private StubLoanedBuffer(int capacity) {
            this.segment = MemorySegment.ofArray(new byte[capacity]);
            this.capacity = capacity;
        }

        private static LoanedBuffer withBytes(byte[] bytes) {
            StubLoanedBuffer buffer = new StubLoanedBuffer(bytes.length);
            MemorySegment.copy(bytes, 0, buffer.segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
            buffer.setSize(bytes.length);
            return buffer;
        }

        @Override
        public MemorySegment segment() {
            return segment;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return capacity;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retain() {
            // Single-owner test stub: retain is not needed for these helper assertions.
        }

        @Override
        public void close() {
            // Heap-backed test stub has no external resources to release.
        }

        @Override
        public int refCount() {
            return 1;
        }

        @Override
        public void setSize(long newSize) {
            this.size = newSize;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void addCloseAction(Runnable action) {
            throw new UnsupportedOperationException();
        }
    }
}