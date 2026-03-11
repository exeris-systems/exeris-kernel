/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: Http1Codec — Connection State Coordination")
class Http1CodecTest {

    @Nested
    @DisplayName("Initial state")
    class InitialState {

        @Test
        @DisplayName("keep-alive is true by default (HTTP/1.1 semantics)")
        void defaultKeepAlive() {
            assertThat(new Http1Codec().isKeepAlive()).isTrue();
        }

        @Test
        @DisplayName("pendingContentLength is NO_BODY initially")
        void defaultNobody() {
            assertThat(new Http1Codec().pendingContentLength())
                    .isEqualTo(Http1Codec.NO_BODY);
        }
    }

    @Nested
    @DisplayName("parseRequestLine()")
    class ParseRequestLine {

        @Test
        @DisplayName("Delegates to Http1RequestParser correctly")
        void delegatesToParser() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, "GET /api HTTP/1.1\r\n");
                Http1RequestParser.RequestLine rl =
                        codec.parseRequestLine(seg, 0, seg.byteSize());
                assertThat(rl).isNotNull();
                assertThat(rl.method()).isEqualTo("GET");
                assertThat(rl.target()).isEqualTo("/api");
            }
        }
    }

    @Nested
    @DisplayName("parseHeaders() — connection state updates")
    class ParseHeadersState {

        @Test
        @DisplayName("Connection: close sets keepAlive=false")
        void connectionCloseDisablesKeepAlive() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, "Connection: close\r\n\r\n");
                codec.parseHeaders(seg, 0, seg.byteSize());
                assertThat(codec.isKeepAlive()).isFalse();
            }
        }

        @Test
        @DisplayName("Connection: keep-alive (explicit) keeps keepAlive=true")
        void connectionKeepAliveExplicit() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, "Connection: keep-alive\r\n\r\n");
                codec.parseHeaders(seg, 0, seg.byteSize());
                assertThat(codec.isKeepAlive()).isTrue();
            }
        }

        @Test
        @DisplayName("Content-Length header is captured")
        void contentLengthCaptured() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, "Content-Length: 42\r\n\r\n");
                codec.parseHeaders(seg, 0, seg.byteSize());
                assertThat(codec.pendingContentLength()).isEqualTo(42L);
            }
        }

        @Test
        @DisplayName("Subsequent parseHeaders resets pendingContentLength")
        void contentLengthReset() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg1 = toSegment(arena, "Content-Length: 10\r\n\r\n");
                codec.parseHeaders(seg1, 0, seg1.byteSize());
                assertThat(codec.pendingContentLength()).isEqualTo(10L);

                MemorySegment seg2 = toSegment(arena, "Host: example.com\r\n\r\n");
                codec.parseHeaders(seg2, 0, seg2.byteSize());
                assertThat(codec.pendingContentLength()).isEqualTo(Http1Codec.NO_BODY);
            }
        }

        @Test
        @DisplayName("Returns -1 when header block is incomplete")
        void incompleteHeadersReturnsNegative() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, "Host: example.com\r\n");
                assertThat(codec.parseHeaders(seg, 0, seg.byteSize())).isNegative();
            }
        }
    }

    @Nested
    @DisplayName("writeStatusAndConnection()")
    class WriteStatusAndConnection {

        @Test
        @DisplayName("Writes keep-alive Connection header when keepAlive=true")
        void writesKeepAlive() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(256);
                long pos = codec.writeStatusAndConnection(buf, 0, 200, "OK");
                String result = readAscii(buf, 0, pos);
                assertThat(result).contains("HTTP/1.1 200 OK\r\n");
                assertThat(result).contains("Connection: keep-alive\r\n");
            }
        }

        @Test
        @DisplayName("Writes close Connection header when keepAlive=false")
        void writesClose() {
            Http1Codec codec = new Http1Codec();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, "Connection: close\r\n\r\n");
                codec.parseHeaders(seg, 0, seg.byteSize());

                MemorySegment buf = arena.allocate(256);
                long pos = codec.writeStatusAndConnection(buf, 0, 200, "OK");
                assertThat(readAscii(buf, 0, pos)).contains("Connection: close\r\n");
            }
        }
    }

    @Nested
    @DisplayName("parseHeaders() — DoS limits")
    class DosLimits {

        @Test
        @DisplayName("Exceeding maxHeaders throws Http1ParseException")
        void tooManyHeaders() {
            try (Arena arena = Arena.ofConfined()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    sb.append("X-Header-").append(i).append(": value\r\n");
                }
                sb.append("\r\n");
                MemorySegment seg = toSegment(arena, sb.toString());
                assertThatThrownBy(() ->
                        Http1RequestParser.parseHeaders(seg, 0, seg.byteSize(),
                                3, 8192, (n, v) -> {}))
                        .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                        .hasMessageContaining("too many");
            }
        }

        @Test
        @DisplayName("Exceeding maxHeaderSize throws Http1ParseException")
        void headerTooLarge() {
            try (Arena arena = Arena.ofConfined()) {
                String bigValue = "X".repeat(200);
                MemorySegment seg = toSegment(arena, "X-Big: " + bigValue + "\r\n\r\n");
                assertThatThrownBy(() ->
                        Http1RequestParser.parseHeaders(seg, 0, seg.byteSize(),
                                100, 50, (n, v) -> {}))
                        .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                        .hasMessageContaining("size limit");
            }
        }

        @Test
        @DisplayName("Headers within limits parse without exception")
        void withinLimits() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, "X-Ok: val\r\n\r\n");
                long endPos = Http1RequestParser.parseHeaders(seg, 0, seg.byteSize(),
                        10, 100, (n, v) -> {});
                assertThat(endPos).isPositive();
            }
        }
    }

    // =========================================================================
    // Http1ChunkedEncoder — edge cases
    // =========================================================================

    @Nested
    @DisplayName("Http1ChunkedEncoder — edge cases")
    class ChunkedEncoderEdgeCases {

        @Test
        @DisplayName("writeChunkHeader encodes 0xFF size as hex 'ff\\r\\n'")
        void writeChunkHeaderLargeHexSize() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(8);
                long pos = Http1ChunkedEncoder.writeChunkHeader(buf, 0, 0xFF);
                assertThat(readAscii(buf, 0, pos)).isEqualTo("ff\r\n");
            }
        }

        @Test
        @DisplayName("writeChunkHeader encodes 0x10000 size as 'ffff\\r\\n' — no, as '10000\\r\\n'")
        void writeChunkHeaderSixDigitHex() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(16);
                long pos = Http1ChunkedEncoder.writeChunkHeader(buf, 0, 0x10000);
                assertThat(readAscii(buf, 0, pos)).isEqualTo("10000\r\n");
            }
        }

        @Test
        @DisplayName("writeChunkTrailer writes exactly CRLF (2 bytes)")
        void writeChunkTrailerExactlyTwoBytes() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(4);
                buf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x55);
                buf.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x55);
                buf.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x55);
                long pos = Http1ChunkedEncoder.writeChunkTrailer(buf, 0);
                assertThat(pos).isEqualTo(2);
                assertThat(buf.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) '\r');
                assertThat(buf.get(ValueLayout.JAVA_BYTE, 1)).isEqualTo((byte) '\n');
                assertThat(buf.get(ValueLayout.JAVA_BYTE, 2)).isEqualTo((byte) 0x55);
            }
        }

        @Test
        @DisplayName("Multiple sequential chunks produce correct wire format")
        void multipleChunksSequentially() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(512);
                byte[] p1 = "Hello".getBytes(StandardCharsets.US_ASCII);
                byte[] p2 = "World".getBytes(StandardCharsets.US_ASCII);

                MemorySegment d1 = arena.allocate(p1.length);
                MemorySegment.copy(MemorySegment.ofArray(p1), ValueLayout.JAVA_BYTE, 0,
                        d1, ValueLayout.JAVA_BYTE, 0, p1.length);
                MemorySegment d2 = arena.allocate(p2.length);
                MemorySegment.copy(MemorySegment.ofArray(p2), ValueLayout.JAVA_BYTE, 0,
                        d2, ValueLayout.JAVA_BYTE, 0, p2.length);

                long pos = 0;
                pos = Http1ChunkedEncoder.writeChunk(buf, pos, d1);
                pos = Http1ChunkedEncoder.writeChunk(buf, pos, d2);
                pos = Http1ChunkedEncoder.writeLastChunk(buf, pos);

                assertThat(readAscii(buf, 0, pos))
                        .isEqualTo("5\r\nHello\r\n5\r\nWorld\r\n0\r\n\r\n");
            }
        }

        @Test
        @DisplayName("writeChunkHeader for size=0 emits '0\\r\\n' (empty chunk, not last-chunk)")
        void writeChunkHeaderZeroSize() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(8);
                long pos = Http1ChunkedEncoder.writeChunkHeader(buf, 0, 0);
                assertThat(readAscii(buf, 0, pos)).isEqualTo("0\r\n");
            }
        }
    }

    // =========================================================================
    // h2c Upgrade Detection — RFC 7540 §3.2
    // =========================================================================

    @Nested
    @DisplayName("h2c Upgrade Detection — RFC 7540 §3.2")
    class H2cUpgrade {

        @Test
        @DisplayName("No Upgrade header → upgradeState NONE")
        void noUpgradeHeader_stateNone() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();
                String headers = "host: example.com\r\ncontent-length: 0\r\n\r\n";
                codec.parseHeaders(toSegment(arena, headers), 0, headers.length());
                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.NONE);
                assertThat(codec.h2cSettingsPayload()).isNull();
            }
        }

        @Test
        @DisplayName("Upgrade: h2c without HTTP2-Settings → upgradeState NONE (incomplete upgrade)")
        void upgradeH2cWithoutSettings_stateNone() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();
                String headers = "upgrade: h2c\r\nconnection: Upgrade\r\n\r\n";
                codec.parseHeaders(toSegment(arena, headers), 0, headers.length());
                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.NONE);
            }
        }

        @Test
        @DisplayName("HTTP2-Settings without Upgrade → upgradeState NONE")
        void settingsWithoutUpgrade_stateNone() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();
                String headers = "http2-settings: AAMAAABkAAQAAP__\r\n\r\n";
                codec.parseHeaders(toSegment(arena, headers), 0, headers.length());
                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.NONE);
            }
        }

        /**
         * RFC 7540 §3.2: a valid h2c upgrade requires both
         * "Upgrade: h2c" and "HTTP2-Settings: <base64url>" headers.
         */
        @Test
        @DisplayName("Upgrade: h2c + HTTP2-Settings → upgradeState H2C_REQUESTED")
        void validH2cUpgrade_stateRequested() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();
                String settings = "AAMAAABkAAQAAP__";
                String headers = "upgrade: h2c\r\n"
                        + "connection: Upgrade, HTTP2-Settings\r\n"
                        + "http2-settings: " + settings + "\r\n\r\n";
                codec.parseHeaders(toSegment(arena, headers), 0, headers.length());

                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.H2C_REQUESTED);
                assertThat(codec.h2cSettingsPayload()).isEqualTo(settings);
            }
        }

        @Test
        @DisplayName("Empty HTTP2-Settings payload is valid (RFC 7540 §3.2 allows empty)")
        void emptySettingsPayload_valid() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();
                String headers = """
                        upgrade: h2c\r
                        connection: Upgrade\r
                        http2-settings: \r
                        \r
                        """;
                codec.parseHeaders(toSegment(arena, headers), 0, headers.length());

                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.H2C_REQUESTED);
                assertThat(codec.h2cSettingsPayload()).isEmpty();
            }
        }

        @Test
        @DisplayName("Upgrade header is case-insensitive (h2C, H2C)")
        void upgradeHeaderCaseInsensitive() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();
                String headers = """
                        Upgrade: h2c\r
                        HTTP2-Settings: AAA\r
                        \r
                        """;
                codec.parseHeaders(toSegment(arena, headers), 0, headers.length());
                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.H2C_REQUESTED);
            }
        }

        @Test
        @DisplayName("upgradeState resets to NONE on each parseHeaders call")
        void upgradeStateResetsPerRequest() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();

                String upgradeRequest = "upgrade: h2c\r\nhttp2-settings: AAA\r\n\r\n";
                codec.parseHeaders(toSegment(arena, upgradeRequest), 0, upgradeRequest.length());
                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.H2C_REQUESTED);

                String normalRequest = "host: example.com\r\n\r\n";
                codec.parseHeaders(toSegment(arena, normalRequest), 0, normalRequest.length());
                assertThat(codec.upgradeState()).isEqualTo(Http1Codec.UpgradeState.NONE);
                assertThat(codec.h2cSettingsPayload()).isNull();
            }
        }

        /**
         * RFC 7540 §3.2: the server MUST respond with "101 Switching Protocols" before
         * switching to HTTP/2. The 101 response MUST include "Connection: Upgrade" and
         * "Upgrade: h2c".
         */
        @Test
        @DisplayName("writeH2cSwitchingProtocols emits correct 101 response wire bytes")
        void writeH2cSwitchingProtocols_correctWireFormat() {
            try (Arena arena = Arena.ofConfined()) {
                Http1Codec codec = new Http1Codec();
                MemorySegment buf = arena.allocate(256);
                long pos = codec.writeH2cSwitchingProtocols(buf, 0);

                String written = readAscii(buf, 0, pos);
                assertThat(written)
                        .startsWith("HTTP/1.1 101 Switching Protocols\r\n")
                        .contains("Connection: Upgrade\r\n")
                        .contains("Upgrade: h2c\r\n")
                        .endsWith("\r\n");
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static MemorySegment toSegment(Arena arena, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.US_ASCII);
        MemorySegment seg = arena.allocate(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), ValueLayout.JAVA_BYTE, 0,
                seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static String readAscii(MemorySegment seg, long start, long end) {
        int len = (int) (end - start);
        byte[] bytes = new byte[len];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, start, bytes, 0, len);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
