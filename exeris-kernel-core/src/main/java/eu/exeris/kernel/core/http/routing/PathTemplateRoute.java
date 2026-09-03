/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
     * Returns a {@link RouteMatch} (handler + captured params) when {@code path} matches this
     * template, or {@code null} when it does not.
     *
     * @param path the request path, query already stripped
     * @return the match, or {@code null}
     */
    /* default */ RouteMatch toMatch(String path) {
        return template.matches(path) ? new RouteMatch(handler, template.capture(path)) : null;
    }
}
