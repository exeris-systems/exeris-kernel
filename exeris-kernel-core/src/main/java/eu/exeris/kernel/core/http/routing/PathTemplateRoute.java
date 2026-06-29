/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A path-template route compiled once at build time. {@code segments} is the ordered list of
 * literal and placeholder segments; matching requires the same segment count and equality on every
 * literal segment, and each placeholder captures the corresponding request segment.
 */
record PathTemplateRoute(HttpMethod method, List<Segment> segments, int paramCount, HttpHandler handler) {

    /** One compiled path segment: a {@code {name}} placeholder ({@code param=true}) or a literal. */
    /* default */ record Segment(boolean param, String text) {}

    // Package-private factory for the HttpRouter builder.
    /* default */ static PathTemplateRoute compile(HttpMethod method, String path, HttpHandler handler) {
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
        return new PathTemplateRoute(method, List.copyOf(parsed), params, handler);
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
     * Returns a {@link RouteMatch} (handler + captured params) when {@code requestSegments} matches
     * this template, or {@code null} when it does not. An empty request segment never satisfies a
     * placeholder.
     *
     * @param requestSegments the request path pre-split on {@code '/'} (eager-split once by the router
     *     and reused across every template, hence an array rather than varargs)
     */
    @SuppressWarnings("PMD.UseVarargs") // call site passes a pre-split array, never a varargs list
    /* default */ RouteMatch toMatch(String[] requestSegments) {
        if (requestSegments.length != segments.size()) {
            return null;
        }
        Map<String, String> captured = LinkedHashMap.newLinkedHashMap(paramCount);
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            String actual = requestSegments[i];
            if (!segment.param() && !segment.text().equals(actual)) {
                return null;
            }
            if (segment.param() && actual.isEmpty()) {
                return null;
            }
            if (segment.param()) {
                captured.put(segment.text(), actual);
            }
        }
        return new RouteMatch(handler, Map.copyOf(captured));
    }
}
