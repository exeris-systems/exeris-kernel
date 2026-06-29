/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.events.EventBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

final class CommunityEventsSubsystem extends AbstractCommunitySubsystem {

    private EventProvider eventProvider;
    private EventEngine eventEngine;

    @Override
    public String name() {
        return "events";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory", "persistence");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.RUNTIME;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        EventEngineConfig config = buildConfig(configProvider);
        EventBootstrap.BootstrapResult bootstrapResult = EventBootstrap.loadWithProvider(config);
        eventProvider = bootstrapResult.provider();
        eventEngine = bootstrapResult.engine();
    }

    @Override
    public void start() {
        if (eventEngine == null) {
            return;
        }
        eventEngine.start();
        markRunning(true);
    }

    @Override
    public void stop() {
        markRunning(false);
        if (eventEngine != null) {
            eventEngine.close();
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (eventProvider == null || eventEngine == null) {
            return defaultProviderBindings();
        }
        List<CommunityCarrierBindings.Binding<?>> bindings = new ArrayList<>();
        bindings.add(CommunityCarrierBindings.binding(KernelProviders.EVENT_PROVIDER, eventProvider));
        bindings.add(CommunityCarrierBindings.binding(KernelProviders.EVENT_ENGINE, eventEngine));
        // ADR-046: bind the event-payload codec registry into the kernel scope so the producer
        // (the generated *EventPublisher) can resolve a codec via
        // KernelProviders.eventPayloadCodecRegistry() without DI — the event-side mirror of how
        // CommunityHttpSubsystem binds HTTP_REQUEST_BODY_DECODER_REGISTRY (ADR-036). Optional and
        // ifPresent-guarded: a provider shipping no codec leaves the slot unbound (publisher falls
        // back to EventPayload.empty()).
        eventProvider.eventPayloadCodecRegistry().ifPresent(registry -> bindings.add(
                CommunityCarrierBindings.binding(KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY, registry)));
        return CommunityCarrierBindings.operator(bindings);
    }

    private static EventEngineConfig buildConfig(ConfigProvider configProvider) {
        EventEngineConfig defaults = EventEngineConfig.communityDefaults();
        return new EventEngineConfig(
                configProvider.getString("event.engineName").orElse(defaults.engineName()),
                configProvider.getInt("event.queueCapacity").orElse(defaults.queueCapacity()),
                configProvider.getInt("event.batchSize").orElse(defaults.batchSize()),
                configProvider.getString("event.partitionName").orElse(defaults.partitionName()),
                configProvider.getLong("event.partitionBytes").orElse(defaults.partitionBytes()),
                configProvider.getInt("event.slabDescriptorCount").orElse(defaults.slabDescriptorCount()),
                configProvider.getInt("event.slabPayloadSmall").orElse(defaults.slabPayloadSmall()),
                configProvider.getInt("event.slabPayloadMedium").orElse(defaults.slabPayloadMedium()),
                configProvider.getInt("event.slabPayloadLarge").orElse(defaults.slabPayloadLarge()),
                configProvider.getBoolean("event.outboxEnabled").orElse(defaults.outboxEnabled()),
                configProvider.getInt("event.outboxBatchSize").orElse(defaults.outboxBatchSize())
        );
    }
}
