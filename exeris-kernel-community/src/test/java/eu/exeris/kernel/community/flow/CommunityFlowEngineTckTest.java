/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.AbstractFlowEngineTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: CommunityFlowEngine TCK")
class CommunityFlowEngineTckTest extends AbstractFlowEngineTck {

    @Override
    protected FlowEngine createEngine() {
        return new CommunityFlowProvider().createEngine(FlowEngineConfig.defaults("Community/HeapFlow"));
    }
}