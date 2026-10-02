/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.community.events.CommunityEventProvider;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.AbstractFlowChoreographyTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: Flow Choreography TCK")
class CommunityFlowChoreographyTckTest extends AbstractFlowChoreographyTck {

    @Override
    protected FlowEngine createFlowEngine() {
        return new CommunityFlowProvider().createEngine(
                FlowEngineConfig.defaults("Community/Choreography-TCK"));
    }

    @Override
    protected EventEngine createEventEngine() {
        return new CommunityEventProvider().createEngine(EventEngineConfig.communityDefaults());
    }
}
