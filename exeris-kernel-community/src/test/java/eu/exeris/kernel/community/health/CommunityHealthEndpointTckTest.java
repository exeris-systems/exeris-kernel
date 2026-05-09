/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
