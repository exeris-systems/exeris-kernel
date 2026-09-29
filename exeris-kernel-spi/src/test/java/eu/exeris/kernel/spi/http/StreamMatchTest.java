/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * A {@link StreamMatch} is what a wrapping handler builds when it returns a stream handler of its
 * own, so the record is constructed outside the router and has to refuse a match a driver could not
 * run: a missing handler fails where the stream opens, and missing parameters fail where the driver
 * decides whether to decorate the exchange.
 */
@DisplayName("StreamMatch")
class StreamMatchTest {

    private static final HttpStreamHandler HANDLER = exchange -> { };

    @Test
    @DisplayName("a null handler is refused at construction")
    void nullHandlerIsRefused() {
        Map<String, String> params = Map.of("id", "42");

        assertThatNullPointerException()
                .isThrownBy(() -> new StreamMatch(null, params))
                .withMessage("handler");
    }

    @Test
    @DisplayName("null parameters are refused at construction")
    void nullParamsAreRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StreamMatch(HANDLER, null))
                .withMessage("params");
    }

    @Test
    @DisplayName("a complete match keeps the handler and holds the parameter map it was given")
    void completeMatchIsHeldAsGiven() {
        Map<String, String> params = Map.of("id", "42");

        StreamMatch match = new StreamMatch(HANDLER, params);

        assertThat(match.handler()).isSameAs(HANDLER);
        assertThat(match.params()).isSameAs(params);
    }

    @Test
    @DisplayName("an exact match captures nothing, and reports an empty map rather than null")
    void exactMatchHasEmptyParams() {
        StreamMatch match = StreamMatch.exact(HANDLER);

        assertThat(match.handler()).isSameAs(HANDLER);
        assertThat(match.params()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("an exact match without a handler is refused like any other")
    void exactMatchWithoutHandlerIsRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> StreamMatch.exact(null))
                .withMessage("handler");
    }
}
