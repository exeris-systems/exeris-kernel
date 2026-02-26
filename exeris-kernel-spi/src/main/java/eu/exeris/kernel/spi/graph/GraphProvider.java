/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 *       This binding lives in {@code exeris-kernel-enterprise} and must
 *       <em>never</em> be referenced from this SPI.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. The kernel bootstrapper selects the
 * highest-{@link #priority()} provider:
 * <pre>{@code
 * GraphProvider provider = ServiceLoader.load(GraphProvider.class)
 *     .stream()
 *     .map(ServiceLoader.Provider::get)
 *     .max(Comparator.comparingInt(GraphProvider::priority))
 *     .orElseThrow(() -> new GraphBootstrapException("N/A", "No GraphProvider on classpath"));
 *
 * GraphEngine engine = provider.createEngine(config);
 * ScopedValue.where(KernelProviders.GRAPH_ENGINE, engine).run(kernel::startSubsystems);
 * }</pre>
 *
 * <h2>SPI Compliance</h2>
 * <p>The <strong>API surface</strong> of this interface is implementation-blind:
 * no backend-specific types (JDBC, Neo4j Bolt, io_uring, Redis, DataSource, etc.)
 * appear in method signatures, return types, or thrown exceptions. The Open-Core
 * section above names those technologies only to document the bindings that are
 * expected to implement this interface — they must never leak into the SPI itself.
 *
 * <h2>SPI Dependency Injection</h2>
 * <p>Implementations obtain their {@link eu.exeris.kernel.spi.memory.MemoryAllocator},
 * {@link eu.exeris.kernel.spi.persistence.PersistenceEngine}, and
 * {@link eu.exeris.kernel.spi.security.SecurityProvider} from
 * {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots — they do NOT
 * receive them as constructor parameters. This ensures clean SPI isolation.
 *
 * @since 0.5.0
 * @see GraphEngine
 * @see GraphConfig
 */
public interface GraphProvider {

    /**
     * Creates and initialises a {@link GraphEngine} from the given configuration.
     *
     * <p>This is a potentially blocking call (connection pool setup, PGQ version check,
     * memory partition claim). It MUST NOT be called on a virtual thread that is
     * expected to be non-blocking.
     *
     * <p>Implementations read upstream providers from {@code KernelProviders} scoped slots:
     * <ul>
     *   <li>{@code KernelProviders.MEMORY_ALLOCATOR} — for buffer allocation</li>
     *   <li>{@code KernelProviders.PERSISTENCE_ENGINE} — for database access (Community)</li>
     *   <li>{@code KernelProviders.STORAGE_CONTEXT} — for tenant isolation</li>
     * </ul>
     *
     * @param config graph subsystem configuration
     * @return a fully initialised, ready-to-serve graph engine
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphBootstrapException if the engine
     *         cannot be created (missing backend, connection failure, etc.)
     */
    GraphEngine createEngine(GraphConfig config);

    /**
     * Unique identifier for this graph provider (e.g., {@code "postgres-community"},
     * {@code "neo4j-community"}, {@code "postgres-enterprise"}).
     *
     * <p>Used in configuration routing and diagnostic JFR events.
     *
     * @return stable provider identifier
     */
    String providerId();

    /**
     * Display name used in bootstrap JFR events and diagnostics
     * (e.g., {@code "ExerisCommunity/JdbcGraph"}, {@code "ExerisEnterprise/NativeGraph"}).
     *
     * @return human-readable provider name; never {@code null}
     */
    String providerName();

    /**
     * Selection priority when multiple providers are on the classpath.
     * Higher value wins.
     *
     * <p>Convention:
     * <ul>
     *   <li>Community: {@code 0}</li>
     *   <li>Enterprise: {@code 100}</li>
     * </ul>
     *
     * @return priority (higher wins)
     */
    default int priority() {
        return 0;
    }
}

