/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.events.codec.EventPayloadCodecRegistry;
import eu.exeris.kernel.spi.exceptions.events.EventProviderException;

import java.util.Optional;

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
 * @see EventEngine
 * @see eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE
 * @since 0.5.0
 */
public interface EventProvider {

    /**
     * Human-readable provider name.
     *
     * <p>Examples: {@code "StandardEvents"}, {@code "NativeEvents"}.
     *
     * @return non-null, non-blank name
     */
    String providerName();

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
     * <p>Convention:
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
     * @param config the event engine configuration (non-null)
     * @return a newly created (but not yet started) {@link EventEngine} instance
     * @throws EventProviderException if the engine cannot be created from the given configuration
     */
    EventEngine createEngine(EventEngineConfig config);

    /**
     * Returns the provider's {@link EventPayloadCodecRegistry} for serializing typed
     * domain-event payloads to bytes (ADR-046), or {@link Optional#empty()} when this
     * provider ships no codec.
     *
     * <p>The bootstrapper binds the returned registry into
     * {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_PAYLOAD_CODEC_REGISTRY}
     * so the producer (the generated {@code *EventPublisher}) can resolve a codec
     * without DI — the event-side mirror of how {@code HttpProvider.requestBodyDecoderRegistry()}
     * feeds the server-side request-decoder slot (ADR-036). Default empty keeps the
     * method additive: a provider without a codec still bootstraps events, and the
     * producer falls back to {@link EventPayload#empty()}.
     *
     * @return the codec registry, or empty when this provider ships none
     * @since 0.10.0
     */
    default Optional<EventPayloadCodecRegistry> eventPayloadCodecRegistry() {
        return Optional.empty();
    }
}
