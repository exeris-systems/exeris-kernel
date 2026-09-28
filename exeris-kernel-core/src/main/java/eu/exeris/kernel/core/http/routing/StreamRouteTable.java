/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.StreamMatch;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The streaming half of the routing table: exact paths first, then templates.
 *
 * <p>Its own type because it is its own table. Folding it back into {@link HttpRouter} would put two
 * independent resolution strategies in one class and, more to the point, would hide that the streaming
 * table must keep exactly the precedence rules the respond-once one has — an exact route wins over a
 * template — rather than the two tables silently drifting apart.
 *
 * @since 0.11
 */
final class StreamRouteTable {

    // Keyed by method first so a lookup needs no key object: a (method, path) record would be
    // allocated and discarded on every request just to probe the map.
    private final Map<HttpMethod, Map<String, HttpStreamHandler>> exact;
    // An array, not a List: a for-each over an array compiles to an indexed walk, while over a List
    // it creates an iterator, which is an allocation on every request wherever the loop has not been
    // compiled with escape analysis.
    private final TemplateEntry[] templates;
    // Every method that has at least one stream route, exact or templated.
    private final Set<HttpMethod> methods;

    private StreamRouteTable(Map<HttpMethod, Map<String, HttpStreamHandler>> exact,
                             List<TemplateEntry> templates) {
        Map<HttpMethod, Map<String, HttpStreamHandler>> copied = new EnumMap<>(HttpMethod.class);
        exact.forEach((method, byPath) -> copied.put(method, Map.copyOf(byPath)));
        this.exact = copied;
        this.templates = templates.toArray(new TemplateEntry[0]);
        Set<HttpMethod> served = EnumSet.noneOf(HttpMethod.class);
        served.addAll(copied.keySet());
        for (TemplateEntry entry : this.templates) {
            served.add(entry.method());
        }
        this.methods = served;
    }

    /**
     * Returns whether any stream route, exact or templated, is registered for {@code method}.
     *
     * @param method request method; {@code null} answers {@code false}
     * @return {@code true} if a request with this method can resolve to a stream route
     */
    /* default */ boolean serves(HttpMethod method) {
        return method != null && methods.contains(method);
    }

    /**
     * Resolves a streaming route, or returns {@code null} when none matches.
     *
     * <p>An exact route wins over a template, mirroring respond-once precedence: a deployment that
     * registers a literal alongside a template meant the literal to be the special case.
     *
     * @param method request method
     * @param path   request path, query already stripped
     * @return the match, or {@code null}
     */
    /* default */ StreamMatch resolve(HttpMethod method, String path) {
        Map<String, HttpStreamHandler> byPath = exact.get(method);
        HttpStreamHandler literal = byPath == null ? null : byPath.get(path);
        if (literal != null) {
            return StreamMatch.exact(literal);
        }
        for (TemplateEntry entry : templates) {
            if (entry.method() == method && entry.template().matches(path)) {
                return new StreamMatch(entry.handler(), entry.template().capture(path));
            }
        }
        return null;
    }

    private record TemplateEntry(HttpMethod method, PathTemplate template,
                                 HttpStreamHandler handler) {}

    /** Accumulates registrations, compiling each path once at build time. */
    /* default */ static final class Builder {

        private final Map<HttpMethod, Map<String, HttpStreamHandler>> exact =
                new EnumMap<>(HttpMethod.class);
        private final List<TemplateEntry> templates = new ArrayList<>();

        /**
         * Registers one streaming route.
         *
         * @throws IllegalArgumentException if the path carries a brace that is not a well-formed
         *                                  {@code {name}} placeholder — a registration that could never
         *                                  match must not be storable
         */
        /* default */ void add(HttpMethod method, String path, HttpStreamHandler handler) {
            if (PathTemplate.isTemplate(path)) {
                templates.add(new TemplateEntry(method, PathTemplate.compile(path), handler));
            } else {
                exact.computeIfAbsent(method, _ -> new HashMap<>()).put(path, handler);
            }
        }

        /* default */ StreamRouteTable build() {
            return new StreamRouteTable(exact, templates);
        }
    }
}
