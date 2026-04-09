/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow.tck;

import eu.exeris.kernel.core.flow.CoreFlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.AbstractFlowSchedulerTck;
import org.junit.jupiter.api.DisplayName;

/**
 * TCK binding: {@link eu.exeris.kernel.spi.flow.FlowScheduler} contract verification.
 * Tests: basic schedule/park/wake, Virtual Thread avalanche concurrency, orphan-free cancellation.
 *
 * @since 0.5.0
 */
@DisplayName("Core: CoreFlowScheduler TCK")
class CoreFlowSchedulerTckTest extends AbstractFlowSchedulerTck {

    @Override
    protected FlowEngine createEngine() {
        FlowEngineConfig config = FlowEngineConfig.defaults("CoreFlowScheduler/TCK");
        return new CoreFlowEngine(config, FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-scheduler-tck"));
    }
}
