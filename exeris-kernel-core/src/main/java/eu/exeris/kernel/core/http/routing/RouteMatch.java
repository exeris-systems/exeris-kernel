/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpHandler;

import java.util.Map;

/**
 * A resolved route: the target handler plus any captured path parameters. Exact and prefix routes
 * resolve to an empty param map; a path-template route carries the captured {@code {name} → value}
 * bindings.
 */
record RouteMatch(HttpHandler handler, Map<String, String> params) {
}
