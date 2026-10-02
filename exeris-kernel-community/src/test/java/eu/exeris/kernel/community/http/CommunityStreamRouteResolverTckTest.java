/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import eu.exeris.kernel.tck.contract.http.AbstractStreamRouteResolverTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binds the stream-resolution contract to the handler shape the Community dispatcher actually
 * consults in a generated application: a forwarder bound to {@code HTTP_SERVER_HANDLER} that holds
 * the router in a slot and implements both {@link HttpHandler} and {@link StreamRouteResolver} by
 * delegating. The dispatcher asks the bound handler, not the router behind it, so the contract has
 * to hold through the forwarder — parameters included.
 */
@DisplayName("Community: StreamRouteResolver TCK (forwarding handler over a router slot)")
class CommunityStreamRouteResolverTckTest extends AbstractStreamRouteResolverTck {

    @Override
    protected StreamRouteResolver resolverFor(List<StreamRoute> streamRoutes,
                                              List<RespondOnceRoute> respondOnceRoutes) {
        HttpRouter.Builder builder = HttpRouter.builder();
        for (StreamRoute route : streamRoutes) {
            builder.streamRoute(route.method(), route.path(), route.handler());
        }
        for (RespondOnceRoute route : respondOnceRoutes) {
            builder.route(route.method(), route.path(), route.handler());
        }
        return new Forwarder(new AtomicReference<>(builder.build()));
    }

    /** The shape a generated application binds to {@code HTTP_SERVER_HANDLER}. */
    private static final class Forwarder implements HttpHandler, StreamRouteResolver {

        private final AtomicReference<HttpHandler> slot;

        private Forwarder(AtomicReference<HttpHandler> slot) {
            this.slot = slot;
        }

        @Override
        public void handle(HttpExchange exchange) {
            HttpHandler handler = slot.get();
            if (handler == null) {
                exchange.respond(HttpStatus.SERVICE_UNAVAILABLE);
                return;
            }
            handler.handle(exchange);
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            return slot.get() instanceof StreamRouteResolver resolver
                    ? resolver.resolveStream(method, path)
                    : null;
        }
    }
}
