/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.security.RouteAuthorizationEnforcer;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.tck.contract.http.AbstractHttpRoutePolicyTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community binding for {@link AbstractHttpRoutePolicyTck} (ADR-061).
 *
 * <p>The decision under test is the Core helper the Community dispatcher calls, so this suite pins the
 * behaviour the HTTP edge actually exhibits rather than a parallel implementation of it.
 */
@DisplayName("Community: HTTP route-authorization policy TCK")
class CommunityHttpRoutePolicyTckTest extends AbstractHttpRoutePolicyTck {

    @Override
    protected Decision decide(RouteRequirement requirement, PrincipalContext principal) {
        return map(RouteAuthorizationEnforcer.decide(requirement, principal));
    }

    @Override
    protected Decision decide(HttpRoutePolicy policy,
                              HttpMethod method,
                              String path,
                              PrincipalContext principal) {
        return map(RouteAuthorizationEnforcer.decide(policy, method, path, principal));
    }

    private static Decision map(RouteAuthorizationEnforcer.RouteDecision decision) {
        return switch (decision) {
            case ADMIT -> Decision.ADMIT;
            case UNAUTHENTICATED -> Decision.UNAUTHENTICATED;
            case FORBIDDEN -> Decision.FORBIDDEN;
        };
    }
}
