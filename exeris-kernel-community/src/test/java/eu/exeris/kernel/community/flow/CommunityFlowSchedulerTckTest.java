/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.AbstractFlowSchedulerTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: CommunityFlowScheduler TCK")
class CommunityFlowSchedulerTckTest extends AbstractFlowSchedulerTck {

    @Override
    protected FlowEngine createEngine() {
        return new CommunityFlowProvider().createEngine(FlowEngineConfig.defaults("Community/HeapFlow"));
    }
}