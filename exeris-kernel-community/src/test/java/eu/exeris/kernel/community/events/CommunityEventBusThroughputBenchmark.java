/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.tck.perf.AbstractEventBusThroughputBenchmark;

/**
 * Community event-bus throughput benchmark binding.
 */
public class CommunityEventBusThroughputBenchmark extends AbstractEventBusThroughputBenchmark {

    @Override
    protected EventEngine createTargetEngine() {
        return new CommunityEventProvider().createEngine(EventEngineConfig.communityDefaults());
    }
}
