/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerConfig;
import eu.exeris.kernel.tck.contract.scheduling.AbstractJobSchedulerTck;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * Community binding of {@link AbstractJobSchedulerTck}, driven by a virtual clock.
 */
@DisplayName("CommunityJobScheduler — JobScheduler contract")
class CommunityJobSchedulerTckTest extends AbstractJobSchedulerTck {

    private VirtualSchedulerClock clock;

    @Override
    protected JobScheduler createScheduler() {
        clock = new VirtualSchedulerClock();
        return new CommunityJobScheduler(new JobSchedulerConfig("tck"), clock);
    }

    @Override
    protected void advanceTime(Duration amount) {
        clock.advance(amount);
    }
}
