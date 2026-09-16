/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.health;

import eu.exeris.kernel.spi.bootstrap.HealthProbe;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.tck.contract.health.AbstractHealthEndpointTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: HealthEndpointHandler TCK")
class CommunityHealthEndpointTckTest extends AbstractHealthEndpointTck {

    @Override
    protected HttpHandler newHandler(HealthProbe probe) {
        return new HealthEndpointHandler(probe);
    }
}
