/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's pure ALPN decision: which name an offer yields, given whether the listener may select
 * {@code h2}, and which callback argument a configured ALPN list produces.
 */
@DisplayName("CommunityAlpnSelector — the selection rule and its callback argument")
class CommunityAlpnSelectorTest {

    /** Which of the two protocols the server may select. */
    private enum Allowed {
        BOTH(true, true), HTTP_1_1_ONLY(false, true), H2_ONLY(true, false);

        private final boolean h2;
        private final boolean http11;

        Allowed(boolean h2, boolean http11) {
            this.h2 = h2;
            this.http11 = http11;
        }
    }

    @Nested
    @DisplayName("when h2 is selectable")
    class H2Selectable {

        @Test
        @DisplayName("an offer of h2 and http/1.1 selects h2")
        void prefersH2() {
            assertSelects(Allowed.BOTH, "h2", "h2", "http/1.1");
        }

        @Test
        @DisplayName("an offer of http/1.1 alone selects http/1.1")
        void fallsBackToHttp11() {
            assertSelects(Allowed.BOTH, "http/1.1", "http/1.1");
        }

        @Test
        @DisplayName("an offer of h2 alone selects h2")
        void selectsH2Alone() {
            assertSelects(Allowed.BOTH, "h2", "h2");
        }

        @Test
        @DisplayName("an empty offer selects nothing")
        void selectsNothingFromEmptyOffer() {
            assertSelectsNothing(Allowed.BOTH);
        }

        @Test
        @DisplayName("a zero-length entry is skipped, so the name behind it is still selected")
        void zeroLengthEntryIsSkipped() {
            assertSelectsFromRaw(Allowed.BOTH, "h2", concat(new byte[] {0}, entry("h2")));
        }

        @Test
        @DisplayName("near-miss names h2c and http/1.10 are not selected")
        void nearMissesAreNotSelected() {
            assertSelectsNothing(Allowed.BOTH, "h2c", "http/1.10");
        }

        @Test
        @DisplayName("a name that is a prefix of a listed one is not selected")
        void prefixesAreNotSelected() {
            assertSelectsNothing(Allowed.BOTH, "h", "http/1.");
        }

        @Test
        @DisplayName("a malformed length ends the scan, so a name behind it is not selected")
        void malformedLengthEndsTheScan() {
            assertSelectsNothingFromRaw(Allowed.BOTH, concat(new byte[] {9, 'x'}, entry("http/1.1")));
        }

        @Test
        @DisplayName("an entry whose length runs past the end of the offer is not selected")
        void truncatedEntryIsNotSelected() {
            assertSelectsNothingFromRaw(Allowed.BOTH, new byte[] {9, 'h', '2'});
        }
    }

    @Nested
    @DisplayName("when h2 is not selectable")
    class H2NotSelectable {

        @Test
        @DisplayName("an offer of h2 and http/1.1 selects http/1.1")
        void selectsHttp11() {
            assertSelects(Allowed.HTTP_1_1_ONLY, "http/1.1", "h2", "http/1.1");
        }

        @Test
        @DisplayName("an offer of http/1.1 alone selects http/1.1")
        void selectsHttp11Alone() {
            assertSelects(Allowed.HTTP_1_1_ONLY, "http/1.1", "http/1.1");
        }

        @Test
        @DisplayName("an offer of h2 alone selects nothing, so the handshake completes without ALPN")
        void refusesH2Alone() {
            assertSelectsNothing(Allowed.HTTP_1_1_ONLY, "h2");
        }

        @Test
        @DisplayName("an empty offer selects nothing")
        void selectsNothingFromEmptyOffer() {
            assertSelectsNothing(Allowed.HTTP_1_1_ONLY);
        }

        @Test
        @DisplayName("near-miss names h2c and http/1.10 are not selected")
        void nearMissesAreNotSelected() {
            assertSelectsNothing(Allowed.HTTP_1_1_ONLY, "h2c", "http/1.10");
        }

        @Test
        @DisplayName("a zero-length entry is skipped, so http/1.1 behind it is still selected")
        void zeroLengthEntryIsSkipped() {
            assertSelectsFromRaw(Allowed.HTTP_1_1_ONLY, "http/1.1", concat(new byte[] {0}, entry("http/1.1")));
        }

        @Test
        @DisplayName("a malformed length ends the scan, so a name behind it is not selected")
        void malformedLengthEndsTheScan() {
            assertSelectsNothingFromRaw(Allowed.HTTP_1_1_ONLY, concat(new byte[] {9, 'x'}, entry("http/1.1")));
        }
    }

    @Nested
    @DisplayName("when only h2 is selectable")
    class H2Only {

        @Test
        @DisplayName("an offer of h2 and http/1.1 selects h2")
        void selectsH2() {
            assertSelects(Allowed.H2_ONLY, "h2", "http/1.1", "h2");
        }

        @Test
        @DisplayName("an offer of h2 alone selects h2")
        void selectsH2Alone() {
            assertSelects(Allowed.H2_ONLY, "h2", "h2");
        }

        @Test
        @DisplayName("an offer of http/1.1 alone selects nothing, since the server never listed it")
        void refusesHttp11Alone() {
            assertSelectsNothing(Allowed.H2_ONLY, "http/1.1");
        }

        @Test
        @DisplayName("near-miss names h2c and http/1.10 are not selected")
        void nearMissesAreNotSelected() {
            assertSelectsNothing(Allowed.H2_ONLY, "h2c", "http/1.10");
        }

        @Test
        @DisplayName("a zero-length entry is skipped, so h2 behind it is still selected")
        void zeroLengthEntryIsSkipped() {
            assertSelectsFromRaw(Allowed.H2_ONLY, "h2", concat(new byte[] {0}, entry("h2")));
        }
    }

    @Nested
    @DisplayName("callback argument from the configured ALPN list")
    class Argument {

        @Test
        @DisplayName("a list naming h2 makes h2 selectable")
        void h2ListIsSelectable() {
            assertThat(CommunityAlpnSelector.argumentFor(List.of("h2", "http/1.1")))
                    .isEqualTo(OptionalLong.of(CommunityAlpnSelector.ARG_HTTP_2_SELECTABLE));
        }

        @Test
        @DisplayName("a list naming only http/1.1 makes only http/1.1 selectable")
        void http11ListIsHttp11Only() {
            assertThat(CommunityAlpnSelector.argumentFor(List.of("http/1.1")))
                    .isEqualTo(OptionalLong.of(CommunityAlpnSelector.ARG_HTTP_1_1_ONLY));
        }

        @Test
        @DisplayName("a list naming only h2 makes only h2 selectable")
        void h2OnlyListIsH2Only() {
            assertThat(CommunityAlpnSelector.argumentFor(List.of("h2")))
                    .isEqualTo(OptionalLong.of(CommunityAlpnSelector.ARG_HTTP_2_ONLY));
        }

        @Test
        @DisplayName("a list naming neither protocol registers no callback")
        void unknownListHasNoArgument() {
            assertThat(CommunityAlpnSelector.argumentFor(List.of("h3"))).isEqualTo(OptionalLong.empty());
            assertThat(CommunityAlpnSelector.argumentFor(List.of())).isEqualTo(OptionalLong.empty());
        }
    }

    private static void assertSelects(Allowed allowed, String expected, String... offered) {
        assertSelectsFromRaw(allowed, expected, wire(offered).toArray(ValueLayout.JAVA_BYTE));
    }

    private static void assertSelectsFromRaw(Allowed allowed, String expected, byte[] raw) {
        MemorySegment offer = MemorySegment.ofArray(raw);
        Optional<CommunityAlpnSelector.Selection> chosen =
                CommunityAlpnSelector.select(offer, allowed.h2, allowed.http11);
        assertThat(chosen).as("the selected protocol").isPresent();
        CommunityAlpnSelector.Selection selection = chosen.orElseThrow();
        byte[] name = offer.asSlice(selection.offset(), selection.length()).toArray(ValueLayout.JAVA_BYTE);
        assertThat(new String(name, StandardCharsets.US_ASCII)).isEqualTo(expected);
    }

    private static void assertSelectsNothing(Allowed allowed, String... offered) {
        assertSelectsNothingFromRaw(allowed, wire(offered).toArray(ValueLayout.JAVA_BYTE));
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head);
        out.writeBytes(tail);
        return out.toByteArray();
    }

    private static void assertSelectsNothingFromRaw(Allowed allowed, byte[] raw) {
        assertThat(CommunityAlpnSelector.select(MemorySegment.ofArray(raw), allowed.h2, allowed.http11)).isEmpty();
    }

    private static byte[] entry(String name) {
        byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
        byte[] entry = new byte[ascii.length + 1];
        entry[0] = (byte) ascii.length;
        System.arraycopy(ascii, 0, entry, 1, ascii.length);
        return entry;
    }

    private static MemorySegment wire(String... names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String name : names) {
            out.writeBytes(entry(name));
        }
        return MemorySegment.ofArray(out.toByteArray());
    }
}
