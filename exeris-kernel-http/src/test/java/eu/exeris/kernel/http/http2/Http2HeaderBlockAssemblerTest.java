/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http2;

import eu.exeris.kernel.http.hpack.TestAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: Http2HeaderBlockAssembler — RFC 7540 §6.2 / §6.10")
class Http2HeaderBlockAssemblerTest {

    private static Arena testArena;
    private static TestAllocator allocator;

    @BeforeAll
    static void setUp() {
        testArena = Arena.ofConfined();
        allocator = new TestAllocator(testArena);
    }

    @AfterAll
    static void tearDown() {
        testArena.close();
    }

    // =========================================================================
    // Single-frame header block (END_HEADERS set on HEADERS frame)
    // =========================================================================

    @Nested
    @DisplayName("Single-frame header block")
    class SingleFrame {

        @Test
        @DisplayName("HEADERS with END_HEADERS — immediately complete, no continuation")
        void headersWithEndHeaders_immediatelyComplete() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            MemorySegment payload = payloadOf(new byte[]{0x01, 0x02, 0x03});

            Http2FrameParser.FrameHeader header = headerWith(
                    Http2FrameType.HEADERS.code(), /*flags*/ 0x04, /*streamId*/ 1);

            asm.beginHeaders(header, payload, 0, 3);

            assertThat(asm.isComplete()).isTrue();
            assertThat(asm.isAwaitingContinuation()).isFalse();
            assertThat(asm.currentStreamId()).isEqualTo(1);

            MemorySegment block = asm.completeBlock();
            assertThat(block.byteSize()).isEqualTo(3);
            assertThat(block.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0x01);
            assertThat(block.get(ValueLayout.JAVA_BYTE, 2)).isEqualTo((byte) 0x03);

            asm.reset();
        }

        @Test
        @DisplayName("completeBlock on incomplete block throws IllegalStateException")
        void completeBlockOnIncompleteThrows() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            assertThatThrownBy(asm::completeBlock)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("HEADERS with zero-length fragment yields empty complete block")
        void headersWithZeroLengthFragment() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            MemorySegment payload = payloadOf(new byte[]{0x01});

            Http2FrameParser.FrameHeader header = headerWith(
                    Http2FrameType.HEADERS.code(), /*END_HEADERS*/ 0x04, 1);

            asm.beginHeaders(header, payload, 0, 0);

            assertThat(asm.isComplete()).isTrue();
            assertThat(asm.completeBlock().byteSize()).isZero();

            asm.reset();
        }
    }

    // =========================================================================
    // Multi-frame header block (CONTINUATION)
    // =========================================================================

    @Nested
    @DisplayName("Multi-frame header block — CONTINUATION")
    class Continuation {

        @Test
        @DisplayName("HEADERS without END_HEADERS → assembler awaits continuation")
        void headersWithoutEndHeaders_awaitsContinuation() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            MemorySegment payload = payloadOf(new byte[]{0x10, 0x20});

            Http2FrameParser.FrameHeader header = headerWith(
                    Http2FrameType.HEADERS.code(), /*flags no END_HEADERS*/ 0x00, 3);

            asm.beginHeaders(header, payload, 0, 2);

            assertThat(asm.isComplete()).isFalse();
            assertThat(asm.isAwaitingContinuation()).isTrue();
            assertThat(asm.currentStreamId()).isEqualTo(3);

            asm.reset();
        }

        @Test
        @DisplayName("HEADERS + CONTINUATION(END_HEADERS) → complete, bytes concatenated")
        void headersThenContinuation_blockConcatenated() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            MemorySegment payload = payloadOf(new byte[]{(byte) 0x82, (byte) 0x84});
            Http2FrameParser.FrameHeader headersFrame = headerWith(
                    Http2FrameType.HEADERS.code(), 0x00, 5);
            asm.beginHeaders(headersFrame, payload, 0, 2);

            MemorySegment contPayload = payloadOf(new byte[]{(byte) 0x86, 0x41});
            Http2FrameParser.FrameHeader contFrame = headerWith(
                    Http2FrameType.CONTINUATION.code(), /*END_HEADERS*/ 0x04, 5);
            asm.appendContinuation(contFrame, contPayload, 0, 2);

            assertThat(asm.isComplete()).isTrue();
            MemorySegment block = asm.completeBlock();
            assertThat(block.byteSize()).isEqualTo(4);
            assertThat(block.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0x82);
            assertThat(block.get(ValueLayout.JAVA_BYTE, 1)).isEqualTo((byte) 0x84);
            assertThat(block.get(ValueLayout.JAVA_BYTE, 2)).isEqualTo((byte) 0x86);
            assertThat(block.get(ValueLayout.JAVA_BYTE, 3)).isEqualTo((byte) 0x41);

            asm.reset();
        }

        @Test
        @DisplayName("HEADERS + two CONTINUATION frames → complete after second END_HEADERS")
        void headersAndTwoContinuations_completeAfterLast() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);

            asm.beginHeaders(
                    headerWith(Http2FrameType.HEADERS.code(), 0x00, 7),
                    payloadOf(new byte[]{0x01}), 0, 1);

            asm.appendContinuation(
                    headerWith(Http2FrameType.CONTINUATION.code(), 0x00, 7),
                    payloadOf(new byte[]{0x02}), 0, 1);

            assertThat(asm.isComplete()).isFalse();

            asm.appendContinuation(
                    headerWith(Http2FrameType.CONTINUATION.code(), 0x04, 7),
                    payloadOf(new byte[]{0x03}), 0, 1);

            assertThat(asm.isComplete()).isTrue();
            assertThat(asm.completeBlock().byteSize()).isEqualTo(3);

            asm.reset();
        }

        @Test
        @DisplayName("reset() clears state for next header block")
        void resetClearsState() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            asm.beginHeaders(
                    headerWith(Http2FrameType.HEADERS.code(), 0x04, 1),
                    payloadOf(new byte[]{(byte) 0x82}), 0, 1);
            asm.reset();

            assertThat(asm.isComplete()).isFalse();
            assertThat(asm.isAwaitingContinuation()).isFalse();
            assertThat(asm.currentStreamId()).isZero();
        }

        @Test
        @DisplayName("buffer grows to hold large header blocks across continuations")
        void bufferGrowsAcrossContinuations() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            int chunkSize = 600;
            byte[] chunk = new byte[chunkSize];
            for (int i = 0; i < chunkSize; i++) {
                chunk[i] = (byte) (i & 0xFF);
            }

            asm.beginHeaders(
                    headerWith(Http2FrameType.HEADERS.code(), 0x00, 9),
                    payloadOf(chunk), 0, chunkSize);

            asm.appendContinuation(
                    headerWith(Http2FrameType.CONTINUATION.code(), 0x04, 9),
                    payloadOf(chunk), 0, chunkSize);

            assertThat(asm.isComplete()).isTrue();
            assertThat(asm.completeBlock().byteSize()).isEqualTo(chunkSize * 2L);

            asm.reset();
        }
    }

    // =========================================================================
    // RFC §6.10 violation detection
    // =========================================================================

    @Nested
    @DisplayName("RFC §6.10 — CONTINUATION protocol violations")
    class Violations {

        /**
         * RFC §6.10: any frame other than CONTINUATION received while awaiting
         * continuation MUST be treated as PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Non-CONTINUATION frame while awaiting continuation throws")
        void nonContinuationWhileAwaiting_throws() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            asm.beginHeaders(
                    headerWith(Http2FrameType.HEADERS.code(), 0x00, 1),
                    payloadOf(new byte[]{0x01}), 0, 1);

            Http2FrameParser.FrameHeader dataFrame = headerWith(
                    Http2FrameType.DATA.code(), 0x00, 1);

            assertThatThrownBy(() -> asm.validateContinuationMode(dataFrame))
                    .isInstanceOf(Http2HeaderBlockAssembler.ContinuationViolationException.class)
                    .hasMessageContaining("PROTOCOL_ERROR");

            asm.reset();
        }

        /**
         * RFC §6.10: CONTINUATION on a different stream MUST be treated as PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("CONTINUATION on wrong stream throws")
        void continuationWrongStream_throws() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            asm.beginHeaders(
                    headerWith(Http2FrameType.HEADERS.code(), 0x00, 1),
                    payloadOf(new byte[]{0x01}), 0, 1);

            Http2FrameParser.FrameHeader wrongStream = headerWith(
                    Http2FrameType.CONTINUATION.code(), 0x04, 3);

            assertThatThrownBy(() -> asm.appendContinuation(
                    wrongStream, payloadOf(new byte[]{0x02}), 0, 1))
                    .isInstanceOf(Http2HeaderBlockAssembler.ContinuationViolationException.class)
                    .hasMessageContaining("stream");

            asm.reset();
        }

        /**
         * RFC §6.10: CONTINUATION outside of a header block sequence (no preceding
         * HEADERS without END_HEADERS) MUST be treated as PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Unexpected CONTINUATION (no preceding HEADERS) throws")
        void continuationWithoutHeaders_throws() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            Http2FrameParser.FrameHeader cont = headerWith(
                    Http2FrameType.CONTINUATION.code(), 0x04, 1);

            assertThatThrownBy(() -> asm.appendContinuation(
                    cont, payloadOf(new byte[]{0x01}), 0, 1))
                    .isInstanceOf(Http2HeaderBlockAssembler.ContinuationViolationException.class)
                    .hasMessageContaining("outside");
        }

        @Test
        @DisplayName("HEADERS while already awaiting CONTINUATION throws")
        void headersWhileAwaitingContinuation_throws() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            asm.beginHeaders(
                    headerWith(Http2FrameType.HEADERS.code(), 0x00, 1),
                    payloadOf(new byte[]{0x01}), 0, 1);

            Http2FrameParser.FrameHeader second = headerWith(
                    Http2FrameType.HEADERS.code(), 0x04, 3);

            assertThatThrownBy(() -> asm.beginHeaders(
                    second, payloadOf(new byte[]{0x02}), 0, 1))
                    .isInstanceOf(Http2HeaderBlockAssembler.ContinuationViolationException.class)
                    .hasMessageContaining("CONTINUATION");

            asm.reset();
        }

        @Test
        @DisplayName("validateContinuationMode is no-op when not awaiting continuation")
        void validateContinuationMode_noopWhenNotAwaiting() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            Http2FrameParser.FrameHeader anyFrame = headerWith(
                    Http2FrameType.DATA.code(), 0x00, 1);
            asm.validateContinuationMode(anyFrame);
        }

        @Test
        @DisplayName("Header block exceeding MAX_HEADER_BLOCK_SIZE throws")
        void blockExceedsLimit_throws() {
            Http2HeaderBlockAssembler asm = new Http2HeaderBlockAssembler(allocator);
            int halfMax = 33_000;
            byte[] chunk = new byte[halfMax];

            asm.beginHeaders(
                    headerWith(Http2FrameType.HEADERS.code(), 0x00, 1),
                    payloadOf(chunk), 0, halfMax);

            assertThatThrownBy(() -> asm.appendContinuation(
                    headerWith(Http2FrameType.CONTINUATION.code(), 0x04, 1),
                    payloadOf(chunk), 0, halfMax))
                    .isInstanceOf(Http2HeaderBlockAssembler.ContinuationViolationException.class)
                    .hasMessageContaining("limit");

            asm.reset();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Http2FrameParser.FrameHeader headerWith(int type, int flags, int streamId) {
        return new Http2FrameParser.FrameHeader(0, type, flags, streamId);
    }

    private MemorySegment payloadOf(byte[] data) {
        MemorySegment seg = testArena.allocate(Math.max(data.length, 1));
        MemorySegment.copy(MemorySegment.ofArray(data), ValueLayout.JAVA_BYTE, 0,
                seg, ValueLayout.JAVA_BYTE, 0, data.length);
        return seg;
    }
}


