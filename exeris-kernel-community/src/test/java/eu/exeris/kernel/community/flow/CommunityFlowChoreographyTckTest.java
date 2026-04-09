/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.AbstractFlowChoreographyTck;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;

@Disabled("CommunityEventProvider not yet implemented \u2014 awaiting events subsystem")
@DisplayName("Community: Flow Choreography TCK")
class CommunityFlowChoreographyTckTest extends AbstractFlowChoreographyTck {

    @Override
    protected FlowEngine createFlowEngine() {
        return new CommunityFlowProvider().createEngine(
                FlowEngineConfig.defaults("Community/Choreography-TCK"));
    }

    @Override
    protected EventEngine createEventEngine() {
        throw new UnsupportedOperationException("CommunityEventProvider not yet implemented");
    }
}
