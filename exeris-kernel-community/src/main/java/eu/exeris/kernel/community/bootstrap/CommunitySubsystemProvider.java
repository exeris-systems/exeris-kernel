/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;

import java.util.List;

/**
 * ServiceLoader entry point that exposes all Community-tier {@link Subsystem} implementations
 * to the {@code SubsystemOrchestrator}.
 *
 * <h2>Priority</h2>
 * <p>Returns {@code 0} — the Community open-core slot.
 * Enterprise overrides individual subsystems at priority {@code 100}.
 *
 * @since 0.5.0
 */
public final class CommunitySubsystemProvider implements SubsystemProvider {

    @Override
    public List<Subsystem> getSubsystems(ConfigProvider config) {
        return List.of(
                new CommunityMemorySubsystem(),
                new CommunityCryptoSubsystem(),
                new CommunitySecuritySubsystem(),
                new CommunityPersistenceSubsystem(),
                new CommunityEventsSubsystem(),
                new CommunityGraphSubsystem(),
                new CommunityTransportSubsystem(),
                new CommunityHttpSubsystem(),
                new CommunityWebSocketSubsystem(),
                new CommunityFlowSubsystem(),
                new CommunitySchedulingSubsystem(),
                new CommunityStorageSubsystem()
        );
    }

    @Override
    public String moduleName() {
        return "exeris-kernel-community";
    }
}
