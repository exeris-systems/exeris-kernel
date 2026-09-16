/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.events.JdbcEventStreamAppender;
import eu.exeris.kernel.community.events.JdbcEventStreamReader;
import eu.exeris.kernel.core.events.EventBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Bootstraps the Community event bus and, when persistence is present, the durable JDBC-backed event
 * log alongside it.
 *
 * <p>Depends on {@code memory} and {@code persistence}: the engine itself needs the kernel's
 * allocator, and whether a {@link JdbcEventStreamAppender}/{@link JdbcEventStreamReader} pair can be
 * built at all depends on {@link CommunityPersistenceSubsystem} having already set the shared
 * {@link PersistenceEngine} in {@link CommunityBootstrapServices} — the same Community-internal
 * handoff {@link CommunityFlowSubsystem} uses, needed because {@code providerBindings()} composition
 * runs after every subsystem's {@code initialize()} (ADR-022 §4).
 *
 * <p>The durable log is optional twice over: with no persistence engine bound, {@code initialize()}
 * leaves both the appender and the reader {@code null} and the corresponding
 * {@code EVENT_STREAM_APPENDER}/{@code EVENT_STREAM_READER} slots stay unbound (ADR-049); with no
 * event payload codec registry offered by the discovered {@link EventProvider}, the
 * {@code EVENT_PAYLOAD_CODEC_REGISTRY} slot likewise stays unbound and the generated event publisher
 * falls back to an empty payload (ADR-046). Callers read absence from the unbound slot rather than
 * from a placeholder that answers every question the same way.
 */
final class CommunityEventsSubsystem extends AbstractCommunitySubsystem {

    private EventProvider eventProvider;
    private EventEngine eventEngine;
    private EventStreamAppender eventStreamAppender;
    private EventStreamReader eventStreamReader;

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

        // ADR-049: bind the durable event-log appender/reader when a Community persistence engine is
        // present. Optional — with no engine the slots stay unbound and callers fall back to the bus
        // per the SPI Javadoc. dependsOn("persistence") guarantees the shared engine is set first.
        eventStreamAppender = resolveEventStreamAppender(config.engineName());
        eventStreamReader = resolveEventStreamReader(config.engineName());
    }

    // The shared PersistenceEngine is owned by CommunityPersistenceSubsystem; this subsystem only
    // borrows it to construct the appender/reader and MUST NOT close it. Fetching + consuming the
    // engine in a single-return helper mirrors CommunityFlowSubsystem.resolveSnapshotStore (ADR-022 §4).
    private EventStreamAppender resolveEventStreamAppender(String engineName) {
        PersistenceEngine engine = CommunityBootstrapServices.getSharedPersistenceEngine();
        return engine == null ? null : new JdbcEventStreamAppender(engine, engineName);
    }

    private EventStreamReader resolveEventStreamReader(String engineName) {
        PersistenceEngine engine = CommunityBootstrapServices.getSharedPersistenceEngine();
        return engine == null ? null : new JdbcEventStreamReader(engine, engineName, eventEngine.registry()::ordinalOf);
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
        // ADR-049: durable event-log appender/reader slots (optional; bound only when persistence
        // is present so the ordered/replayable log is available to sourcing/streaming consumers).
        if (eventStreamAppender != null) {
            bindings.add(CommunityCarrierBindings.binding(
                    KernelProviders.EVENT_STREAM_APPENDER, eventStreamAppender));
        }
        if (eventStreamReader != null) {
            bindings.add(CommunityCarrierBindings.binding(
                    KernelProviders.EVENT_STREAM_READER, eventStreamReader));
        }
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
