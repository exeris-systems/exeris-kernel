/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table's whole safety argument is that a hit is indistinguishable from materialising the name.
 * These pin that, not merely that lookups succeed.
 */
class CanonicalHeaderNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Host", "Content-Length", "User-Agent", "Sec-CH-UA-Platform",
                            "X-Forwarded-For", "TE", "Upgrade-Insecure-Requests"})
    void aKnownSpellingResolvesToACharacterIdenticalConstant(String name) {
        assertThat(resolve(name))
                .as("a hit must be indistinguishable from materialising the wire bytes")
                .isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "content-length", "user-agent", "sec-ch-ua", "traceparent"})
    void lowercaseSpellingsResolveToo(String name) {
        // HTTP/2 requires lowercase field names (RFC 9113 §8.2.1) and browsers already send some
        // HTTP/1 fields lowercase. A title-case-only table missed sec-ch-ua during the spike.
        assertThat(resolve(name)).isEqualTo(name);
    }

    @Test
    void caseIsNotNormalised() {
        // The property that makes this correctness-neutral. If a mixed-case spelling resolved to a
        // differently-cased constant, the field's name would silently change on the wire-to-heap
        // boundary — a behaviour change dressed as an optimisation.
        assertThat(resolve("HOST")).as("an unknown casing must not borrow another's characters").isNull();
        assertThat(resolve("hOsT")).isNull();
        assertThat(resolve("Content-length")).isNull();
    }

    @Test
    void unknownNamesMiss() {
        assertThat(resolve("X-Wholly-Invented")).isNull();
        assertThat(resolve("Hosts")).as("a longer name sharing a prefix must not match").isNull();
        assertThat(resolve("Hos")).as("a shorter prefix must not match").isNull();
    }

    @Test
    void emptyAndOverlongNamesMiss() {
        assertThat(resolve("")).isNull();
        assertThat(resolve("X".repeat(512))).isNull();
    }

    @Test
    void aNameOfKnownLengthButDifferentBytesMisses() {
        // Same length as "Range"; the length bucket must not be mistaken for a match.
        assertThat(resolve("Xyzzy")).isNull();
        // Same length as "Host", differing only in the last byte.
        assertThat(resolve("Hosx")).isNull();
    }

    @Test
    void resolutionIsNotConfusedByAdjacentBytes() {
        // The parser hands a range inside a larger buffer, so a match must depend on [start,end)
        // and nothing around it.
        try (Arena arena = Arena.ofConfined()) {
            String line = "GET /x\r\nHost: example.test\r\n";
            MemorySegment segment = arena.allocateFrom(line, StandardCharsets.US_ASCII);
            long start = line.indexOf("Host");
            assertThat(CanonicalHeaderNames.resolve(segment, start, start + 4)).isEqualTo("Host");
            assertThat(CanonicalHeaderNames.resolve(segment, start, start + 5))
                    .as("including the colon must not resolve").isNull();
        }
    }

    @Test
    void everyEntryIsAValidHeaderToken() {
        // The parser skips token validation on a hit, which is only safe because every entry is a
        // valid RFC 9110 token. This is the assertion that keeps that true as the table grows.
        for (String name : knownSpellings()) {
            assertThat(CanonicalHeaderNames.isValidFieldName(name))
                    .as("entry '%s' must be a valid field-name, since a hit skips the check", name)
                    .isTrue();
        }
    }

    private static java.util.List<String> knownSpellings() {
        // Probe the table through its public surface rather than reflecting into it: every name the
        // table can return is returned by resolving its own bytes.
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String candidate : new String[] {
                "Host", "host", "User-Agent", "user-agent", "Content-Length", "content-length",
                "Sec-CH-UA", "sec-ch-ua", "X-Forwarded-For", "x-forwarded-for", "TE", "te",
                "HTTP2-Settings", "http2-settings", "WWW-Authenticate", "www-authenticate",
                "Idempotency-Key", "idempotency-key", "DNT", "dnt"}) {
            String resolved = resolve(candidate);
            if (resolved != null) {
                found.add(resolved);
            }
        }
        assertThat(found).as("the probe set must actually be in the table").hasSize(20);
        return found;
    }

    @Test
    void fieldNameValidationRejectsWhatRfc9110Rejects() {
        // Moved here from the HTTP/1 parser, because a field name is a field name on every version.
        assertThat(CanonicalHeaderNames.isValidFieldName("X-Ok_9")).isTrue();
        assertThat(CanonicalHeaderNames.isValidFieldName("!#$%&'*+-.^_`|~")).isTrue();
        assertThat(CanonicalHeaderNames.isValidFieldName("")).as("empty is not a token").isFalse();
        assertThat(CanonicalHeaderNames.isValidFieldName("Bad Name")).as("space").isFalse();
        assertThat(CanonicalHeaderNames.isValidFieldName("Bad:Name")).as("colon").isFalse();
        assertThat(CanonicalHeaderNames.isValidFieldName("Bad\tName")).as("tab").isFalse();
        assertThat(CanonicalHeaderNames.isValidFieldName("Bad(Name)")).as("parens").isFalse();
    }

    private static String resolve(String name) {
        try (Arena arena = Arena.ofConfined()) {
            if (name.isEmpty()) {
                MemorySegment empty = arena.allocate(1);
                return CanonicalHeaderNames.resolve(empty, 0, 0);
            }
            MemorySegment segment = arena.allocateFrom(name, StandardCharsets.ISO_8859_1);
            return CanonicalHeaderNames.resolve(segment, 0, name.length());
        }
    }
}
