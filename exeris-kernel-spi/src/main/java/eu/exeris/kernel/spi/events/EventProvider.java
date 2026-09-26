/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.5
 * @see EventEngine
 * @see eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE
 */
public interface EventProvider {

    /**
     * Names this provider for a human reading a log line or a JFR recording — free-form, and not
     * the value anything routes on.
     *
     * <p>Examples: {@code "StandardEvents"}, {@code "NativeEvents"}.
     *
     * @return a non-null, non-blank display name; {@link #providerId()} is the stable identifier
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
     * @implSpec A stable string constant that does not change between releases — configuration
     *           and diagnostics key on it.
     */
    String providerId();

    /**
     * Decides which provider wins when several are on the classpath: the highest value is the one
     * the bootstrapper instantiates, and every other discovered provider is passed over.
     *
     * <p>Convention:
     * <ul>
     *   <li>Community: {@code 0}</li>
     *   <li>Enterprise: {@code 100}</li>
     *   <li>Test/Noop: {@code -1}</li>
     * </ul>
     *
     * @return this provider's rank; defaults to {@code 0}, the Community slot
     * @apiNote Values between the tier anchors express intra-tier precedence rather than tier: a
     *          Community driver at {@code 50} outranks the in-memory default at {@code 0} while
     *          still yielding to the Enterprise slot at {@code 100}.
     */
    default int priority() {
        return 0;
    }

    /**
     * Constructs the engine this provider offers, in an inert state — wired but holding no
     * resource, so a failure here costs nothing to unwind.
     *
     * <p>The engine obtains the required {@link eu.exeris.kernel.spi.memory.MemoryAllocator}
     * directly from the {@link eu.exeris.kernel.spi.context.KernelProviders#MEMORY_ALLOCATOR}
     * scoped value slot to prevent parameter pollution.
     *
     * @param config the event engine configuration (non-null)
     * @return a newly created {@link EventEngine} that has not been started
     * @throws EventProviderException {@code EX-EVENT-6004} if the engine cannot be created from
     *         the given configuration; {@code rawArgs} carry
     *         {@code [String providerName, String reason]}
     * @implSpec Performs no I/O, allocates no native memory and starts no thread — every such
     *           cost is deferred to {@link EventEngine#start()}, so that a kernel which fails
     *           later during bootstrap has nothing here to release.
     * @apiNote The bootstrapper calls this once, on the winning provider only.
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
     * @return the codec registry, or {@link Optional#empty()} when this provider ships none — in
     *         which case the producer falls back to {@link EventPayload#empty()} rather than
     *         failing
     * @since 0.10
     */
    default Optional<EventPayloadCodecRegistry> eventPayloadCodecRegistry() {
        return Optional.empty();
    }
}
