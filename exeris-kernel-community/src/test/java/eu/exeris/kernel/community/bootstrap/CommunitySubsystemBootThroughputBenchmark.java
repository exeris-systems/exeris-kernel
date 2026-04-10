/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
