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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: Http2FrameParser + Http2FrameEncoder — Wire Format Contract")
class Http2FrameCodecTest {

    @Nested
    @DisplayName("Frame header round-trip")
    class FrameHeaderRoundTrip {

        @Test
        @DisplayName("DATA frame header survives encode → parse")
        void dataFrameRoundTrip() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 1024, Http2FrameType.DATA.code(), 0x01, 5);

                Http2FrameParser.FrameHeader header = Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.length()).isEqualTo(1024);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.DATA);
                assertThat(header.isEndStream()).isTrue();
                assertThat(header.streamId()).isEqualTo(5);
            }
        }

        @Test
        @DisplayName("HEADERS frame with END_HEADERS flag")
        void headersFrameEndHeaders() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameCodec.writeHeadersHeader(buf, 0, 1, 256, false, true);

                Http2FrameParser.FrameHeader header = Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.HEADERS);
                assertThat(header.isEndHeaders()).isTrue();
                assertThat(header.isEndStream()).isFalse();
                assertThat(header.length()).isEqualTo(256);
                assertThat(header.streamId()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("SETTINGS ACK frame")
        void settingsAckFrame() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeSettings(buf, 0, 0, true);

                Http2FrameParser.FrameHeader header = Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.SETTINGS);
                assertThat(header.isAck()).isTrue();
                assertThat(header.length()).isZero();
                assertThat(header.streamId()).isZero();
            }
        }

        @Test
        @DisplayName("WINDOW_UPDATE frame round-trip")
        void windowUpdateRoundTrip() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(13);
                Http2FrameEncoder.writeWindowUpdate(buf, 0, 3, 65535);

                Http2FrameParser.FrameHeader header = Http2FrameParser.parseHeaderBigEndian(buf, 0);
                assertThat(header.frameType()).isEqualTo(Http2FrameType.WINDOW_UPDATE);
                assertThat(header.length()).isEqualTo(4);
                assertThat(header.streamId()).isEqualTo(3);
            }
        }
    }

    @Nested
    @DisplayName("Http2FrameType — resolution")
    class FrameTypeResolution {

        @Test
        @DisplayName("All defined types resolve correctly")
        void allDefinedTypes() {
            for (Http2FrameType type : Http2FrameType.values()) {
                assertThat(Http2FrameType.fromCode(type.code())).isEqualTo(type);
            }
        }

        @Test
        @DisplayName("Unknown type code returns null")
        void unknownTypeReturnsNull() {
            assertThat(Http2FrameType.fromCode(0xFF)).isNull();
        }
    }

    @Nested
    @DisplayName("Http2ErrorCode — resolution")
    class ErrorCodeResolution {

        @Test
        @DisplayName("All defined error codes resolve correctly")
        void allDefinedCodes() {
            for (Http2ErrorCode code : Http2ErrorCode.values()) {
                assertThat(Http2ErrorCode.fromCode(code.code())).isEqualTo(code);
            }
        }

        @Test
        @DisplayName("Unknown error code returns null")
        void unknownCodeReturnsNull() {
            assertThat(Http2ErrorCode.fromCode(0xFF)).isNull();
        }
    }

    @Nested
    @DisplayName("Http2Settings — immutable withSetting()")
    class SettingsContract {

        @Test
        @DisplayName("DEFAULTS has RFC 7540 values")
        void defaults() {
            assertThat(Http2Settings.DEFAULTS.headerTableSize()).isEqualTo(4096);
            assertThat(Http2Settings.DEFAULTS.enablePush()).isTrue();
            assertThat(Http2Settings.DEFAULTS.initialWindowSize()).isEqualTo(65_535);
            assertThat(Http2Settings.DEFAULTS.maxFrameSize()).isEqualTo(16_384);
        }

        @Test
        @DisplayName("withSetting produces a new immutable instance")
        void withSettingImmutable() {
            Http2Settings updated = Http2Settings.DEFAULTS.withSetting(
                    Http2Settings.ID_HEADER_TABLE_SIZE, 8192);
            assertThat(updated.headerTableSize()).isEqualTo(8192);
            assertThat(Http2Settings.DEFAULTS.headerTableSize()).isEqualTo(4096);
        }
    }

    @Nested
    @DisplayName("Http2FlowController — window management")
    class FlowControllerContract {

        @Test
        @DisplayName("Initial window size is 65535")
        void initialWindowSize() {
            Http2FlowController fc = new Http2FlowController();
            assertThat(fc.windowSize()).isEqualTo(65_535);
        }

        @Test
        @DisplayName("consume() reduces window, returns false when insufficient")
        void consumeReducesWindow() {
            Http2FlowController fc = new Http2FlowController();
            assertThat(fc.consume(1024)).isTrue();
            assertThat(fc.windowSize()).isEqualTo(65_535 - 1024);

            assertThat(fc.consume(70_000)).isFalse();
            assertThat(fc.windowSize()).isEqualTo(65_535 - 1024);
        }

        @Test
        @DisplayName("increment() increases window")
        void incrementIncreasesWindow() {
            Http2FlowController fc = new Http2FlowController();
            fc.consume(1024);
            assertThat(fc.increment(512)).isTrue();
            assertThat(fc.windowSize()).isEqualTo(65_535 - 1024 + 512);
        }

        @Test
        @DisplayName("increment() rejects overflow beyond MAX_WINDOW_SIZE")
        void incrementRejectsOverflow() {
            Http2FlowController fc = new Http2FlowController(Integer.MAX_VALUE - 10);
            assertThat(fc.increment(20)).isFalse();
        }
    }

    @Nested
    @DisplayName("Http2FrameCodec — parseAndValidate + constructor validation")
    class FrameCodecSecurity {

        @Test
        @DisplayName("parseAndValidate throws when frame length > maxFrameSize (FRAME_SIZE_ERROR)")
        void parseAndValidateRejectsOversizedFrame() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 32_768,
                        Http2FrameType.DATA.code(), 0x00, 1);

                Http2FrameCodec codec = new Http2FrameCodec(16_384);
                assertThatThrownBy(() -> codec.parseAndValidate(buf, 0))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("FRAME_SIZE_ERROR");
            }
        }

        @Test
        @DisplayName("parseAndValidate accepts frame at exactly maxFrameSize")
        void parseAndValidateAcceptsFrameAtLimit() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(9);
                Http2FrameEncoder.writeHeader(buf, 0, 16_384,
                        Http2FrameType.DATA.code(), 0x00, 1);

                Http2FrameCodec codec = new Http2FrameCodec(16_384);
                Http2FrameParser.FrameHeader header = codec.parseAndValidate(buf, 0);
                assertThat(header.length()).isEqualTo(16_384);
            }
        }

        @Test
        @DisplayName("setMaxFrameSize rejects value below 16384")
        void setMaxFrameSizeRejectsBelowMin() {
            Http2FrameCodec codec = new Http2FrameCodec();
            assertThatThrownBy(() -> codec.setMaxFrameSize(8_192))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("setMaxFrameSize rejects value above 16777215")
        void setMaxFrameSizeRejectsAboveMax() {
            Http2FrameCodec codec = new Http2FrameCodec();
            assertThatThrownBy(() -> codec.setMaxFrameSize(16_777_216))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Constructor rejects out-of-range maxFrameSize")
        void constructorRejectsOutOfRange() {
            assertThatThrownBy(() -> new Http2FrameCodec(1024))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // CONTINUATION frame encoding — RFC 7540 §6.10
    // =========================================================================

    @Nested
    @DisplayName("CONTINUATION frame — §6.10")
    class ContinuationEncoding {

        @Test
        @DisplayName("writeContinuation without END_HEADERS — type 0x09, flags 0x00")
        void writeContinuationNoEndHeaders() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16);
                long written = Http2FrameEncoder.writeContinuation(seg, 0, 5, 100, false);

                assertThat(written).isEqualTo(9);
                Http2FrameParser.FrameHeader h = Http2FrameParser.parseHeaderBigEndian(seg, 0);
                assertThat(h.type()).isEqualTo(Http2FrameType.CONTINUATION.code());
                assertThat(h.length()).isEqualTo(100);
                assertThat(h.streamId()).isEqualTo(5);
                assertThat(h.isEndHeaders()).isFalse();
                assertThat(h.flags()).isEqualTo(0x00);
            }
        }

        @Test
        @DisplayName("writeContinuation with END_HEADERS — flag 0x04 set")
        void writeContinuationWithEndHeaders() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16);
                Http2FrameEncoder.writeContinuation(seg, 0, 7, 50, true);

                Http2FrameParser.FrameHeader h = Http2FrameParser.parseHeaderBigEndian(seg, 0);
                assertThat(h.isEndHeaders()).isTrue();
                assertThat(h.flags()).isEqualTo(0x04);
                assertThat(h.streamId()).isEqualTo(7);
                assertThat(h.type()).isEqualTo(0x09);
            }
        }

        @Test
        @DisplayName("writeContinuation returns exactly FRAME_HEADER_SIZE bytes")
        void writeContinuationReturnSize() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16);
                long written = Http2FrameEncoder.writeContinuation(seg, 0, 1, 0, true);
                assertThat(written).isEqualTo(Http2FrameParser.FRAME_HEADER_SIZE);
            }
        }
    }
}
