/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PathTemplate} matches and captures by scanning the request path in place. It used to take
 * the path pre-split into a {@code String[]}; the split is what these tests hold it to, because the
 * segment rules it encoded — a trailing slash is a segment, an empty placeholder does not match — are
 * easy to lose when the array goes away.
 */
@DisplayName("PathTemplate")
class PathTemplateTest {

    /**
     * The split-based matcher this class replaced, kept as the oracle. Agreement across a corpus is
     * the claim worth testing: the scanner is a re-expression, not a new rule set.
     */
    private static boolean referenceMatches(PathTemplate template, String path) {
        String[] requestSegments = path.split("/", -1);
        List<PathTemplate.Segment> segments = template.segments();
        if (requestSegments.length != segments.size()) {
            return false;
        }
        for (int i = 0; i < segments.size(); i++) {
            PathTemplate.Segment segment = segments.get(i);
            String actual = requestSegments[i];
            if (segment.param()) {
                if (actual.isEmpty()) {
                    return false;
                }
            } else if (!segment.text().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    /** The split-based capture this class replaced, kept as the oracle for the same reason. */
    private static Map<String, String> referenceCapture(PathTemplate template, String path) {
        if (template.paramCount() == 0) {
            return Map.of();
        }
        String[] requestSegments = path.split("/", -1);
        Map<String, String> captured = new LinkedHashMap<>();
        for (int i = 0; i < template.segments().size(); i++) {
            PathTemplate.Segment segment = template.segments().get(i);
            if (segment.param()) {
                captured.put(segment.text(), requestSegments[i]);
            }
        }
        return Map.copyOf(captured);
    }

    /** How many (template, path) pairs of the corpus below match; see {@code captureAgrees}. */
    private static final int EXPECTED_MATCHING_PAIRS = 12;

    private static final List<String> TEMPLATES = List.of(
            "/health",
            "/api/orders/{id}",
            "/api/orders/{id}/lines/{line}",
            "/{topic}",
            "/a/{b}/c",
            "/{a}/{b}",
            "/x/",
            "/{topic}//live",
            "/",
            "");

    private static final List<String> PATHS = List.of(
            "/health",
            "/health/",
            "/healthz",
            "/api/orders/42",
            "/api/orders/42/",
            "/api/orders/",
            "/api//42",
            "/api/orders/42/lines/7",
            "/api/orders/42/lines/",
            "/a/b/c",
            "/a//c",
            "/x/",
            "/x",
            "/",
            "//",
            "",
            "events",
            "/live",
            "/events/live");

    @Nested
    @DisplayName("agrees with the split it replaced")
    class Differential {

        @Test
        @DisplayName("matches() agrees on every template/path pair")
        void matchesAgrees() {
            for (String templatePath : TEMPLATES) {
                PathTemplate template = PathTemplate.compile(templatePath);
                for (String path : PATHS) {
                    assertEquals(referenceMatches(template, path), template.matches(path),
                            "template '" + templatePath + "' against path '" + path + "'");
                }
            }
        }

        @Test
        @DisplayName("capture() agrees on every pair that matches")
        void captureAgrees() {
            int matched = 0;
            for (String templatePath : TEMPLATES) {
                PathTemplate template = PathTemplate.compile(templatePath);
                for (String path : PATHS) {
                    if (template.matches(path)) {
                        matched++;
                        assertEquals(referenceCapture(template, path), template.capture(path),
                                "template '" + templatePath + "' against path '" + path + "'");
                    }
                }
            }
            // Pinned, not a floor: a corpus edit that stops producing hits would leave the loop above
            // asserting nothing at all, and a differential test that never compares anything passes.
            assertEquals(EXPECTED_MATCHING_PAIRS, matched);
        }
    }

    @Nested
    @DisplayName("segment rules the split encoded")
    class SegmentRules {

        @Test
        @DisplayName("a trailing slash is a segment, so it does not match a template without one")
        void trailingSlashIsASegment() {
            assertFalse(PathTemplate.compile("/api/orders/{id}").matches("/api/orders/42/"));
        }

        @Test
        @DisplayName("an empty placeholder value does not match")
        void emptyPlaceholderDoesNotMatch() {
            assertFalse(PathTemplate.compile("/api/orders/{id}").matches("/api/orders/"));
        }

        @Test
        @DisplayName("an empty literal segment matches an empty path segment")
        void emptyLiteralMatchesEmptySegment() {
            assertTrue(PathTemplate.compile("/x/").matches("/x/"));
        }

        @Test
        @DisplayName("a longer path does not match a shorter template")
        void extraSegmentDoesNotMatch() {
            assertFalse(PathTemplate.compile("/{topic}").matches("/events/live"));
        }

        @Test
        @DisplayName("a shorter path does not match a longer template")
        void missingSegmentDoesNotMatch() {
            assertFalse(PathTemplate.compile("/api/orders/{id}").matches("/api/orders"));
        }

        @Test
        @DisplayName("a path that runs out mid-template is rejected, not read past its end")
        void pathRunningOutIsRejected() {
            // The walk learns a segment's end from the next '/'. When the path has none left, that
            // end is -1, and a placeholder segment does not notice: -1 is not the segment's start,
            // so it reads as a non-empty capture. Only the segment-count check stands between that
            // and a capture indexing the path with a negative bound. A registered template with an
            // empty segment is what makes it reachable — rare, but representable by the builder.
            assertFalse(PathTemplate.compile("/{topic}//live").matches("/live"));
        }

        @Test
        @DisplayName("a literal is compared over its own bounds, not as a prefix")
        void literalIsNotAPrefixMatch() {
            assertFalse(PathTemplate.compile("/api/{id}").matches("/apix/42"));
            assertFalse(PathTemplate.compile("/apix/{id}").matches("/api/42"));
        }
    }

    @Nested
    @DisplayName("capture")
    class Capture {

        @Test
        @DisplayName("captures every placeholder by name")
        void capturesByName() {
            assertEquals(Map.of("id", "42", "line", "7"),
                    PathTemplate.compile("/api/orders/{id}/lines/{line}")
                            .capture("/api/orders/42/lines/7"));
        }

        @Test
        @DisplayName("captures nothing for a template with no placeholder")
        void capturesNothing() {
            assertEquals(Map.of(), PathTemplate.compile("/health").capture("/health"));
        }

        @Test
        @DisplayName("the captured map is unmodifiable, as HttpExchange.pathParams() promises")
        void capturedMapIsUnmodifiable() {
            Map<String, String> captured =
                    PathTemplate.compile("/api/orders/{id}").capture("/api/orders/42");
            assertThrows(UnsupportedOperationException.class, () -> captured.put("id", "43"));
        }
    }
}
