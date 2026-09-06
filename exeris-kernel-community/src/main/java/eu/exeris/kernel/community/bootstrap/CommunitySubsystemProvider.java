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
 * @since 0.5
 */
public final class CommunitySubsystemProvider implements SubsystemProvider {

    /**
     * Constructs the provider that {@link java.util.ServiceLoader} instantiates to resolve the
     * Community-tier {@link SubsystemProvider}, per this module's registration under
     * {@code META-INF/services/eu.exeris.kernel.spi.bootstrap.SubsystemProvider}.
     */
    public CommunitySubsystemProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Returns every subsystem the Community tier registers with the orchestrator.
     *
     * @param config the resolved kernel configuration; unused — the Community subsystem set is
     *               fixed and does not vary by configuration, unlike each subsystem's own behavior
     * @return the complete, fixed Community subsystem list
     */
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

    /**
     * Returns this provider's module identifier, {@code "exeris-kernel-community"}.
     *
     * @return the Community module name
     */
    @Override
    public String moduleName() {
        return "exeris-kernel-community";
    }
}
