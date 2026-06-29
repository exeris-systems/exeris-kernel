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

import java.util.Map;

/**
 * A resolved route: the target handler plus any captured path parameters. Exact and prefix routes
 * resolve to an empty param map; a path-template route carries the captured {@code {name} → value}
 * bindings.
 */
record RouteMatch(HttpHandler handler, Map<String, String> params) {
}
