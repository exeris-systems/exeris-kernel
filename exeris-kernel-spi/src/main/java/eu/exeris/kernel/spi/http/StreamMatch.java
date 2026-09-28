/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.Map;
import java.util.Objects;

/**
 * SPI: a resolved streaming route — the handler a driver runs, and the path parameters its template
 * captured. {@link StreamRouteResolver#resolveStream} returns one on a hit.
 *
 * <p>{@code params} is held, not copied: pass an unmodifiable map. A driver applies it to the
 * exchange it hands the handler, as {@link HttpStreamExchange#pathParams()}.
 *
 * <p><b>Ownership:</b> the carrier owns nothing — it holds references to the handler and the
 * parameter map it was given, and neither copies nor releases them
 *
 * @param handler the streaming handler to run; never {@code null}
 * @param params  the captured path parameters, empty for an exact route; never {@code null}
 * @since 0.12
 */
public record StreamMatch(HttpStreamHandler handler, Map<String, String> params) {

    /**
     * Rejects a match that could not be run.
     *
     * @throws NullPointerException if {@code handler} or {@code params} is {@code null}
     */
    public StreamMatch {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(params, "params");
    }

    /**
     * Returns a match with nothing captured, which is what an exact stream route resolves to.
     *
     * @param handler the streaming handler; never {@code null}
     * @return the match
     */
    public static StreamMatch exact(HttpStreamHandler handler) {
        return new StreamMatch(handler, Map.of());
    }
}
