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
 * <pre>
 * # META-INF/services/eu.exeris.kernel.spi.bootstrap.SubsystemProvider
 * eu.exeris.kernel.community.bootstrap.CommunitySubsystemProvider
 * </pre>
 *
 * <h2>Implementation Contract</h2>
 * <ul>
 *   <li>{@link #getSubsystems(ConfigProvider)} MUST be pure — no side effects,
 *       no I/O, no locks. It is called once during bootstrap.</li>
 *   <li>The returned list MUST NOT contain two subsystems with the same
 *       {@link Subsystem#name()}.</li>
 *   <li>Implementations MUST be discoverable via a public no-arg constructor
 *       (required by {@link java.util.ServiceLoader}).</li>
 * </ul>
 *
 * @since 0.5.0
 * @see Subsystem
 * @see BootstrapSelector
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface SubsystemProvider {

    /**
     * Returns all subsystems provided by this module.
     *
     * <p>Called once during L0 bootstrap. The {@link ConfigProvider} is available
     * for subsystems that need to read config to decide whether to activate
     * (e.g., skip transport if no port is configured).
     *
     * <p>Implementations MUST:
     * <ul>
     *   <li>Return a non-null, possibly empty list.</li>
     *   <li>Not mutate the returned list after returning it.</li>
     *   <li>Not perform network I/O or heavy initialization here —
     *       that belongs in {@link Subsystem#initialize()}.</li>
     * </ul>
     *
     * @param config the active kernel configuration; never {@code null}
     * @return list of subsystems provided; never {@code null}
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


