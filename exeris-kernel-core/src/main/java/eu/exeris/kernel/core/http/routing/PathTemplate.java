/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compiled path template: literal and {@code {name}} placeholder segments, matched by position.
 *
 * <p>Extracted from {@code PathTemplateRoute} when the streaming table gained templates. The two
 * tables carry different handler types and nothing else, so leaving the matching inside the
 * respond-once route would have meant a second copy of the placeholder rules — and a copy is exactly
 * how the two tables would drift into disagreeing about what {@code /x/{id}} means.
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
     * Returns whether {@code requestSegments} matches this template.
     *
     * <p>Separate from {@link #capture} so a miss — the common case when a router walks its template
     * list — costs no map at all. A hit walks the segments a second time, which is a handful of
     * comparisons against an allocation that would otherwise be made and discarded on every miss.
     *
     * @param requestSegments the request path pre-split on {@code '/'} (split once by the router and
     *                        reused across every template, hence an array rather than varargs)
     * @return {@code true} if every literal segment is equal and every placeholder has a non-empty value
     */
    @SuppressWarnings("PMD.UseVarargs") // call site passes a pre-split array, never a varargs list
    /* default */ boolean matches(String[] requestSegments) {
        if (requestSegments.length != segments.size()) {
            return false;
        }
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
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

    /**
     * Captures the placeholder values from segments already known to {@link #matches}.
     *
     * @param requestSegments the matching request path pre-split on {@code '/'}
     * @return the captured parameters; empty when the template declares none
     */
    @SuppressWarnings("PMD.UseVarargs") // call site passes a pre-split array, never a varargs list
    /* default */ Map<String, String> capture(String[] requestSegments) {
        if (paramCount == 0) {
            return Map.of();
        }
        Map<String, String> captured = LinkedHashMap.newLinkedHashMap(paramCount);
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.param()) {
                captured.put(segment.text(), requestSegments[i]);
            }
        }
        return Map.copyOf(captured);
    }
}
