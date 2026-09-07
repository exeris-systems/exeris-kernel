/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph;

/**
 * SPI: Pluggable graph engine factory — the single entry-point through which
 * the kernel bootstrapper creates a {@link GraphEngine}.
 *
 * <h2>Open-Core (The Wall)</h2>
 * <p>Two tier bindings are loaded via {@link java.util.ServiceLoader} priority.
 * The SPI contract is implementation-blind — the technologies listed below are
 * <em>documentation of expected bindings</em>, not API surface:
 * <ul>
 *   <li><b>Community binding</b> (free, priority 0): standard JDBC-compatible
 *       driver with arena-per-request {@code LoanedBuffer} allocations.
 *       No preallocated slab pools. No raw pointers.</li>
 *   <li><b>Enterprise binding</b> (secret sauce, priority 100): native wire
 *       protocol via io_uring + FFM, backed by {@code GlobalMemoryArbiter} →
 *       {@code PartitionedSlabPool}. Zero dynamic allocation after startup.
 *       This binding lives in {@code exeris-kernel-enterprise} and MUST NOT
 *       be referenced from this SPI.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. The kernel bootstrapper selects the
 * highest-{@link #priority()} provider:
 * {@snippet lang="java" :
 * GraphProvider provider = ServiceLoader.load(GraphProvider.class)
 *     .stream()
 *     .map(ServiceLoader.Provider::get)
 *     .max(Comparator.comparingInt(GraphProvider::priority))
 *     .orElseThrow(() -> new GraphBootstrapException("N/A", "No GraphProvider on classpath"));
 *
 * GraphEngine engine = provider.createEngine(config);
 * ScopedValue.where(KernelProviders.GRAPH_ENGINE, engine).run(kernel::startSubsystems);
 * }
 *
 * <h2>SPI Compliance</h2>
 * <p>The <strong>API surface</strong> of this interface is implementation-blind:
 * no backend-specific types (JDBC, Neo4j Bolt, io_uring, Redis, DataSource, etc.)
 * appear in method signatures, return types, or thrown exceptions. The Open-Core
 * section above names those technologies only to document the bindings that are
 * expected to implement this interface — they MUST NOT leak into the SPI itself.
 *
 * @implSpec Implementations MUST obtain their {@link eu.exeris.kernel.spi.memory.MemoryAllocator}
 *           and {@link eu.exeris.kernel.spi.persistence.PersistenceEngine} from
 *           {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots, never as
 *           constructor parameters, to keep SPI isolation clean.
 * @since 0.5
 * @see GraphEngine
 * @see GraphConfig
 */
public interface GraphProvider {

    /**
     * Creates and initialises a {@link GraphEngine} from the given configuration.
     *
     * @param config graph subsystem configuration
     * @return a fully initialised, ready-to-serve graph engine
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphBootstrapException {@code (EX-GRPH-5001)}
     *         if the engine cannot be created (missing backend, connection failure, etc.)
     * @implSpec Implementations read their upstream providers from {@code KernelProviders}
     *           scoped slots — {@code MEMORY_ALLOCATOR} for buffer allocation,
     *           {@code PERSISTENCE_ENGINE} for database access on Community, and
     *           {@code STORAGE_CONTEXT} for tenant isolation.
     * @apiNote This is a potentially blocking call (connection pool setup, PGQ version check,
     *          memory partition claim). Callers MUST NOT invoke it on a virtual thread that is
     *          expected to stay non-blocking.
     */
    GraphEngine createEngine(GraphConfig config);

    /**
     * Returns the stable identifier this provider is routed by in configuration and
     * diagnostic JFR events (e.g. {@code "postgres-community"}, {@code "neo4j-community"},
     * {@code "postgres-enterprise"}).
     *
     * @return stable provider identifier
     */
    String providerId();

    /**
     * Returns the human-readable name this provider reports in bootstrap JFR events and
     * diagnostics (e.g. {@code "ExerisCommunity/JdbcGraph"}, {@code "ExerisEnterprise/NativeGraph"}).
     *
     * @return human-readable provider name; never {@code null}
     */
    String providerName();

    /**
     * Returns this provider's selection priority; when more than one provider is on the
     * classpath, the bootstrapper picks the highest value. Convention: Community is
     * {@code 0}, Enterprise is {@code 100}.
     *
     * @return priority (higher wins)
     */
    default int priority() {
        return 0;
    }
}

