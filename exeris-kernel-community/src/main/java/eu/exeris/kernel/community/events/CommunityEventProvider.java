/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public int priority() {
        return 0;
    }

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

    @Override
    public Optional<EventPayloadCodecRegistry> eventPayloadCodecRegistry() {
        return Optional.of(PAYLOAD_CODEC_REGISTRY);
    }
}
