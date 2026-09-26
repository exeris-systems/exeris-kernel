/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ADR-077: the dispatcher binds a request-scoped persistence session for a {@code PROMPT} route and
 * not for a {@code LONG_RUNNING} one.
 *
 * <p>This is the decision itself, asserted where it is made. The end-to-end consequence — two
 * acquires landing on different PostgreSQL backends — is pinned separately by
 * {@code CommunityRouteExecutionIsolationIT}, which needs a live database; this one runs in the
 * default build, so a regression in the branch is caught without a Docker gate.
 */
@DisplayName("Community: a route's declared execution decides whether a request session is bound (ADR-077)")
class CommunityRouteExecutionBindingTest {

    private static final HttpRequest REQUEST =
            new HttpRequest(HttpMethod.GET, "/report", HttpVersion.HTTP_1_1, List.of(), null);

    private static final class RecordingExchange implements HttpExchange {
        private final AtomicReference<HttpResponse> responded = new AtomicReference<>();

        @Override
        public HttpRequest request() {
            return REQUEST;
        }

        @Override
        public void respond(HttpResponse response) {
            responded.compareAndSet(null, response);
        }
    }

    private static boolean sessionBoundInside(RouteRequirement requirement) {
        AtomicBoolean bound = new AtomicBoolean();
        AtomicBoolean ran = new AtomicBoolean();
        RecordingExchange exchange = new RecordingExchange();

        new CommunityHttpRequestDispatcher(
                mock(MemoryAllocator.class), null, null, null, (method, path) -> requirement)
                .dispatch(REQUEST, exchange, ex -> {
                    ran.set(true);
                    bound.set(CommunityHttpRequestProcessor.REQUEST_SESSION.isBound());
                    ex.respond(HttpResponse.noBody(HttpStatus.OK, REQUEST.version()));
                });

        assertThat(ran)
                .as("the handler must actually run, or the binding assertion below is vacuous")
                .isTrue();
        return bound.get();
    }

    @Test
    @DisplayName("PROMPT binds the request session — the promise persistence.md states, unchanged")
    void promptBindsTheSession() {
        assertThat(sessionBoundInside(RouteRequirement.permitAll())).isTrue();
    }

    @Test
    @DisplayName("LONG_RUNNING binds none, so nothing pooled is held across the block")
    void longRunningBindsNoSession() {
        assertThat(sessionBoundInside(RouteRequirement.permitAll().longRunning())).isFalse();
    }

    @Test
    @DisplayName("the two answers differ, which is the whole claim")
    void theDeclarationIsWhatDecides() {
        // Stated as its own case because each assertion above passes on its own against a dispatcher
        // that ignores the facet entirely — one of them would just be wrong. Only the pair is the
        // decision.
        assertThat(sessionBoundInside(RouteRequirement.permitAll()))
                .isNotEqualTo(sessionBoundInside(RouteRequirement.permitAll().longRunning()));
    }
}
