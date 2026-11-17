/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.events.JdbcEventStreamAppender;
import eu.exeris.kernel.community.events.JdbcEventStreamReader;
import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.core.events.EventBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Bootstraps the Community event bus and, when persistence is present, the durable JDBC-backed event
 * log alongside it.
 *
 * <p>Depends on {@code memory}, and by default on {@code persistence} as well: the engine itself needs
 * the kernel's allocator, and whether a {@link JdbcEventStreamAppender}/{@link JdbcEventStreamReader}
 * pair can be built at all depends on {@link CommunityPersistenceSubsystem} having already set the
 * shared {@link PersistenceEngine} in {@link CommunityBootstrapServices} — the same Community-internal
 * handoff {@link CommunityFlowSubsystem} uses, needed because {@code providerBindings()} composition
 * runs after every subsystem's {@code initialize()} (ADR-022 §4).
 *
 * <p>The {@code persistence} edge exists only for that durable log, and it forces the persistence
 * subsystem — and with it a connection pool — into every boot that selects {@code events}. Setting
 * {@code event.durableLogEnabled=false} removes the edge: {@link #dependsOn()} then returns
 * {@code ["memory"]}, {@code initialize()} never resolves the shared persistence engine (even when one
 * exists because something else booted persistence), and the appender/reader slots stay unbound. The
 * flag is read once, when {@link CommunitySubsystemProvider#getSubsystems} builds this subsystem,
 * because the {@code Subsystem} contract requires {@code dependsOn()} to answer identically on every
 * call.
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

    private static final String DURABLE_LOG_KEY = "event.durableLogEnabled";
    private static final List<String> DEPENDS_WITH_DURABLE_LOG = List.of("memory", "persistence");
    private static final List<String> DEPENDS_WITHOUT_DURABLE_LOG = List.of("memory");

    private final boolean durableLogEnabled;
    private final boolean durableLogKeyMalformed;
    private EventProvider eventProvider;
    private EventEngine eventEngine;
    private EventStreamAppender eventStreamAppender;
    private EventStreamReader eventStreamReader;

    /**
     * Builds the subsystem with every key at its default — equivalent to a {@code null} config.
     *
     * <p>Kept for the reflective name-readers in the architecture guards, which instantiate each
     * subsystem without a kernel context.
     *
     * @since 0.13
     */
    /* default */ CommunityEventsSubsystem() {
        this(null);
    }

    /**
     * Builds the subsystem, reading {@code event.durableLogEnabled} once.
     *
     * <p>{@code event.durableLogEnabled} (boolean, default {@code true}) decides whether this subsystem
     * depends on {@code persistence} and binds the JDBC durable event log
     * ({@code EVENT_STREAM_APPENDER}/{@code EVENT_STREAM_READER}). When it is {@code false} and
     * {@code event.outboxEnabled=true}, no outbox is constructed unless a persistence engine is bound
     * anyway — {@code CommunityEventEngine} builds its outbox orchestrator only when
     * {@code PERSISTENCE_ENGINE} is bound, and otherwise leaves it {@code null} without raising an
     * error.
     *
     * <p>A value that is not {@code true} or {@code false}, or a provider that throws while reading it,
     * cannot be reported from here: {@code getSubsystems} runs outside the orchestrator's failure
     * policy, and the checked {@code BootstrapException} is not available to it. The subsystem is
     * instead built with the default and {@link #initialize()} fails with a {@link SubsystemException}
     * naming the key, which the failure policy handles.
     *
     * @param config the resolved kernel configuration; {@code null} means every key takes its default
     * @since 0.13
     */
    /* default */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // a foreign ConfigProvider may throw anything
    CommunityEventsSubsystem(ConfigProvider config) {
        super();
        boolean enabled = true;
        boolean malformed = false;
        if (config != null) {
            try {
                boolean unparseable = config.getString(DURABLE_LOG_KEY)
                        .map(String::strip)
                        .filter(raw -> !"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw))
                        .isPresent();
                if (unparseable) {
                    malformed = true;
                } else {
                    enabled = config.getBoolean(DURABLE_LOG_KEY).orElse(true);
                }
            } catch (RuntimeException readFailure) {
                malformed = true;
            }
        }
        this.durableLogEnabled = enabled;
        this.durableLogKeyMalformed = malformed;
    }

    @Override
    public String name() {
        return "events";
    }

    @Override
    public List<String> dependsOn() {
        return durableLogEnabled ? DEPENDS_WITH_DURABLE_LOG : DEPENDS_WITHOUT_DURABLE_LOG;
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.RUNTIME;
    }

    @Override
    public void initialize() {
        if (durableLogKeyMalformed) {
            throw new SubsystemException(name(), SubsystemException.Phase.INITIALIZE,
                    "Config key event.durableLogEnabled is not a boolean (expected true or false)");
        }
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        EventEngineConfig config = buildConfig(configProvider);
        EventBootstrap.BootstrapResult bootstrapResult = EventBootstrap.loadWithProvider(config);
        eventProvider = bootstrapResult.provider();
        eventEngine = bootstrapResult.engine();

        // ADR-049: bind the durable event-log appender/reader when a Community persistence engine is
        // present. Optional — with no engine the slots stay unbound and callers fall back to the bus
        // per the SPI Javadoc. With the durable log enabled, dependsOn("persistence") guarantees the
        // shared engine is set first; with it disabled the engine is not even looked up.
        if (!durableLogEnabled) {
            return;
        }
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
        // This driver's hot-path JFR event classes initialise here, on the thread that starts the
        // subsystem: a virtual thread inside a <clinit> pins its carrier for the whole of it.
        CommunityJfrEventCatalogue.warmHotPath(name());
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
