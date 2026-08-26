/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.scheduling;

import eu.exeris.kernel.core.bootstrap.BootstrapProviderSelector;
import eu.exeris.kernel.spi.exceptions.scheduling.JobSchedulerException;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerConfig;
import eu.exeris.kernel.spi.scheduling.JobSchedulerProvider;

import java.util.Comparator;
import java.util.Objects;

/**
 * Selects a {@link JobSchedulerProvider} and creates its scheduler (ADR-057 §1).
 *
 * <p>Core orchestration: it ranks providers through the shared {@link BootstrapProviderSelector} and
 * never names a driver. Mirrors {@code GraphBootstrap} so a reader who knows one knows this one.
 *
 * @since 0.11.0
 */
public final class SchedulingBootstrap {

    private static final String COMPONENT = "SchedulingBootstrap";
    private SchedulingBootstrap() {
        // Utility holder — not instantiable.
    }

    /**
     * The provider that won selection and the scheduler it created.
     *
     * @param provider  the selected provider
     * @param scheduler the scheduler it produced, already started
     */
    public record BootstrapResult(JobSchedulerProvider provider, JobScheduler scheduler) {
    }

    /**
     * Ranks providers by priority and creates a scheduler from the winner.
     *
     * @param config scheduler configuration
     * @return the provider and its scheduler
     * @throws JobSchedulerException if no provider is on the classpath
     */
    public static BootstrapResult loadWithProvider(JobSchedulerConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        JobSchedulerProvider provider = BootstrapProviderSelector.loadHighestPriority(
                JobSchedulerProvider.class,
                Comparator.comparingInt(JobSchedulerProvider::priority))
                .orElseThrow(() -> JobSchedulerException.noProvider(COMPONENT));

        JobScheduler scheduler = provider.createScheduler(config);

        SchedulingBootstrapSelectedEvent.emit(
                provider.getClass().getName(),
                provider.providerId(),
                provider.priority(),
                config.schedulerName());

        return new BootstrapResult(provider, scheduler);
    }
}
