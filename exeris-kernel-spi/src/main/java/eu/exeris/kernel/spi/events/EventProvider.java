/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.events.EventProviderException;

/**
 * SPI: Pluggable Event Engine Provider.
 *
 * <h2>Open-Core Entry Point</h2>
 * <p>This interface is the {@link java.util.ServiceLoader} boundary between the
 * kernel bootstrapper and the event engine implementations. The bootstrapper
 * remains completely blind to the chosen implementation — it only knows this interface.
 *
 * <h2>Registration</h2>
 * <p>Each implementation ships a ServiceLoader configuration containing the
 * fully-qualified class name of the provider implementation.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@code ServiceLoader.load(EventProvider.class)} — discovers all providers on the classpath.</li>
 *   <li>Sort by {@link #priority()} descending — the highest-priority implementation wins.</li>
 *   <li>Call {@link #createEngine(EventEngineConfig)} — creates the engine instance.</li>
 *   <li>Bind result to {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE}.</li>
 *   <li>Call {@link EventEngine#start()} — initialises all components.</li>
 *   <li>On shutdown: call {@link EventEngine#close()}.</li>
 * </ol>
 *
 * @since 0.5.0
 * @see EventEngine
 * @see eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE
 */
public interface EventProvider {

    /**
     * Human-readable provider name.
     *
     * <p>Examples: {@code "StandardEvents"}, {@code "NativeEvents"}.
     *
     * @return non-null, non-blank name
     */
    String name();

    /**
     * Returns the stable, programmatic identifier for this provider.
     *
     * <p>This ID is used for configuration routing and diagnostic JFR events and
     * must be a stable string constant that does not change between releases.
     * Examples: {@code "community"}, {@code "enterprise"}.
     *
     * @return provider identifier; never {@code null}
     */
    String providerId();

    /**
     * Selection priority. Higher value wins when multiple providers are on the classpath.
     *
     * <p>Convention: Community=100, Enterprise=200, Test/Noop=0.
     *
     * @return non-negative priority
     */
    int priority();

    /**
     * Creates the {@link EventEngine} instance for the given configuration.
     *
     * <p>This method is called <b>once</b> during kernel bootstrap, before {@link EventEngine#start()}.
     * Implementations MUST NOT perform I/O, allocate native memory, or start threads here —
     * defer all heavy initialisation to {@link EventEngine#start()}.
     *
     * <p>The engine obtains the required {@link eu.exeris.kernel.spi.memory.MemoryAllocator}
     * directly from the {@link eu.exeris.kernel.spi.context.KernelProviders#MEMORY_ALLOCATOR}
     * scoped value slot to prevent parameter pollution.
     *
     * @param config  the event engine configuration (non-null)
     * @return a newly created (but not yet started) {@link EventEngine} instance
     * @throws EventProviderException if the engine cannot be created from the given configuration
     */
    EventEngine createEngine(EventEngineConfig config);
}
