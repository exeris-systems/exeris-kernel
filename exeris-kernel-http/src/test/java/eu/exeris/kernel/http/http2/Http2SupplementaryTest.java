/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: Http2 — Supplementary Contract Tests")
class Http2SupplementaryTest {

    // =========================================================================
    // Http2FrameParser — parseHeader() (native-endian variant)
    // =========================================================================

    @Nested
    @DisplayName("Http2FrameParser.parseHeaderBigEndian() — big-endian")
    class FrameParserBigEndian {

        @Test
        @DisplayName("DATA frame via parseHeaderBigEndian round-trip")
        void dataFrameBigEndian() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 512,
                        Http2FrameType.DATA.code(), 0x01, 7);

                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.length()).isEqualTo(512);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.DATA);
                assertThat(header.isEndStream()).isTrue();
                assertThat(header.streamId()).isEqualTo(7);
            }
        }

        @Test
        @DisplayName("isPadded flag is set correctly")
        void isPaddedFlag() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 0,
                        Http2FrameType.DATA.code(), 0x08, 1);
                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.isPadded()).isTrue();
                assertThat(header.isEndStream()).isFalse();
            }
        }

        @Test
        @DisplayName("isPriority flag is set correctly")
        void isPriorityFlag() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 0,
                        Http2FrameType.HEADERS.code(), 0x20, 3);
                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.isPriority()).isTrue();
            }
        }

        @Test
        @DisplayName("RST_STREAM frame type resolves")
        void rstStreamFrameType() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 4,
                        Http2FrameType.RST_STREAM.code(), 0, 5);
                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.RST_STREAM);
                assertThat(header.streamId()).isEqualTo(5);
            }
        }
    }

    // =========================================================================
    // Http2FrameEncoder.writeSettings() — with parameters
    // =========================================================================

    @Nested
    @DisplayName("Http2FrameEncoder.writeSettings() — with parameters")
    class FrameEncoderSettings {

        @Test
        @DisplayName("SETTINGS with two parameters encodes correct payload length")
        void settingsWithTwoParams() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9 + 12);
                long written = Http2FrameEncoder.writeSettings(buf, 0, 0, false,
                        Http2Settings.ID_HEADER_TABLE_SIZE, 8192,
                        Http2Settings.ID_INITIAL_WINDOW_SIZE, 131072);

                assertThat(written).isEqualTo(9 + 12);
                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.SETTINGS);
                assertThat(header.length()).isEqualTo(12);
                assertThat(header.isAck()).isFalse();
            }
        }

        @Test
        @DisplayName("SETTINGS ACK has length zero and flag 0x01")
        void settingsAckZeroLength() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeSettings(buf, 0, 0, true);
                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.isAck()).isTrue();
                assertThat(header.length()).isZero();
            }
        }

        @Test
        @DisplayName("SETTINGS parameter values are correctly encoded and layout")
        void settingsParamValue() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9 + 6);
                Http2FrameEncoder.writeSettings(buf, 0, 0, false,
                        Http2Settings.ID_MAX_FRAME_SIZE, 32768);

                // param id at offset 9: big-endian 16-bit
                int id = ((buf.get(ValueLayout.JAVA_BYTE, 9) & 0xFF) << 8)
                        | (buf.get(ValueLayout.JAVA_BYTE, 10) & 0xFF);
                assertThat(id).isEqualTo(Http2Settings.ID_MAX_FRAME_SIZE);

                // param value at offset 11: big-endian 32-bit
                int val = ((buf.get(ValueLayout.JAVA_BYTE, 11) & 0xFF) << 24)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 12) & 0xFF) << 16)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 13) & 0xFF) << 8)
                        | (buf.get(ValueLayout.JAVA_BYTE, 14) & 0xFF);
                assertThat(val).isEqualTo(32768);
            }
        }
    }

    // =========================================================================
    // Http2FlowController — missing branches
    // =========================================================================

    @Nested
    @DisplayName("Http2FlowController — extended contract")
    class FlowControllerExtended {

        @Test
        @DisplayName("Constructor rejects negative initial window")
        void constructorRejectsNegativeInitialWindow() {
            assertThatThrownBy(() -> new Http2FlowController(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("initialWindowSize");
        }

        @Test
        @DisplayName("increment(0) returns false (zero increment is invalid)")
        void zeroIncrementInvalid() {
            Http2FlowController fc = new Http2FlowController();
            assertThat(fc.increment(0)).isFalse();
            assertThat(fc.windowSize()).isEqualTo(Http2FlowController.DEFAULT_WINDOW_SIZE);
        }

        @Test
        @DisplayName("increment(-1) returns false")
        void negativeIncrementInvalid() {
            Http2FlowController fc = new Http2FlowController();
            assertThat(fc.increment(-1)).isFalse();
        }

        @Test
        @DisplayName("updateInitialWindowSize adjusts window by delta")
        void updateInitialWindowSize() {
            Http2FlowController fc = new Http2FlowController(65_535);
            fc.updateInitialWindowSize(65_535, 131_070);
            assertThat(fc.windowSize()).isEqualTo(131_070);
        }

        @Test
        @DisplayName("updateInitialWindowSize reduces window to zero")
        void updateInitialWindowSizeToZero() {
            Http2FlowController fc = new Http2FlowController(65_535);
            fc.updateInitialWindowSize(65_535, 0);
            assertThat(fc.windowSize()).isZero();
        }

        @Test
        @DisplayName("updateInitialWindowSize throws when new window would exceed MAX_WINDOW_SIZE")
        void updateInitialWindowSizeExceedsMax() {
            Http2FlowController fc = new Http2FlowController(Http2FlowController.MAX_WINDOW_SIZE);
            assertThatThrownBy(() -> fc.updateInitialWindowSize(1, Http2FlowController.MAX_WINDOW_SIZE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("consume(0) is valid and no-op")
        void consumeZero() {
            Http2FlowController fc = new Http2FlowController();
            assertThat(fc.consume(0)).isTrue();
            assertThat(fc.windowSize()).isEqualTo(Http2FlowController.DEFAULT_WINDOW_SIZE);
        }

        @Test
        @DisplayName("Window can be fully drained")
        void windowFullyDrained() {
            Http2FlowController fc = new Http2FlowController(100);
            assertThat(fc.consume(100)).isTrue();
            assertThat(fc.windowSize()).isZero();
            assertThat(fc.consume(1)).isFalse();
        }

        @Test
        @DisplayName("Window becomes negative after SETTINGS_INITIAL_WINDOW_SIZE reduction (RFC 7540 §6.9.2)")
        void windowCanBeNegativeAfterSettingsReduction() {
            Http2FlowController fc = new Http2FlowController(65_535);
            fc.consume(30_000);
            assertThat(fc.windowSize()).isEqualTo(35_535);

            fc.updateInitialWindowSize(65_535, 10_000);
            assertThat(fc.windowSize()).isNegative();
        }

        @Test
        @DisplayName("Negative window blocks consume until incremented back above zero")
        void negativeWindowBlocksConsumeUntilIncremented() {
            Http2FlowController fc = new Http2FlowController(65_535);
            fc.updateInitialWindowSize(65_535, 0);
            assertThat(fc.windowSize()).isZero();

            assertThat(fc.consume(1)).isFalse();

            fc.increment(100);
            assertThat(fc.consume(50)).isTrue();
        }
    }

    // =========================================================================
    // Http2Settings — full withSetting() coverage
    // =========================================================================

    @Nested
    @DisplayName("Http2Settings.withSetting() — all identifiers")
    class SettingsWithSetting {

        @Test
        @DisplayName("ID_ENABLE_PUSH = 0 disables push")
        void disablePush() {
            Http2Settings s = Http2Settings.DEFAULTS.withSetting(
                    Http2Settings.ID_ENABLE_PUSH, 0);
            assertThat(s.enablePush()).isFalse();
            assertThat(Http2Settings.DEFAULTS.enablePush()).isTrue();
        }

        @Test
        @DisplayName("ID_MAX_CONCURRENT_STREAMS")
        void maxConcurrentStreams() {
            Http2Settings s = Http2Settings.DEFAULTS.withSetting(
                    Http2Settings.ID_MAX_CONCURRENT_STREAMS, 100);
            assertThat(s.maxConcurrentStreams()).isEqualTo(100);
        }

        @Test
        @DisplayName("ID_MAX_HEADER_LIST_SIZE")
        void maxHeaderListSize() {
            Http2Settings s = Http2Settings.DEFAULTS.withSetting(
                    Http2Settings.ID_MAX_HEADER_LIST_SIZE, 65536);
            assertThat(s.maxHeaderListSize()).isEqualTo(65536);
        }

        @Test
        @DisplayName("Unknown identifier is a no-op (returns same settings)")
        void unknownIdentifierIsNoop() {
            Http2Settings s = Http2Settings.DEFAULTS.withSetting(0xFF, 999);
            assertThat(s).isEqualTo(Http2Settings.DEFAULTS);
        }

        @Test
        @DisplayName("DEFAULTS maxConcurrentStreams and maxHeaderListSize are -1")
        void defaultsUnlimited() {
            assertThat(Http2Settings.DEFAULTS.maxConcurrentStreams()).isEqualTo(-1);
            assertThat(Http2Settings.DEFAULTS.maxHeaderListSize()).isEqualTo(-1L);
        }
    }

    // =========================================================================
    // Http2FrameEncoder.writeRstStream() and writeGoAway()
    // =========================================================================

    @Nested
    @DisplayName("Http2FrameEncoder — RST_STREAM and GOAWAY")
    class RstStreamAndGoAway {

        @Test
        @DisplayName("writeRstStream encodes stream ID and error code")
        void rstStream() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(13);
                long written = Http2FrameEncoder.writeRstStream(buf, 0, 7,
                        Http2ErrorCode.PROTOCOL_ERROR.code());

                assertThat(written).isEqualTo(13);
                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.RST_STREAM);
                assertThat(header.length()).isEqualTo(4);
                assertThat(header.streamId()).isEqualTo(7);

                // error code big-endian at offset 9
                int errCode = ((buf.get(ValueLayout.JAVA_BYTE, 9) & 0xFF) << 24)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 10) & 0xFF) << 16)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 11) & 0xFF) << 8)
                        | (buf.get(ValueLayout.JAVA_BYTE, 12) & 0xFF);
                assertThat(errCode).isEqualTo(Http2ErrorCode.PROTOCOL_ERROR.code());
            }
        }

        @Test
        @DisplayName("writeGoAway encodes lastStreamId and error code on stream 0")
        void goAway() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(17);
                long written = Http2FrameEncoder.writeGoAway(buf, 0, 15,
                        Http2ErrorCode.NO_ERROR.code());

                assertThat(written).isEqualTo(17);
                Http2FrameParser.FrameHeader header =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.GOAWAY);
                assertThat(header.length()).isEqualTo(8);
                assertThat(header.streamId()).isZero();

                // lastStreamId big-endian at offset 9 (R bit masked)
                int lastId = ((buf.get(ValueLayout.JAVA_BYTE, 9) & 0x7F) << 24)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 10) & 0xFF) << 16)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 11) & 0xFF) << 8)
                        | (buf.get(ValueLayout.JAVA_BYTE, 12) & 0xFF);
                assertThat(lastId).isEqualTo(15);

                int errCode = ((buf.get(ValueLayout.JAVA_BYTE, 13) & 0xFF) << 24)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 14) & 0xFF) << 16)
                        | ((buf.get(ValueLayout.JAVA_BYTE, 15) & 0xFF) << 8)
                        | (buf.get(ValueLayout.JAVA_BYTE, 16) & 0xFF);
                assertThat(errCode).isEqualTo(Http2ErrorCode.NO_ERROR.code());
            }
        }
    }

    // =========================================================================
    // Http2FrameParser.parseHeader() — delegates to parseHeaderBigEndian
    // =========================================================================

    @Nested
    @DisplayName("Http2FrameParser.parseHeader() — delegates correctly")
    class ParseHeaderDelegation {

        @Test
        @DisplayName("parseHeader() and parseHeaderBigEndian() produce identical results")
        void parseHeaderMatchesBigEndian() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 256,
                        Http2FrameType.HEADERS.code(), 0x05, 11);

                Http2FrameParser.FrameHeader direct =
                        Http2FrameParser.parseHeader(buf, 0);
                Http2FrameParser.FrameHeader bigEndian =
                        Http2FrameParser.parseHeaderBigEndian(buf, 0);

                assertThat(direct.length()).isEqualTo(bigEndian.length());
                assertThat(direct.type()).isEqualTo(bigEndian.type());
                assertThat(direct.flags()).isEqualTo(bigEndian.flags());
                assertThat(direct.streamId()).isEqualTo(bigEndian.streamId());
            }
        }
    }

    // =========================================================================
    // Http2StreamState — enum completeness
    // =========================================================================

    @Nested
    @DisplayName("Http2StreamState — RFC 7540 §5.1 state coverage")
    class StreamState {

        @Test
        @DisplayName("All RFC 7540 §5.1 states are defined")
        void allStatesPresent() {
            assertThat(Http2StreamState.values()).containsExactly(
                    Http2StreamState.IDLE,
                    Http2StreamState.RESERVED_LOCAL,
                    Http2StreamState.RESERVED_REMOTE,
                    Http2StreamState.OPEN,
                    Http2StreamState.HALF_CLOSED_LOCAL,
                    Http2StreamState.HALF_CLOSED_REMOTE,
                    Http2StreamState.CLOSED
            );
        }

        @Test
        @DisplayName("valueOf returns correct instance")
        void valueOf() {
            assertThat(Http2StreamState.valueOf("OPEN")).isEqualTo(Http2StreamState.OPEN);
            assertThat(Http2StreamState.valueOf("CLOSED")).isEqualTo(Http2StreamState.CLOSED);
        }
    }
}




