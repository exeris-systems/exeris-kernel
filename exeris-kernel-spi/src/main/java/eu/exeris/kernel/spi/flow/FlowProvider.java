/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowProviderException;

/**
 * SPI: Pluggable Flow Engine Provider — the {@link java.util.ServiceLoader} boundary between
 * the kernel bootstrapper and the flow engine implementations.
 *
 * <h2>Open-Core (The Wall)</h2>
 * <p>Two tier bindings are loaded via {@link java.util.ServiceLoader} priority selection.
 * The SPI contract is implementation-blind:
 * <ul>
 *   <li><b>Community binding</b> (free, priority 0): heap-based orchestration,
 *       {@code StructuredTaskScope} scheduler, no ordering guarantees, no off-heap,
 *       no raw pointers. Lives in {@code exeris-kernel-community}.</li>
 *   <li><b>Enterprise binding</b> (secret sauce, priority 100): off-heap flow descriptors,
 *       lock-free state transitions, slab-based flow nodes, zero-GC, zero dynamic
 *       allocations after {@link FlowEngine#start()}. Lives in {@code exeris-kernel-enterprise}
 *       and must <em>never</em> be referenced from this SPI.</li>
 * </ul>
 *
 * <h2>Discovery &amp; Lifecycle</h2>
 * <ol>
 *   <li>{@code ServiceLoader.load(FlowProvider.class)} — discovers all providers on the classpath.</li>
 *   <li>Sort by {@link #priority()} descending — the highest-priority implementation wins.</li>
 *   <li>Call {@link #createEngine(FlowEngineConfig)} — creates the engine instance (no I/O here).</li>
 *   <li>Bind result to {@link eu.exeris.kernel.spi.context.KernelProviders#FLOW_ENGINE}.</li>
 *   <li>Call {@link FlowEngine#start()} — initialises all components.</li>
 *   <li>On shutdown: call {@link FlowEngine#close()}.</li>
 * </ol>
 *
 * <h2>SPI Dependency Injection</h2>
 * <p>Implementations obtain their {@link eu.exeris.kernel.spi.memory.MemoryAllocator},
 * {@link eu.exeris.kernel.spi.persistence.PersistenceEngine}, and
 * {@link eu.exeris.kernel.spi.telemetry.TelemetrySink} instances from
 * {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots — they do NOT
 * receive them as constructor parameters. This ensures clean SPI isolation.
 *
 * @see FlowEngine
 * @see FlowEngineConfig
 * @see eu.exeris.kernel.spi.context.KernelProviders#FLOW_ENGINE
 * @since 0.5.0
 */
public interface FlowProvider {

    /**
     * Returns the stable, programmatic identifier for this provider.
     *
     * <p>Used for configuration routing and diagnostic JFR events. Must be a stable
     * string constant that does not change between releases.
     * Examples: {@code "community"}, {@code "enterprise"}.
     *
     * @return provider identifier; never {@code null}
     */
    String providerId();

    /**
     * Human-readable provider name used in bootstrap JFR events and diagnostics.
     *
     * <p>Examples: {@code "ExerisCommunity/HeapFlow"}, {@code "ExerisEnterprise/SlabFlow"}.
     *
     * @return non-null, non-blank name
     */
    String providerName();

    /**
     * Selection priority when multiple providers are on the classpath.
     *
     * <p>Higher value wins. Convention:
     * <ul>
     *   <li>Community: {@code 0}</li>
     *   <li>Enterprise: {@code 100}</li>
     *   <li>Test/Noop: {@code -1}</li>
     * </ul>
     *
     * @return priority value used for provider selection (higher wins)
     */
    default int priority() {
        return 0;
    }

    /**
     * Creates the {@link FlowEngine} instance for the given configuration.
     *
     * <p>This method is called <b>once</b> during kernel bootstrap, before {@link FlowEngine#start()}.
     * Implementations MUST NOT perform I/O, allocate native memory, or start threads here —
     * defer all heavy initialisation to {@link FlowEngine#start()}.
     *
     * <p>The engine obtains the required {@link eu.exeris.kernel.spi.memory.MemoryAllocator}
     * directly from the {@link eu.exeris.kernel.spi.context.KernelProviders#MEMORY_ALLOCATOR}
     * scoped value slot to prevent parameter pollution.
     *
     * @param config the flow engine configuration (non-null)
     * @return a newly created (but not yet started) {@link FlowEngine} instance
     * @throws FlowProviderException if the engine cannot be created from the given configuration
     */
    FlowEngine createEngine(FlowEngineConfig config);
}

