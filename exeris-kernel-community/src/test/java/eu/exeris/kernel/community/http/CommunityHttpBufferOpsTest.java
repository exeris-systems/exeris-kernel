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

    @Test
    void parseStatusCodeValid() {
        assertStatusCode("HTTP/1.1 200 OK\r\n", 200);
        assertStatusCode("HTTP/1.0 204 No Content\r\n", 204);
        assertStatusCode("HTTP/1.1 304 Not Modified\r\n", 304);
        assertStatusCode("HTTP/1.1 404 Not Found\r\n", 404);
        assertStatusCode("HTTP/1.1 500 Internal Server Error\r\n", 500);
        assertStatusCode("HTTP/1.1 200\r\n", 200);
    }

    @Test
    void parseStatusCodeRejectsInvalid() {
        assertStatusCode("HTTP/1.1 2000 OK\r\n", -1);
        assertStatusCode("HTTP/1.1 20 OK\r\n", -1);
        assertStatusCode("HTTP/1.1 20a OK\r\n", -1);
        assertStatusCode("HTTP/1.1\r\n", -1);
        assertStatusCode("HTTP/1.1 2", -1);
    }

    @Test
    void findHeaderTerminatorFindsOffset() {
        byte[] bytes = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nTail".getBytes(StandardCharsets.US_ASCII);
        MemorySegment seg = MemorySegment.ofArray(bytes);
        long offset = CommunityHttpBufferOps.findHeaderTerminator(seg, 0, bytes.length);
        assertThat(offset).isEqualTo(34L);
    }

    @Test
    void findHeaderTerminatorReturnsMinusOneWhenMissing() {
        byte[] bytes = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n".getBytes(StandardCharsets.US_ASCII);
        MemorySegment seg = MemorySegment.ofArray(bytes);
        long offset = CommunityHttpBufferOps.findHeaderTerminator(seg, 0, bytes.length);
        assertThat(offset).isEqualTo(-1);
    }

    @Test
    void matchesAsciiIgnoreCaseMatchingAndMismatching() {
        byte[] bytes = "Prefix-Content-Length: 123".getBytes(StandardCharsets.US_ASCII);
        MemorySegment seg = MemorySegment.ofArray(bytes);

        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 7, 21, "content-length")).isTrue();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 7, 21, "CONTENT-LENGTH")).isTrue();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 7, 21, "Content-Length")).isTrue();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 7, 20, "Content-Length")).isFalse();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 7, 22, "Content-Length")).isFalse();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 0, 6, "prefix")).isTrue();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 0, 6, "PREFIY")).isFalse();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, -1, 5, "prefix")).isFalse();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 6, 5, "prefix")).isFalse();
        assertThat(CommunityHttpBufferOps.matchesAsciiIgnoreCase(seg, 0, bytes.length + 1, "prefix")).isFalse();
    }

    private static void assertStatusCode(String statusLine, int expected) {
        byte[] bytes = statusLine.getBytes(StandardCharsets.US_ASCII);
        MemorySegment seg = MemorySegment.ofArray(bytes);
        int parsed = CommunityHttpBufferOps.parseStatusCode(seg, 0, bytes.length);
        assertThat(parsed).isEqualTo(expected);
    }

    private static String readAscii(LoanedBuffer buffer, int length) {
        byte[] bytes = buffer.segment().asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}