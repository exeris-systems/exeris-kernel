/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityHttpBufferOpsTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void findCrLfReturnsFirstCrLfOffset() {
        MemorySegment segment = MemorySegment.ofArray("GET / HTTP/1.1\r\nHost: x".getBytes(StandardCharsets.US_ASCII));

        assertThat(CommunityHttpBufferOps.findCrLf(segment, 0, segment.byteSize())).isEqualTo(14);
    }

    @Test
    void retainUnreadBytesMovesTrailingBytesToFront() {
        byte[] bytes = "HEADtail".getBytes(StandardCharsets.US_ASCII);
        try (LoanedBuffer buffer = ALLOCATOR.allocateNetwork(bytes.length)) {
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buffer.segment(), 0, bytes.length);
            buffer.setSize(bytes.length);

            long remaining = CommunityHttpBufferOps.retainUnreadBytes(buffer, 4);

            assertThat(remaining).isEqualTo(4);
            assertThat(buffer.size()).isEqualTo(4);
            assertThat(readAscii(buffer, 4)).isEqualTo("tail");
        }
    }

    @Test
    void compactUnreadBytesMovesBufferedSliceToFront() {
        byte[] bytes = "0123456789".getBytes(StandardCharsets.US_ASCII);
        try (LoanedBuffer buffer = ALLOCATOR.allocateNetwork(bytes.length)) {
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buffer.segment(), 0, bytes.length);
            buffer.setSize(bytes.length);

            long unread = CommunityHttpBufferOps.compactUnreadBytes(buffer, 8, 3);

            assertThat(unread).isEqualTo(5);
            assertThat(buffer.size()).isEqualTo(5);
            assertThat(readAscii(buffer, 5)).isEqualTo("34567");
        }
    }

    private static String readAscii(LoanedBuffer buffer, int length) {
        byte[] bytes = buffer.segment().asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}