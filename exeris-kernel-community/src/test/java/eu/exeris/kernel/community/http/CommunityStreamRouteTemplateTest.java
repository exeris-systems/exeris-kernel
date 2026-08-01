/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A templated stream route, driven end to end through the production dispatch path.
 *
 * <p>{@code HttpRouterTest} proves the router <em>resolves</em> a template and captures its
 * parameters; nothing there proves the capture reaches a running handler. That gap is the same shape
 * as the defect this work fixed — a registration that looks correct and never reaches production
 * behaviour — so it gets its own test rather than an argument.
 *
 * <p>Deliberately goes through {@code resolveStreamHandler} and {@code dispatchStream} with a real
 * {@link HttpRouter}, not through {@code StreamMatch.exact(...)} as the sibling suites do: the branch
 * under test is the one that wraps the engine in a {@code PathParamStreamExchange} only when a template
 * captured something, and a hand-built exact match skips it entirely.
 *
 * @since 0.11.0
 */
@DisplayName("Community: a templated stream route delivers its captured parameters")
class CommunityStreamRouteTemplateTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("the handler sees the concrete id the request carried")
    void templatedStreamRouteReachesTheHandler() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();

        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.POST, "/orders/{id}/actions/ship", exchange -> {
                    seen.set(exchange.pathParams());
                    seenPath.set(exchange.request().path());
                    exchange.emit(StreamEvent.of("shipping"));
                    exchange.close();
                })
                .build();

        dispatch(router, "/orders/42/actions/ship");

        assertThat(seen.get())
                .as("the router captured the id; a handler that cannot read it knows a stream is open "
                        + "but not what it is a stream of")
                .containsEntry("id", "42");
        assertThat(seenPath.get())
                .as("the decorator must forward request() untouched, not synthesise its own")
                .isEqualTo("/orders/42/actions/ship");
    }

    @Test
    @DisplayName("an exact stream route still reports an empty map, not a null one")
    void exactStreamRouteIsUndecorated() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();

        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.POST, "/orders/events", exchange -> {
                    seen.set(exchange.pathParams());
                    exchange.close();
                })
                .build();

        dispatch(router, "/orders/events");

        assertThat(seen.get())
                .as("the common path skips the decorator entirely, so this is the engine's own default")
                .isNotNull()
                .isEmpty();
    }

    private static void dispatch(HttpRouter router, String path) {
        CommunityHttpStreamDispatcher dispatcher = new CommunityHttpStreamDispatcher(ALLOCATOR);
        HttpRequest request = HttpRequest.noBody(
                HttpMethod.POST, path, HttpVersion.HTTP_1_1, List.of());

        HttpRouter.StreamMatch match = dispatcher.resolveStreamHandler(request, router);
        assertThat(match).as("route must resolve as streaming for %s", path).isNotNull();

        dispatcher.dispatchStream(request, new DiscardingStream(), match);
    }

    /** Accepts the SSE head and every event, and never reads — the wire is not what is under test. */
    private static final class DiscardingStream implements TransportStream {

        @Override
        public int read(MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
            // Discarded: this test asserts on what the handler saw, not on what reached the socket.
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            buffer.close();
        }

        @Override
        public long streamId() {
            return 1L;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            return null;
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /** Compile-time guard: the handler above is an {@link HttpStreamExchange} consumer, nothing else. */
    @SuppressWarnings("unused")
    private static void handlerShape(HttpStreamExchange exchange) {
        exchange.pathParams();
    }
}
