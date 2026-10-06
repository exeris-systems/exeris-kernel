/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.FlowZeroAllocTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@DisplayName("Community: Flow zero-allocation TCK [ADVISORY]")
@Tag("perf-contract")
@Tag("advisory")
class CommunityFlowZeroAllocTckTest extends FlowZeroAllocTck {

    @Override
    protected FlowEngine createEngine() {
        return new CommunityFlowProvider().createEngine(FlowEngineConfig.defaults("Community/HeapFlow"));
    }

    @Override
    protected boolean supportsZeroGcHotPath() {
        return false;
    }

    @Override
    protected int maxExerisAllocationsPerIteration() {
        return 10;
    }
}
