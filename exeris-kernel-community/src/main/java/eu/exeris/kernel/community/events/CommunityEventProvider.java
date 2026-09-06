/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodecRegistry;
import eu.exeris.kernel.spi.exceptions.events.EventProviderException;
import eu.exeris.kernel.community.json.CommunityJsonMappers;
import eu.exeris.kernel.community.json.JsonMapperScope;

import java.util.List;
import java.util.Optional;

/**
 * Community {@link java.util.ServiceLoader}-discovered {@link EventProvider}: creates the heap-backed,
 * single-node {@link CommunityEventEngine} and ships the default JSON
 * {@link EventPayloadCodecRegistry} (ADR-046).
 *
 * <p>Registers at {@link #priority()} {@code 0}, the Community convention — a higher-priority
 * provider on the classpath (e.g. the Kafka driver's {@code KafkaEventProvider} at {@code 50})
 * is selected instead.
 *
 * <p>The codec registry's {@code ObjectMapper} is sourced from
 * {@link CommunityJsonMappers#forScope(JsonMapperScope) CommunityJsonMappers.forScope(EVENTS)}
 * (the ADR-052 customization seam) rather than a hardcoded {@code new ObjectMapper()}, so an
 * application-registered {@code JsonMapperCustomizer} can tune the event-payload mapper; with
 * none registered the mapper is the bare Jackson default.
 */
public final class CommunityEventProvider implements EventProvider {

    private static final String PROVIDER_ID = "community";
    private static final String PROVIDER_NAME = "ExerisCommunity/Events";

    // Default JSON codec (ADR-046). Jackson stays a Community driver detail behind The Wall; the
    // registry is exposed to the bootstrapper as an SPI type and bound into
    // KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY. The mapper is sourced per-scope through the
    // ADR-052 customization seam (bare default when no JsonMapperCustomizer is registered).
    private static final EventPayloadCodecRegistry PAYLOAD_CODEC_REGISTRY =
            EventPayloadCodecRegistry.of(List.of(new CommunityJsonEventPayloadCodec(
                    CommunityJsonMappers.forScope(JsonMapperScope.EVENTS))));

    /**
     * Returns this provider's fixed human-readable name.
     *
     * @return the constant {@code "ExerisCommunity/Events"}
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Returns this provider's fixed programmatic identifier.
     *
     * @return the constant {@code "community"}
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Returns this provider's fixed selection priority.
     *
     * @return the constant {@code 0} — the Community convention
     */
    @Override
    public int priority() {
        return 0;
    }

    /**
     * Creates a new, not-yet-started {@link CommunityEventEngine} for {@code config}.
     *
     * @param config the event engine configuration
     * @return a newly created {@link CommunityEventEngine}
     * @throws EventProviderException EX-EVENT-6004 if {@code config} is {@code null}
     */
    @Override
    public EventEngine createEngine(EventEngineConfig config) {
        if (config == null) {
            throw EventProviderException.creationFailure(
                    PROVIDER_NAME,
                    "config is null",
                    new NullPointerException("config"));
        }
        return new CommunityEventEngine(config);
    }

    /**
     * Returns this provider's default JSON {@link EventPayloadCodecRegistry}.
     *
     * @return always {@link Optional#isPresent() present}
     */
    @Override
    public Optional<EventPayloadCodecRegistry> eventPayloadCodecRegistry() {
        return Optional.of(PAYLOAD_CODEC_REGISTRY);
    }
}
