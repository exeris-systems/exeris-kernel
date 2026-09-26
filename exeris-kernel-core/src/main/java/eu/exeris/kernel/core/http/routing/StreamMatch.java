/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpStreamHandler;

import java.util.Map;

/**
 * A resolved streaming route: the handler, and whatever its template captured.
 *
 * @param handler the streaming handler to drive
 * @param params  captured path parameters; empty for an exact stream route
 * @since 0.12
 */
public record StreamMatch(HttpStreamHandler handler, Map<String, String> params) {

    /**
     * A match with nothing captured — what an exact stream route resolves to.
     *
     * @param handler the streaming handler
     * @return the match; never {@code null}
     */
    public static StreamMatch exact(HttpStreamHandler handler) {
        return new StreamMatch(handler, Map.of());
    }
}
