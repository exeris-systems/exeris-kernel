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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A path-template route compiled once at build time. {@code segments} holds the literal text for
 * fixed segments and the placeholder name (braces stripped) for {@code {name}} segments;
 * {@code isParam[i]} flags which is which. Matching requires the same segment count and equality on
 * every literal segment; placeholders capture the corresponding request segment.
 */
record PathTemplateRoute(HttpMethod method, String[] segments, boolean[] isParam,
                         int paramCount, HttpHandler handler) {

    // Package-private factory for the HttpRouter builder.
    /* default */ static PathTemplateRoute compile(HttpMethod method, String path, HttpHandler handler) {
        String[] raw = path.split("/", -1);
        boolean[] flags = new boolean[raw.length];
        String[] parsed = new String[raw.length];
        int params = 0;
        for (int i = 0; i < raw.length; i++) {
            String segment = raw[i];
            if (segment.length() > 2 && segment.charAt(0) == '{'
                    && segment.charAt(segment.length() - 1) == '}') {
                flags[i] = true;
                parsed[i] = segment.substring(1, segment.length() - 1);
                params++;
            } else {
                parsed[i] = segment;
            }
        }
        return new PathTemplateRoute(method, parsed, flags, params, handler);
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
        if (requestSegments.length != segments.length) {
            return null;
        }
        Map<String, String> captured = new LinkedHashMap<>(paramCount * 2);
        for (int i = 0; i < segments.length; i++) {
            String literal = segments[i];
            String actual = requestSegments[i];
            boolean param = isParam[i];
            if (!param && !literal.equals(actual)) {
                return null;
            }
            if (param && actual.isEmpty()) {
                return null;
            }
            if (param) {
                captured.put(literal, actual);
            }
        }
        return new RouteMatch(handler, Map.copyOf(captured));
    }
}
