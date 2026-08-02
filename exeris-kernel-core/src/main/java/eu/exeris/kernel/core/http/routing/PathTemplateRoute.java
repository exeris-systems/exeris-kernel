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


/**
 * A respond-once path-template route: a method, a compiled {@link PathTemplate}, and its handler.
 */
record PathTemplateRoute(HttpMethod method, PathTemplate template, HttpHandler handler) {

    // Package-private factory for the HttpRouter builder.
    /* default */ static PathTemplateRoute compile(HttpMethod method, String path,
                                                   HttpHandler handler) {
        return new PathTemplateRoute(method, PathTemplate.compile(path), handler);
    }

    /**
     * Returns a {@link RouteMatch} (handler + captured params) when {@code requestSegments} matches
     * this template, or {@code null} when it does not.
     *
     * @param requestSegments the request path pre-split on {@code '/'}
     * @return the match, or {@code null}
     */
    @SuppressWarnings("PMD.UseVarargs") // call site passes a pre-split array, never a varargs list
    /* default */ RouteMatch toMatch(String[] requestSegments) {
        return template.matches(requestSegments)
                ? new RouteMatch(handler, template.capture(requestSegments))
                : null;
    }
}
