/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A streaming route is subject to ADR-061 exactly as a respond-once route is.
 *
 * <p>The decision itself is covered by {@code AbstractHttpRoutePolicyTck}, and it always was — which is
 * the point of this test. The enforcer was fully contract-tested while the streaming branch returned
 * before ever reaching it, so every SSE route was served unauthenticated with no {@code
 * PRINCIPAL_CONTEXT} bound. What is asserted here is the <em>wiring</em>: that the stream open passes
 * through the policy at all.
 */
@DisplayName("Community: streaming routes pass the ADR-061 authorization gate")
class CommunityStreamRouteAuthorizationTest {

    private static final HttpRequest STREAM_REQUEST =
            new HttpRequest(HttpMethod.GET, "/events", HttpVersion.HTTP_1_1, List.of(), null);

    /** Records the response instead of writing it, so no transport is needed. */
    private static final class RecordingExchange implements HttpExchange {
        private final AtomicReference<HttpResponse> responded = new AtomicReference<>();

        @Override
        public HttpRequest request() {
            return STREAM_REQUEST;
        }

        @Override
        public void respond(HttpResponse response) {
            responded.compareAndSet(null, response);
        }

        /* default */ HttpStatus status() {
            HttpResponse response = responded.get();
            return response == null ? null : response.status();
        }
    }

    private static CommunityHttpRequestDispatcher dispatcherWith(RouteRequirement requirement) {
        // No SecurityInterceptor bound: the deployment declared a requirement it cannot satisfy, which
        // must deny rather than fall through — the fail-open shape ADR-012 rules out.
        return new CommunityHttpRequestDispatcher(
                mock(MemoryAllocator.class), null, null, null,
                (method, path) -> requirement);
    }

    @Test
    @DisplayName("a stream route requiring identity is not opened when none can be established")
    void requiredIdentityDeniesTheStreamOpen() {
        AtomicBoolean opened = new AtomicBoolean();
        RecordingExchange exchange = new RecordingExchange();

        dispatcherWith(RouteRequirement.authenticated())
                .dispatchStream(STREAM_REQUEST, () -> exchange, () -> opened.set(true));

        assertThat(opened)
                .withFailMessage("the stream handler ran despite the route requiring an identity")
                .isFalse();
        assertThat(exchange.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a permit-all stream route still opens — the gate is a decision, not a wall")
    void permitAllOpensTheStream() {
        AtomicBoolean opened = new AtomicBoolean();
        RecordingExchange exchange = new RecordingExchange();
        AtomicInteger exchangesBuilt = new AtomicInteger();

        dispatcherWith(RouteRequirement.permitAll()).dispatchStream(
                STREAM_REQUEST,
                () -> {
                    exchangesBuilt.incrementAndGet();
                    return exchange;
                },
                () -> opened.set(true));

        assertThat(opened).isTrue();
        assertThat(exchange.status())
                .withFailMessage("an admitted stream must respond through its engine, not the exchange")
                .isNull();
        assertThat(exchangesBuilt)
                .withFailMessage("an admitted open must not build a denial exchange at all — it is "
                        + "the common path, and this one exists only to be written to on refusal")
                .hasValue(0);
    }

    @Test
    @DisplayName("no policy bound leaves streaming exactly as it behaved before ADR-061")
    void noPolicyOpensTheStream() {
        AtomicBoolean opened = new AtomicBoolean();

        new CommunityHttpRequestDispatcher(mock(MemoryAllocator.class), null, null, null, null)
                .dispatchStream(STREAM_REQUEST, RecordingExchange::new, () -> opened.set(true));

        assertThat(opened).isTrue();
    }
}
