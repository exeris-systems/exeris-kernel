/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.telemetry;

import eu.exeris.kernel.spi.exceptions.telemetry.TelemetryBootstrapException;

import java.util.List;

/**
 * SPI: Factory that creates and wires {@link TelemetrySink} instances.
 *
 * <h2>Open-Core Tier Differentiation</h2>
 * <ul>
 *   <li><b>Community</b>: Returns the active standard sink set for the community distribution,
 *       typically combining human-readable output and event-oriented telemetry.</li>
 *   <li><b>Enterprise</b>: May return a different sink set optimized for enterprise-only
 *       telemetry capabilities and higher-performance collection paths.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. Enterprise returns {@link #priority()} = 100;
 * Community returns 0. Higher value wins.
 *
 * @see TelemetrySink
 * @since 0.5.0
 */
public interface TelemetryProvider {

    /**
     * Creates all active sinks for this provider.
     *
     * <p>Called once during kernel bootstrap. Implementations may open file handles,
     * allocate off-heap JFR buffers, or register mmap regions here.
     *
     * @param config telemetry configuration
     * @return immutable list of active sinks; never empty
     * @throws TelemetryBootstrapException if a required sink cannot be initialized
     */
    List<TelemetrySink> createSinks(TelemetryConfig config);

    /**
     * Display name used in bootstrap JFR events (e.g., {@code "ExerisEnterprise/BinaryGlassBox"}).
     */
    String providerName();

    /**
     * Higher value wins; Community = 0, Enterprise = 100.
     */
    default int priority() {
        return 0;
    }
}
