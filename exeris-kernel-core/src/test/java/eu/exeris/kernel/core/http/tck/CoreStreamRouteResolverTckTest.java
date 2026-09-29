/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.tck;

import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import eu.exeris.kernel.tck.contract.http.AbstractStreamRouteResolverTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

/**
 * Binds the stream-resolution contract to the Core router, the implementation an application gets
 * from {@link HttpRouter#builder()} and the one a wrapping handler delegates to.
 */
@DisplayName("Core: StreamRouteResolver TCK (HttpRouter)")
class CoreStreamRouteResolverTckTest extends AbstractStreamRouteResolverTck {

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
        return builder.build();
    }
}
