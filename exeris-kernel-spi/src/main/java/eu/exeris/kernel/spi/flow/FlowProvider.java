/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 *       virtual-thread scheduler, no ordering guarantees, no off-heap,
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
 * <p><b>Allocation:</b> allocates (one {@link FlowEngine} per {@link #createEngine} call) —
 * and nothing more: native memory and thread starts belong to {@link FlowEngine#start()}.
 * <p><b>Thread confinement:</b> bootstrap thread — {@link #createEngine} is called once, by the
 * bootstrapper, before {@link FlowEngine#start()}.
 * <p><b>Ownership:</b> the bootstrapper owns the returned engine and closes it via
 * {@link FlowEngine#close()} on shutdown; the provider retains nothing after it returns.
 *
 * @implSpec Implementations obtain their {@link eu.exeris.kernel.spi.memory.MemoryAllocator},
 *           {@link eu.exeris.kernel.spi.persistence.PersistenceEngine} and
 *           {@link eu.exeris.kernel.spi.telemetry.TelemetrySink} from
 *           {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots, never as
 *           constructor parameters — that is what keeps the SPI isolated from what wired it.
 * @since 0.5
 * @see FlowEngine
 * @see FlowEngineConfig
 * @see eu.exeris.kernel.spi.context.KernelProviders#FLOW_ENGINE
 */
public interface FlowProvider {

    /**
     * Returns the stable, programmatic identifier for this provider.
     *
     * <p>Used for configuration routing and diagnostic JFR events.
     * Examples: {@code "community"}, {@code "enterprise"}.
     *
     * @return provider identifier; never {@code null}
     * @implSpec A stable string constant that does not change between releases — operators route
     *           configuration on it and correlate JFR records by it.
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
     * <p>Called <b>once</b> during kernel bootstrap, before {@link FlowEngine#start()}.
     *
     * @param config the flow engine configuration (non-null)
     * @return an engine constructed but not started — its components are not yet initialised and
     *         nothing may be scheduled on it until {@link FlowEngine#start()} returns
     * @throws FlowProviderException {@code EX-FLOW-7001} if the engine cannot be created from the
     *         given configuration, carrying {@code providerName} and a static {@code reason}
     * @implSpec No I/O, no native memory, no threads here — every heavy initialisation is deferred
     *           to {@link FlowEngine#start()}, so that a bootstrap that selects a provider and then
     *           fails elsewhere has nothing to unwind. The engine reads the
     *           {@link eu.exeris.kernel.spi.memory.MemoryAllocator} it needs from the
     *           {@link eu.exeris.kernel.spi.context.KernelProviders#MEMORY_ALLOCATOR} scoped slot
     *           rather than taking it as a parameter.
     */
    FlowEngine createEngine(FlowEngineConfig config);
}

