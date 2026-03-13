/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.http2;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Http2SupplementaryTest {

    @Test
    void writesAndParsesSettingsHeader() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(9 + 12);
            long written = Http2FrameEncoder.writeSettings(buf, 0, 0, false,
                    Http2Settings.ID_HEADER_TABLE_SIZE, 8192,
                    Http2Settings.ID_INITIAL_WINDOW_SIZE, 131072);

            Http2FrameParser.FrameHeader header = Http2FrameParser.parseHeaderBigEndian(buf, 0);
            assertThat(written).isEqualTo(21);
            assertThat(header.frameType()).isEqualTo(Http2FrameType.SETTINGS);
            assertThat(header.length()).isEqualTo(12);
            assertThat(header.isAck()).isFalse();
        }
    }

    @Test
    void settingsAckRejectsPayload() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(32);

            assertThatThrownBy(() -> Http2FrameEncoder.writeSettings(buf, 0, 0, true,
                    Http2Settings.ID_ENABLE_PUSH, 1))
                    .isInstanceOfSatisfying(Http2FrameEncoder.FrameEncodingException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4006);
                        assertThat(ex.rawArgs()).hasSize(1);
                        assertThat(ex.rawArgs()[0]).isEqualTo("SETTINGS ACK frame must carry no parameters; got: 2");
                    });
        }
    }

    @Test
    void settingsRejectsOddParamsLength() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(32);

            assertThatThrownBy(() -> Http2FrameEncoder.writeSettings(buf, 0, 0, false,
                    Http2Settings.ID_MAX_FRAME_SIZE))
                    .isInstanceOfSatisfying(Http2FrameEncoder.FrameEncodingException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4006);
                        assertThat(ex.rawArgs()).hasSize(1);
                        assertThat(ex.rawArgs()[0]).isEqualTo("SETTINGS params must be pairs of [id, value]; odd length: 1");
                    });
        }
    }
}
