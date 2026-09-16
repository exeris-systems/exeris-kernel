/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.scheduling.SchedulingBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerConfig;
import eu.exeris.kernel.spi.scheduling.JobSchedulerProvider;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Bootstraps the scheduling subsystem (ADR-057 §1).
 *
 * <p><strong>Depends on nothing.</strong> The in-process driver allocates no off-heap memory, opens no
 * connection, and reads no store — its whole construction is a config lookup and a virtual thread.
 *
 * <p>The tempting declaration is {@code security}, on the reasoning that a job carries the identity
 * that submitted it. It is wrong twice over: there is no {@code security} subsystem in the registry
 * (identity arrives through {@code ScopedValue} slots, not through a boot-ordered component), and
 * identity crosses this seam at <em>submit</em> time from the caller's own scope, which no boot order
 * can influence. A dependency declared for narrative tidiness constrains the boot graph on a fiction
 * and buys nothing.
 *
 * @since 0.11
 */
final class CommunitySchedulingSubsystem extends AbstractCommunitySubsystem {

    private JobSchedulerProvider schedulerProvider;
    private JobScheduler scheduler;

    @Override
    public String name() {
        return "scheduling";
    }

    @Override
    public List<String> dependsOn() {
        return List.of();
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        String schedulerName = configProvider.getString("scheduling.schedulerName")
                .orElse(JobSchedulerConfig.DEFAULT_NAME);
        SchedulingBootstrap.BootstrapResult bootstrap =
                SchedulingBootstrap.loadWithProvider(new JobSchedulerConfig(schedulerName));
        schedulerProvider = bootstrap.provider();
        scheduler = bootstrap.scheduler();
    }

    @Override
    public void start() {
        // The scheduler's dispatcher starts with the scheduler itself — createScheduler returns a
        // running instance — so there is no second start step to perform here.
        markRunning(scheduler != null);
    }

    @Override
    public void stop() {
        markRunning(false);
        if (scheduler != null) {
            // Drains rather than abandons (ADR-057 §6): close() awaits runs already in flight, which
            // is the half of the lifecycle boundary that stands in for a structured scope.
            scheduler.close();
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (schedulerProvider == null || scheduler == null) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(
                CommunityCarrierBindings.binding(
                        KernelProviders.JOB_SCHEDULER_PROVIDER, schedulerProvider),
                CommunityCarrierBindings.binding(KernelProviders.JOB_SCHEDULER, scheduler)
        );
    }
}
