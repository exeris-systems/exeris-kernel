/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;

import java.util.List;

/**
 * ServiceLoader SPI entry point for subsystem discovery.
 *
 * <h2>The Wall</h2>
 * <p>This interface is the only way the {@code SubsystemOrchestrator} (core module)
 * discovers available subsystems. It knows nothing about what those subsystems do —
 * only that they exist, have names, and follow the {@link Subsystem} lifecycle.
 *
 * <h2>Priority-based Selection</h2>
 * <p>When both Community and Enterprise providers are on the classpath, the orchestrator
 * collects all {@link Subsystem} instances from all providers, sorted by
 * {@link #priority()} descending. Name collisions are resolved by first-writer-wins
 * (highest-priority provider's subsystem for a given name is kept).
 *
 * <p>Priority convention: Community = 0, Enterprise = 100.
 *
 * <h2>ServiceLoader Registration</h2>
 * {@snippet lang="properties" :
 * # META-INF/services/eu.exeris.kernel.spi.bootstrap.SubsystemProvider
 * eu.exeris.kernel.community.bootstrap.CommunitySubsystemProvider
 * }
 *
 * @implSpec Implementations must be discoverable through a public no-arg constructor, as
 *           {@link java.util.ServiceLoader} requires, and must not return two subsystems
 *           sharing one {@link Subsystem#name()} from a single
 *           {@link #getSubsystems(ConfigProvider)} call: the registry is keyed by subsystem
 *           name and keeps the first entry it sees, so the duplicate is dropped rather than
 *           reported as an error.
 * @since 0.5
 * @see Subsystem
 * @see BootstrapSelector
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface SubsystemProvider {

    /**
     * Returns all subsystems provided by this module.
     *
     * <p>Called once during L0 bootstrap, before any subsystem is initialized and before the
     * dependency graph is built. The {@link ConfigProvider} is available for subsystems that
     * need to read config to decide whether to activate (e.g., skip transport if no port is
     * configured); it is the same configuration the returned subsystems will later see bound
     * to {@code KernelProviders.CURRENT_CONFIG}.
     *
     * @param config the active kernel configuration; never {@code null}
     * @return the subsystems this module contributes to the boot graph, possibly empty;
     *         never {@code null}
     * @implSpec Implementations must be pure — no side effects, no I/O, no locks — must not
     *           mutate the returned list afterwards, and must leave every acquisition of
     *           resources to {@link Subsystem#initialize()}. Constructing a subsystem here
     *           must not open a socket, a pool or a file: nothing in this call is covered by
     *           the failure policy, and a subsystem returned here may still be dropped before
     *           {@code initialize()} by the active {@link BootstrapSelector}.
     */
    List<Subsystem> getSubsystems(ConfigProvider config);

    /**
     * Selection priority — higher value wins on name collision.
     *
     * <p>Convention: Community = 0, Enterprise = 100.
     *
     * @return priority ≥ 0
     */
    default int priority() {
        return 0;
    }

    /**
     * Human-readable module name for logging and JFR telemetry.
     *
     * @return non-null module name
     */
    default String moduleName() {
        return getClass().getSimpleName();
    }
}


