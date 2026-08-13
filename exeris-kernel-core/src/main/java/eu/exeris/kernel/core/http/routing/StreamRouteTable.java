/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStreamHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The streaming half of the routing table: exact paths first, then templates.
 *
 * <p>Its own type because it is its own table. Folding it back into {@link HttpRouter} would put two
 * independent resolution strategies in one class and, more to the point, would hide that the streaming
 * table has exactly the precedence rules the respond-once one does — which is the property that stopped
 * being true when this table was a bare {@code Map} and templated stream routes silently never matched.
 *
 * @since 0.11.0
 */
final class StreamRouteTable {

    private final Map<Key, HttpStreamHandler> exact;
    private final List<TemplateEntry> templates;

    private StreamRouteTable(Map<Key, HttpStreamHandler> exact, List<TemplateEntry> templates) {
        this.exact = Map.copyOf(exact);
        this.templates = List.copyOf(templates);
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
    /* default */ HttpRouter.StreamMatch resolve(HttpMethod method, String path) {
        HttpStreamHandler literal = exact.get(new Key(method, path));
        if (literal != null) {
            return HttpRouter.StreamMatch.exact(literal);
        }
        if (templates.isEmpty()) {
            return null;
        }
        String[] segments = path.split("/", -1);
        for (TemplateEntry entry : templates) {
            if (entry.method() == method && entry.template().matches(segments)) {
                return new HttpRouter.StreamMatch(
                        entry.handler(), entry.template().capture(segments));
            }
        }
        return null;
    }

    private value record Key(HttpMethod method, String path) {}

    private value record TemplateEntry(HttpMethod method, PathTemplate template,
                                 HttpStreamHandler handler) {}

    /** Accumulates registrations, compiling each path once at build time. */
    /* default */ static final class Builder {

        private final Map<Key, HttpStreamHandler> exact = new HashMap<>();
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
                exact.put(new Key(method, path), handler);
            }
        }

        /* default */ StreamRouteTable build() {
            return new StreamRouteTable(exact, templates);
        }
    }
}
