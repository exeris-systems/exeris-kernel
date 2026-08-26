/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.config.CommunityConfigProvider;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.tck.perf.AbstractSubsystemBootThroughputBenchmark;

/**
 * Community subsystem boot throughput benchmark binding.
 */
public class CommunitySubsystemBootThroughputBenchmark extends AbstractSubsystemBootThroughputBenchmark {

    @Override
    protected SubsystemProvider createProvider() {
        return new CommunitySubsystemProvider();
    }

    @Override
    protected ConfigProvider createConfigStub() {
        return new CommunityConfigProvider();
    }
}
