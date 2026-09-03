/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.core.bootstrap.BootstrapProviderSelector;
import eu.exeris.kernel.spi.exceptions.graph.GraphBootstrapException;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphProvider;

import java.util.Comparator;
import java.util.Objects;

/**
 * Core: ServiceLoader-driven bootstrap for the Graph subsystem (L2 Data Synthesis).
 *
 * <h2>Responsibility (The Brain)</h2>
 * <p>This class is the <b>only</b> place in the kernel that calls
 * {@link ServiceLoader#load(Class)} for {@link GraphProvider}. It selects the
 * highest-priority provider, creates the {@link GraphEngine}, emits a JFR
 * bootstrap event, and returns the ready engine for binding into
 * {@code KernelProviders.GRAPH_ENGINE} by the {@code KernelBootstrap} caller.
 *
 * <h2>Priority Rule (ADR-012 §2.3)</h2>
 * <ul>
 *   <li>Enterprise: priority = 100 — native io_uring + FFM, zero-alloc after startup</li>
 *   <li>Community: priority = 0  — standard JDBC/Bolt, arena-per-request</li>
 * </ul>
 * <p>If both are on the classpath, Enterprise wins. If neither is found →
 * {@link GraphBootstrapException} with {@code EX-GRPH-5001} is thrown and the
 * kernel aborts.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>This class imports only {@code exeris-kernel-spi}. It has zero knowledge of
 * HikariCP, Bolt, io_uring, or any Community/Enterprise implementation class.
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.spi.context.KernelProviders#GRAPH_ENGINE
 */
public final class GraphBootstrap {

    private static final String ERROR_NO_PROVIDER =
            "No GraphProvider found on classpath. "
            + "Add exeris-kernel-community or exeris-kernel-enterprise to your dependencies.";

    public record BootstrapResult(GraphProvider provider, GraphEngine engine) {
    }

    private GraphBootstrap() {
        // utility — no instances
    }

    /**
     * Loads the best available {@link GraphProvider}, creates the engine, and
     * returns the ready {@link GraphEngine} for ScopedValue binding.
     *
     * It MUST be called exactly once per kernel lifecycle, during bootstrap.
     *
     * @param config immutable graph subsystem configuration
     * @return a fully initialised {@link GraphEngine}
     * @throws GraphBootstrapException if no provider is available on the classpath
     */
    public static GraphEngine load(GraphConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return loadWithProvider(config).engine();
    }

    /**
     * Loads the best available {@link GraphProvider}, creates the engine, and
     * returns both the provider and engine.
     *
     * @param config immutable graph subsystem configuration
     * @return selected provider and a fully initialised {@link GraphEngine}
     * @throws GraphBootstrapException if no provider is available on the classpath
     */
    public static BootstrapResult loadWithProvider(GraphConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        GraphProvider provider = BootstrapProviderSelector.loadHighestPriority(
                GraphProvider.class,
                Comparator.comparingInt(GraphProvider::priority))
                .orElseThrow(() -> new GraphBootstrapException(
                        "GraphBootstrap", ERROR_NO_PROVIDER));

        GraphEngine engine = provider.createEngine(config);

        GraphBootstrapSelectedEvent.emit(
                provider.getClass().getName(),
                provider.priority(),
                provider.providerId(),
                engine.engineName()
        );

        return new BootstrapResult(provider, engine);
    }
}
