/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpExchange} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link HttpExchange#request()} is non-null</li>
 *   <li>{@link HttpExchange#respond(HttpResponse)} must be callable exactly once</li>
 *   <li>Second call to {@code respond()} throws {@link IllegalStateException}</li>
 *   <li>{@code respond(null)} throws {@link NullPointerException}</li>
 *   <li>Default {@code respond(HttpStatus)} overload delegates correctly</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractHttpExchangeTck {

    /**
     * Creates an {@link HttpExchange} pre-loaded with the given request.
     *
     * @param request the request to associate with the exchange
     * @return a fresh exchange; never {@code null}
     */
    protected abstract HttpExchange createExchange(HttpRequest request);

    /** Minimal GET / HTTP/1.1 request for use in tests. */
    protected static HttpRequest minimalRequest() {
        return HttpRequest.noBody(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, List.of());
    }

    @Nested
    @DisplayName("Request access")
    class RequestAccess {

        @Test
        @DisplayName("request() returns non-null")
        void requestNonNull() {
            HttpExchange exchange = createExchange(minimalRequest());
            assertThat(exchange.request()).isNotNull();
        }

        @Test
        @DisplayName("request() returns the request that was passed at construction")
        void requestMatchesConstructed() {
            HttpRequest req = minimalRequest();
            HttpExchange exchange = createExchange(req);
            assertThat(exchange.request().method()).isEqualTo(HttpMethod.GET);
            assertThat(exchange.request().path()).isEqualTo("/");
        }
    }

    @Nested
    @DisplayName("respond() contract")
    class RespondContract {

        @Test
        @DisplayName("respond(null) throws NullPointerException")
        void respondNullThrows() {
            HttpExchange exchange = createExchange(minimalRequest());
            assertThatThrownBy(() -> exchange.respond((HttpResponse) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("respond() twice throws IllegalStateException")
        void respondTwiceThrows() {
            HttpExchange exchange = createExchange(minimalRequest());
            exchange.respond(HttpStatus.OK);
            assertThatThrownBy(() -> exchange.respond(HttpStatus.OK))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("respond(HttpStatus) convenience overload uses request's version")
        void respondStatusUsesRequestVersion() {
            HttpRequest req = minimalRequest();
            HttpExchange exchange = createExchange(req);
            exchange.respond(HttpStatus.NO_CONTENT);
        }
    }
}

