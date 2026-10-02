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

    /**
     * Instantiated reflectively by {@code ServiceLoader} through this module's
     * {@code META-INF/services} registration of {@link JobSchedulerProvider}; not meant to be
     * constructed directly.
     */
    public CommunityJobSchedulerProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
