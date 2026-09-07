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
 * <h2>Discovery</h2>
 * <p>Providers are discovered through {@link java.util.ServiceLoader}. The candidate with the
 * highest {@link #priority()} is selected, and only that provider's sinks are built.
 *
 * <p><b>Allocation:</b> allocates — {@link #createSinks(TelemetryConfig)} builds the sink list and
 * whatever each sink owns (file handles, recording buffers, mapped regions) once, at bootstrap.
 * Nothing on this interface sits on an emission path.
 * <p><b>Thread confinement:</b> owner thread — {@code createSinks} is called once, on the thread
 * that runs kernel bootstrap; the list it returns is what hot-path code then iterates.
 * <p><b>Ownership:</b> the caller of {@code createSinks} owns every sink in the returned list and
 * closes each one at shutdown.
 *
 * @implSpec An implementation must return a non-null, non-empty, immutable list from
 *           {@link #createSinks(TelemetryConfig)}, every element of which has a non-blank
 *           {@link TelemetrySink#sinkName()}; a non-null, non-blank {@link #providerName()}; and a
 *           {@link #priority()} of {@code 0} for the Community tier or {@code 100} for the
 *           Enterprise tier.
 * @implNote The Community provider returns a Flight Recorder sink when
 *           {@link TelemetryConfig#jfrSinkEnabled()} is set and an SLF4J fallback sink otherwise,
 *           adding console and file sinks as the configuration asks for them. An Enterprise
 *           provider may return a different set aimed at binary off-heap collection.
 * @since 0.5
 * @see TelemetrySink
 */
public interface TelemetryProvider {

    /**
     * Creates every sink this provider contributes under the given configuration.
     *
     * <p>Called once during kernel bootstrap. Implementations may open file handles,
     * allocate off-heap JFR buffers, or register mmap regions here.
     *
     * @param config the configuration selecting which sinks are active and their resource budgets
     * @return the active sinks, as an immutable list holding at least one element; the caller owns
     *         them and closes each one
     * @throws TelemetryBootstrapException {@code EX-BOOT-3001} — a sink named by {@code config}
     *         could not be initialised
     */
    List<TelemetrySink> createSinks(TelemetryConfig config);

    /**
     * Returns the name under which this provider appears in bootstrap diagnostics and JFR events,
     * such as {@code "ExerisEnterprise/BinaryGlassBox"}.
     *
     * @return a non-null, non-blank display name, stable for the life of the provider
     */
    String providerName();

    /**
     * Returns this provider's rank in {@link java.util.ServiceLoader} selection, where the highest
     * rank on the classpath wins and the losing providers build no sinks.
     *
     * @return {@code 0} for a Community-tier provider, {@code 100} for an Enterprise-tier one
     * @implSpec The default implementation returns {@code 0}, the Community rank; an
     *           Enterprise-tier provider overrides it.
     */
    default int priority() {
        return 0;
    }
}
