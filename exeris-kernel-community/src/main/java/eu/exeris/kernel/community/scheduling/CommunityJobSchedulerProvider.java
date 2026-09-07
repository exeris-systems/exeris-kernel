/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerConfig;
import eu.exeris.kernel.spi.scheduling.JobSchedulerProvider;

/**
 * Community {@link JobSchedulerProvider} (ADR-057): in-process dispatch on virtual threads.
 *
 * @since 0.11
 */
public final class CommunityJobSchedulerProvider implements JobSchedulerProvider {

    private static final String PROVIDER_ID = "job-loom-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/LoomJobScheduler";

    @Override
    public JobScheduler createScheduler(JobSchedulerConfig config) {
        return new CommunityJobScheduler(config, new CommunitySystemSchedulerClock());
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }
}
