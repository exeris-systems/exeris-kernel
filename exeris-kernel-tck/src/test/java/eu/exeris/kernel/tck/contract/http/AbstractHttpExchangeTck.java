/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

        @Test
        @DisplayName("pathParams() defaults to an empty map for a non-templated exchange")
        void pathParamsEmptyByDefault() {
            HttpExchange exchange = createExchange(minimalRequest());
            assertThat(exchange.pathParams())
                    .as("an exchange not dispatched through a path-template route exposes no path params")
                    .isEmpty();
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
            assertThatCode(() -> exchange.respond(HttpStatus.NO_CONTENT))
                    .as("respond(HttpStatus) must delegate successfully using the request version")
                    .doesNotThrowAnyException();
        }
    }
}

