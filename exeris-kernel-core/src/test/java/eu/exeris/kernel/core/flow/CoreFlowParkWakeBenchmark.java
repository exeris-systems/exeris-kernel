/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.perf.AbstractFlowParkWakeBenchmark;

/**
 * JMH benchmark binding for Core-tier FlowEngine park/wake cycle.
 *
 * @since 0.5.0
 */
public class CoreFlowParkWakeBenchmark extends AbstractFlowParkWakeBenchmark {

    @Override
    protected FlowEngine createEngine() {
        return new CoreFlowEngine(
                FlowEngineConfig.defaults("CoreFlowParkWake/Benchmark"),
                FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-bench"));
    }

    @Override
    protected boolean isEnterpriseTier() {
        return false;
    }
}
