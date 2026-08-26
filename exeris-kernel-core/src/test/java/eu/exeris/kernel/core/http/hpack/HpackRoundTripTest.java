/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.hpack;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 7541 Appendix C — HPACK Encoder/Decoder integration tests.
 *
 * <p>Validates encode → decode round-trips using the reference examples
 * from the RFC.
 */
@DisplayName("L1: HpackEncoder + HpackDecoder — Round-Trip Integration")
class HpackRoundTripTest {

    private static Arena testArena;
    private static MemoryAllocator testAllocator;

    @BeforeAll
    static void initAllocator() {
        testArena = Arena.ofShared(); //NOPMD DirectArena — test harness
        testAllocator = new TestAllocator(testArena);
    }

    @AfterAll
    static void closeAllocator() {
        testArena.close();
    }

    @Nested
    @DisplayName("Round-trip: encode → decode preserves header fields")
    class RoundTrip {

        @Test
        @DisplayName("Single header: custom-key: custom-value")
        void singleCustomHeader() {
            roundTrip(List.<String[]>of(
                    header("custom-key", "custom-value")));
        }

        @Test
        @DisplayName("Multiple headers with static matches")
        void multipleHeadersStaticMatches() {
            roundTrip(List.of(
                    header(":method", "GET"),
                    header(":path", "/"),
                    header(":scheme", "https"),
                    header(":authority", "www.example.com")
            ));
        }

        @Test
        @DisplayName("Headers with incremental indexing reuse")
        void incrementalIndexing() {
            HpackDynamicTable encTable = new HpackDynamicTable(4096);
            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackEncoder encoder =
                    new HpackEncoder(encTable, testAllocator, true);
            HpackDecoder decoder =
                    new HpackDecoder(decTable, testAllocator, 65_536);

            MemorySegment buf = testArena.allocate(4096);
            long pos = 0;
            pos = encoder.encodeHeader(buf, pos, "x-custom", "first", false);
            pos = encoder.encodeHeader(buf, pos, "x-custom", "second", false);

            List<String[]> decoded = new ArrayList<>();
            decoder.decode(buf, 0, pos, (name, value, sensitive) ->
                    decoded.add(new String[]{name, value}));

            assertThat(decoded).hasSize(2);
            assertThat(decoded.get(0)).containsExactly("x-custom", "first");
            assertThat(decoded.get(1)).containsExactly("x-custom", "second");
        }

        @Test
        @DisplayName("Never-indexed headers survive round-trip with sensitive flag")
        void neverIndexed() {
            HpackDynamicTable encTable = new HpackDynamicTable(4096);
            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackEncoder encoder =
                    new HpackEncoder(encTable, testAllocator, false);
            HpackDecoder decoder =
                    new HpackDecoder(decTable, testAllocator, 65_536);

            MemorySegment buf = testArena.allocate(4096);
            long pos = encoder.encodeHeader(
                    buf, 0, "authorization", "secret", true);

            List<Boolean> sensFlags = new ArrayList<>();
            decoder.decode(buf, 0, pos, (name, value, sensitive) ->
                    sensFlags.add(sensitive));

            assertThat(sensFlags).containsExactly(true);
        }

        private void roundTrip(List<String[]> headers) {
            HpackDynamicTable encTable = new HpackDynamicTable(4096);
            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackEncoder encoder =
                    new HpackEncoder(encTable, testAllocator, true);
            HpackDecoder decoder =
                    new HpackDecoder(decTable, testAllocator, 65_536);

            MemorySegment buf = testArena.allocate(4096);
            long pos = 0;
            for (String[] hdr : headers) {
                pos = encoder.encodeHeader(buf, pos, hdr[0], hdr[1], false);
            }

            List<String[]> decoded = new ArrayList<>();
            decoder.decode(buf, 0, pos, (name, value, sensitive) ->
                    decoded.add(new String[]{name, value}));

            assertThat(decoded).hasSize(headers.size());
            for (int idx = 0; idx < headers.size(); idx++) {
                assertThat(decoded.get(idx))
                        .containsExactly(headers.get(idx));
            }
        }

        private String[] header(String name, String value) {
            return new String[]{name, value};
        }
    }
}


