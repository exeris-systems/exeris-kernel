/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A compiled path template: literal and {@code {name}} placeholder segments, matched by position.
 *
 * <p>Shared by both the respond-once and the streaming route tables, which carry different handler
 * types and nothing else: keeping the placeholder rules in one type is what stops the two tables from
 * drifting into disagreeing about what {@code /x/{id}} means.
 *
 * @param segments   ordered literal and placeholder segments
 * @param paramCount number of placeholder segments, sized once for the capture map
 */
record PathTemplate(List<Segment> segments, int paramCount) {

    /** One compiled path segment: a {@code {name}} placeholder ({@code param=true}) or a literal. */
    /* default */ record Segment(boolean param, String text) {}

    /**
     * Compiles a path into a template.
     *
     * @param path the registered path
     * @return the compiled template
     * @throws IllegalArgumentException if any segment uses braces but is not a well-formed
     *                                  {@code {name}} placeholder
     */
    /* default */ static PathTemplate compile(String path) {
        String[] raw = path.split("/", -1);
        List<Segment> parsed = new ArrayList<>(raw.length);
        int params = 0;
        for (String rawSegment : raw) {
            Segment segment = parseSegment(rawSegment, path);
            parsed.add(segment);
            if (segment.param()) {
                params++;
            }
        }
        return new PathTemplate(List.copyOf(parsed), params);
    }

    /**
     * Returns whether a registered path should be compiled as a template rather than stored as an
     * exact key.
     *
     * @param path the registered path
     * @return {@code true} if the path carries a brace
     */
    /* default */ static boolean isTemplate(String path) {
        return path.indexOf('{') >= 0;
    }

    // A well-formed placeholder is "{name}" with exactly one '{' (first char) and one '}' (last char);
    // any other brace usage is a malformed template and is rejected at build time rather than silently
    // compiled into a never-matching literal.
    private static Segment parseSegment(String rawSegment, String path) {
        boolean wellFormedParam = rawSegment.length() > 2
                && rawSegment.charAt(0) == '{'
                && rawSegment.indexOf('{', 1) < 0
                && rawSegment.indexOf('}') == rawSegment.length() - 1;
        if (wellFormedParam) {
            return new Segment(true, rawSegment.substring(1, rawSegment.length() - 1));
        }
        if (rawSegment.indexOf('{') >= 0 || rawSegment.indexOf('}') >= 0) {
            throw new IllegalArgumentException(
                    "Malformed path-template segment '" + rawSegment + "' in route " + path);
        }
        return new Segment(false, rawSegment);
    }

    /**
     * Returns whether {@code path} matches this template.
     *
     * <p>Walks the {@code '/'}-separated segments of {@code path} in place rather than pre-splitting it
     * into a {@code String[]}: a literal segment is a region comparison and a placeholder only needs
     * its bounds, so no per-segment array or {@code String} is needed to decide a match.
     *
     * <p>Separate from {@link #capture} so a miss — the common case when a router walks its template
     * list — costs nothing at all. A hit walks the segments a second time, which is a handful of
     * comparisons against a map that would otherwise be built and discarded on every miss.
     *
     * @param path the request path, query already stripped
     * @return {@code true} if every literal segment is equal and every placeholder has a non-empty value
     */
    /* default */ boolean matches(String path) {
        int count = segments.size();
        int start = 0;
        for (int i = 0; i < count; i++) {
            int slash = path.indexOf('/', start);
            boolean lastSegment = i == count - 1;
            // The template's segment count must be the path's: a trailing slash still yields an
            // (empty) segment, matching the -1 limit the split used to carry.
            if (lastSegment != (slash < 0)) {
                return false;
            }
            int end = lastSegment ? path.length() : slash;
            Segment segment = segments.get(i);
            if (segment.param()) {
                if (end == start) {
                    return false;
                }
            } else if (!literalEquals(segment.text(), path, start, end)) {
                return false;
            }
            start = end + 1;
        }
        return true;
    }

    /**
     * Captures the placeholder values from a path already known to {@link #matches}.
     *
     * <p>Only placeholder segments are materialised; literals are never turned into {@code String}s.
     *
     * @param path the matching request path, query already stripped
     * @return the captured parameters; empty when the template declares none
     */
    /* default */ Map<String, String> capture(String path) {
        if (paramCount == 0) {
            return Map.of();
        }
        Map<String, String> captured = HashMap.newHashMap(paramCount);
        int count = segments.size();
        int start = 0;
        for (int i = 0; i < count; i++) {
            int slash = path.indexOf('/', start);
            int end = i == count - 1 ? path.length() : slash;
            Segment segment = segments.get(i);
            if (segment.param()) {
                captured.put(segment.text(), path.substring(start, end));
            }
            start = end + 1;
        }
        // Wrapped rather than copied: the map is built here and escapes only as an unmodifiable
        // view, so a second immutable copy would buy nothing. Map.copyOf discarded insertion order
        // anyway, so nothing downstream can have depended on it.
        return Collections.unmodifiableMap(captured);
    }

    private static boolean literalEquals(String text, String path, int start, int end) {
        return text.length() == end - start && path.regionMatches(start, text, 0, text.length());
    }
}
