/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpHandler} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Handler receives a non-null {@link HttpExchange}</li>
 *   <li>Handler MUST call {@code respond()} exactly once per exchange</li>
 *   <li>An {@link HttpException} thrown by the handler propagates to the caller</li>
 *   <li>A {@link RuntimeException} thrown by the handler propagates unwrapped</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractHttpHandlerTck {

    /**
     * Creates an {@link HttpExchange} wired to a simple recorder so tests can verify
     * which response was written. The exchange must start in an un-responded state.
     *
     * @param request the inbound request
     * @return a fresh exchange; never {@code null}
     */
    protected abstract HttpExchange createRecordingExchange(HttpRequest request);

    /** Minimal GET / HTTP/1.1 request for use in tests. */
    protected static HttpRequest minimalRequest() {
        return HttpRequest.noBody(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, List.of());
    }

    @Nested
    @DisplayName("Handle invocation")
    class HandleInvocation {

        @Test
        @DisplayName("Handler receives non-null exchange")
        void exchangeIsNonNull() {
            AtomicReference<HttpExchange> captured = new AtomicReference<>();
            HttpHandler handler = exchange -> {
                captured.set(exchange);
                exchange.respond(HttpStatus.OK);
            };

            handler.handle(createRecordingExchange(minimalRequest()));

            assertThat(captured.get()).isNotNull();
        }

        @Test
        @DisplayName("Handler can call respond(HttpStatus.OK) without exception")
        void respondOkNoException() {
            HttpHandler handler = exchange -> exchange.respond(HttpStatus.OK);
            assertThatCode(() -> handler.handle(createRecordingExchange(minimalRequest())))
                    .as("Handler should be able to emit an OK response without throwing")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("HttpException propagates from handler to caller")
        void httpExceptionPropagates() {
            HttpException sentinel = new HttpException(
                    "EX-HTTP-4009", "test-failure", (Throwable) null);
            HttpHandler handler = exchange -> {
                throw sentinel;
            };

            assertThatThrownBy(() -> handler.handle(createRecordingExchange(minimalRequest())))
                    .isSameAs(sentinel);
        }

        @Test
        @DisplayName("RuntimeException propagates unwrapped from handler to caller")
        void runtimeExceptionPropagatesUnwrapped() {
            RuntimeException sentinel = new IllegalArgumentException("test");
            HttpHandler handler = exchange -> {
                throw sentinel;
            };

            assertThatThrownBy(() -> handler.handle(createRecordingExchange(minimalRequest())))
                    .isSameAs(sentinel);
        }
    }

    @Nested
    @DisplayName("Functional interface compatibility")
    class FunctionalInterface {

        @Test
        @DisplayName("HttpHandler can be expressed as a lambda")
        void lambdaCompatible() {
            HttpHandler handler = exchange -> exchange.respond(HttpStatus.NO_CONTENT);
            assertThatCode(() -> handler.handle(createRecordingExchange(minimalRequest())))
                    .as("Lambda-based HttpHandler implementations must remain invocation-compatible")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("HttpHandler can be expressed as a method reference")
        void methodReferenceCompatible() {
            HttpHandler handler = AbstractHttpHandlerTck::staticTestHandler;
            assertThatCode(() -> handler.handle(createRecordingExchange(minimalRequest())))
                    .as("Method-reference HttpHandler implementations must remain invocation-compatible")
                    .doesNotThrowAnyException();
        }
    }

    private static void staticTestHandler(HttpExchange exchange) {
        exchange.respond(HttpStatus.ACCEPTED);
    }
}
