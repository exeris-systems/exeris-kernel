/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.telemetry;


import java.util.List;

/**
 * SPI: Factory that creates and wires {@link TelemetrySink} instances.
 *
 * <h2>Open-Core Tier Differentiation</h2>
 * <ul>
 *   <li><b>Community</b>: Returns {@code [ConsoleSink, JfrSink]}. Text output + JFR events.</li>
 *   <li><b>Enterprise</b>: Returns {@code [BinaryBlackBoxSink]}. Direct off-heap mmap dump
 *       of {@code rawArgs} with zero String allocation on the critical path.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. Enterprise returns {@link #priority()} = 100;
 * Community returns 0. Higher value wins.
 *
 * @since 0.5.0
 * @see TelemetrySink
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
     * @throws eu.exeris.kernel.spi.exceptions.telemetry.TelemetryBootstrapException
     *         if a required sink cannot be initialised
     */
    List<TelemetrySink> createSinks(TelemetryConfig config);

    /** Display name used in bootstrap JFR events (e.g., {@code "ExerisEnterprise/BlackBox"}). */
    String providerName();

    /** Higher value wins; Community = 0, Enterprise = 100. */
    default int priority() {
        return 0;
    }
}

