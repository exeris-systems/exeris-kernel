/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ADR-077, end to end against a live pool: what the declaration costs and buys, in backend PIDs.
 *
 * <p>{@code CommunityRouteExecutionBindingTest} pins the decision — whether a session is bound. This
 * pins its consequence, which is the thing the ADR is actually about: on a {@code PROMPT} route two
 * acquires inside one request land on the <em>same</em> PostgreSQL backend, which is
 * {@code persistence.md}'s "One HTTP request is one connection"; on a {@code LONG_RUNNING} route
 * they land on <em>different</em> ones, which is why nothing pooled is pinned across a block.
 *
 * <p>The existing {@code CommunityRequestScopeBypassIsolationIT} asserts the same-backend property
 * at the engine level and is deliberately not touched — ADR-077 narrows the promise to {@code PROMPT}
 * routes rather than retracting it, so that test must keep passing exactly as written.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: a LONG_RUNNING route does not pin a pooled backend (ADR-077)")
class CommunityRouteExecutionIsolationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final int POOL_SIZE = 4;
    private static final HttpRequest REQUEST =
            new HttpRequest(HttpMethod.GET, "/report", HttpVersion.HTTP_1_1, List.of(), null);

    private static PersistenceEngine engine;

    @BeforeAll
    static void startEngine() {
        PersistenceConfig config = new PersistenceConfig(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                POOL_SIZE, POOL_SIZE,
                5_000L, 60_000L, 600_000L,
                true, false, false, 0, Map.of());
        engine = new CommunityPersistenceProvider().createEngine(config);
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

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

    /** Dispatches one request on {@code requirement} and returns the two backend PIDs the handler saw. */
    private static int[] twoBackendsSeenBy(RouteRequirement requirement) {
        AtomicReference<int[]> pids = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();

        new CommunityHttpRequestDispatcher(
                mock(MemoryAllocator.class), null, engine, null, (method, path) -> requirement)
                .dispatch(REQUEST, new RecordingExchange(), exchange -> {
                    ran.set(true);
                    // Held open together on purpose: released sequentially, the pool could hand the
                    // same physical connection back and the LONG_RUNNING case would report one
                    // backend for a reason that has nothing to do with the declaration.
                    try (PersistenceConnection first = engine.openConnection();
                         PersistenceConnection second = engine.openConnection()) {
                        pids.set(new int[] {backendPidOf(first), backendPidOf(second)});
                    }
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, REQUEST.version()));
                });

        assertThat(ran)
                .as("the handler must run, or the PIDs below are from nothing")
                .isTrue();
        return pids.get();
    }

    private static int backendPidOf(PersistenceConnection conn) {
        try (QueryResult result = conn.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.row().getInt(0);
        }
    }

    @Test
    @DisplayName("PROMPT: two acquires share one backend — the promise, unchanged")
    void promptSharesOneBackend() {
        int[] pids = twoBackendsSeenBy(RouteRequirement.permitAll());
        assertThat(pids[0])
                .as("one HTTP request is one connection on a PROMPT route")
                .isEqualTo(pids[1]);
    }

    @Test
    @DisplayName("LONG_RUNNING: two acquires land on different backends, so neither is pinned")
    void longRunningTakesIndependentBackends() {
        int[] pids = twoBackendsSeenBy(RouteRequirement.permitAll().longRunning());
        assertThat(pids[0])
                .as("a LONG_RUNNING route binds no request session, so each acquire is its own")
                .isNotEqualTo(pids[1]);
    }
}
