/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

import java.util.List;
import java.util.Map;

/**
 * Every JFR event class this module declares, in one of two buckets: the ones a subsystem warms when
 * it starts, and the ones it deliberately leaves to initialise wherever their first emit lands.
 *
 * <h2>What the split is for</h2>
 * <p>{@link JfrEventWarmup} states the mechanism: a virtual thread inside a {@code <clinit>} cannot
 * unmount, so the first emit of a cold event class pins a carrier for as long as the class takes to
 * load and register — about 1 ms on an idle host, and tens of milliseconds where carriers are scarce
 * and contended. Warming moves that cost to the thread that starts the subsystem.
 *
 * <p>It is not free, which is why this is a split and not a list. Initialising all 128 event classes
 * in the kernel costs upwards of 100 ms (measured: 103 classes in 79 ms with a recording running,
 * 108 ms without), and a large share of them are failure-path events a given process may never emit.
 * So a class is warmed when it is reached from a virtual thread on a path that can produce it
 * <em>concurrently</em> — per request, per stream, per step, per allocation — and left cold when it
 * is emitted once per process, from a bootstrap or maintenance thread, or only by a path whose own
 * cost dwarfs a millisecond. One group is cold for a second reason, and the list says so per entry
 * rather than per group: an event on a hot path the kernel has no seam to warm from. That happens
 * two ways — nothing constructs the component that emits it, or something constructs it and nothing
 * in the kernel calls the factory — and the two are not interchangeable, so the list gives each its
 * own sentence.
 *
 * <p>Neither bucket is a judgement about how useful an event is. A cold event is fully supported and
 * exactly as observable; it simply pays its own initialisation the first time it fires.
 *
 * <h2>Names, not class literals</h2>
 * <p>Two thirds of this kernel's event classes are package-private, so no single class can name them
 * with a literal. A stale name here is invisible at runtime — {@code JfrEventCatalogueCoverageTest} is
 * what catches it, by resolving every name and matching the union of both buckets against the event
 * classes actually present in this module.
 *
 * @since 0.12
 * @see JfrEventWarmup
 */
public final class CoreJfrEventCatalogue {

    /**
     * Hot-path event classes, keyed by the {@code Subsystem.name()} that warms them.
     *
     * <p>A map rather than a method per subsystem: the entries are names, so nothing here loads a
     * class, and the dispatch is a lookup rather than a switch that grows a branch per subsystem.
     */
    private static final JfrEventCatalogue CATALOGUE = new JfrEventCatalogue(
            CoreJfrEventCatalogue.class,
            Map.ofEntries(
            // transport — per connection, per stream and per ingress batch, on the virtual thread PAQS spawned.
            Map.entry("transport", List.of(
                    "eu.exeris.kernel.core.transport.jfr.ConnectionEstablishedEvent",
                    "eu.exeris.kernel.core.transport.jfr.PaqsHandlerFailureEvent",
                    "eu.exeris.kernel.core.transport.jfr.StreamAcceptedEvent",
                    "eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent",
                    "eu.exeris.kernel.core.transport.jfr.StreamShedEvent",
                    "eu.exeris.kernel.core.transport.jfr.TransportIngressQueueDepthEvent",
                    "eu.exeris.kernel.core.transport.jfr.TransportQueueBackpressureAlertEvent")),
            // http — per HTTP stream and per aggregate buffer, on the request virtual thread.
            Map.entry("http", List.of(
                    "eu.exeris.kernel.core.http.jfr.StreamOpenedEvent",
                    "eu.exeris.kernel.core.http.jfr.StreamClosedEvent",
                    "eu.exeris.kernel.core.http.jfr.StreamBackpressureParkEvent",
                    "eu.exeris.kernel.core.http.jfr.StreamAbortiveTeardownEvent",
                    "eu.exeris.kernel.core.http.jfr.HttpAggregateBufferHeldEvent",
                    "eu.exeris.kernel.core.http.jfr.HttpAggregateBufferForcedReleaseEvent")),
            // events — per publish, per projection apply and per outbox transition; the bus
            // dispatches handlers on virtual threads.
            Map.entry("events", List.of(
                    "eu.exeris.kernel.core.events.jfr.EventBusPublishEvent",
                    "eu.exeris.kernel.core.events.jfr.ProjectionAppliedEvent",
                    "eu.exeris.kernel.core.events.jfr.ProjectionHandlerFailureEvent",
                    "eu.exeris.kernel.core.events.jfr.OutboxStateTransitionEvent",
                    "eu.exeris.kernel.core.events.jfr.OutboxBatchFlushedEvent",
                    "eu.exeris.kernel.core.events.jfr.OutboxDlqEvent",
                    "eu.exeris.kernel.core.events.jfr.OutboxLoopFailureEvent")),
            // flow — per step, per timeout and per snapshot write, on the virtual thread running the flow.
            Map.entry("flow", List.of(
                    "eu.exeris.kernel.core.flow.FlowStepFailedEvent",
                    "eu.exeris.kernel.core.flow.FlowTimeoutEvent",
                    "eu.exeris.kernel.core.flow.FlowDeferredWakeFailedEvent",
                    "eu.exeris.kernel.core.flow.FlowSnapshotPersistFailedEvent",
                    "eu.exeris.kernel.core.flow.FlowSchemaMismatchEvent",
                    "eu.exeris.kernel.core.flow.WakeOnLoadFallbackEvent")),
            // memory — taken on whichever virtual thread is allocating, and a pressure episode
            // produces them in a burst.
            Map.entry("memory", List.of(
                    "eu.exeris.kernel.core.memory.MemoryPressureEvent",
                    "eu.exeris.kernel.core.memory.ResourceArbiterDecisionEvent")),
            // persistence — per session, per connection and per transaction, on the request virtual thread.
            // JfrCommitDropEvent belongs here rather than in the cold list: it fires from
            // JfrEventCommitter.offer when the ring is full, on the same request virtual thread as
            // the four events feeding that ring, and by definition in a burst. The persistence
            // subsystem is what stands the committer and its gate up, so warming with its name is
            // what puts the class in front of the first drop.
            Map.entry("persistence", List.of(
                    "eu.exeris.kernel.core.persistence.ConnectionAcquireEvent",
                    "eu.exeris.kernel.core.persistence.ConnectionHoldEvent",
                    "eu.exeris.kernel.core.persistence.RequestSessionLifecycleEvent",
                    "eu.exeris.kernel.core.persistence.TransactionLifecycleEvent",
                    "eu.exeris.kernel.core.persistence.AdmissionDecisionEvent$JfrEvent",
                    "eu.exeris.kernel.core.persistence.PersistenceAdmissionStageEvent$JfrEvent",
                    "eu.exeris.kernel.core.telemetry.JfrCommitDropEvent")),
            // security — per request, on the request virtual thread.
            Map.entry("security", List.of(
                    "eu.exeris.kernel.core.security.jfr.SecurityJfrEvents$PrincipalBoundEvent",
                    "eu.exeris.kernel.core.security.jfr.SecurityJfrEvents$InsufficientPrivilegesEvent",
                    "eu.exeris.kernel.core.security.jfr.SecurityJfrEvents$SecurityContextMissingEvent",
                    "eu.exeris.kernel.core.security.jfr.SecurityJfrEvents$StorageContextDerivedEvent")),
            // crypto — per engine and per handshake phase; handshakes arrive together after a restart.
            Map.entry("crypto", List.of(
                    "eu.exeris.kernel.core.crypto.tls.TlsEngineBindEvent",
                    "eu.exeris.kernel.core.crypto.tls.TlsEngineCloseEvent",
                    "eu.exeris.kernel.core.crypto.tls.TlsHandshakeEvent",
                    "eu.exeris.kernel.core.crypto.tls.TlsHandshakeFailureEvent",
                    "eu.exeris.kernel.core.crypto.tls.TlsPhaseTransitionEvent",
                    "eu.exeris.kernel.core.crypto.openssl.CryptoContextAllocEvent")),
            // graph — per sync operation and per algorithm run, on the calling virtual thread.
            Map.entry("graph", List.of(
                    "eu.exeris.kernel.core.graph.AlgoOrchestratorEvent",
                    "eu.exeris.kernel.core.graph.GraphSyncOperationEvent",
                    "eu.exeris.kernel.core.graph.GraphSyncFailedEvent"))),
            List.of(
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$CircularDependencyDetectedEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$ConfigSettingsResolvedEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$KernelBootReadyEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$KernelShutdownCompleteEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$SubsystemHealthTransitionEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$SubsystemInitializedEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$SubsystemStartedEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents$SubsystemStoppedEvent",
            "eu.exeris.kernel.core.bootstrap.jfr.KernelStartEvent",
            "eu.exeris.kernel.core.config.jfr.DynamicReloadEvent$DynamicFieldReloadedEvent",
            "eu.exeris.kernel.core.config.jfr.DynamicReloadEvent$DynamicReloadFailedEvent",
            "eu.exeris.kernel.core.config.jfr.ImmutableReloadEvent$ImmutableReloadRefusedEvent",
            "eu.exeris.kernel.core.crypto.openssl.NativeCipherContextFreeFailureEvent",
            "eu.exeris.kernel.core.crypto.openssl.OpenSslLoadEvent",
            "eu.exeris.kernel.core.events.EventBootstrapSelectedEvent",
            "eu.exeris.kernel.core.flow.FlowBootstrapSelectedEvent",
            "eu.exeris.kernel.core.flow.FlowDefinitionMigratedEvent",
            "eu.exeris.kernel.core.flow.FlowEngineShutdownEvent",
            "eu.exeris.kernel.core.flow.FlowProgressDisabledEvent",
            "eu.exeris.kernel.core.graph.GraphBootstrapSelectedEvent",
            "eu.exeris.kernel.core.graph.GraphMetadataDiscoveryEvent",
            "eu.exeris.kernel.core.http.routing.HttpRouterRegisteredEvent",
            "eu.exeris.kernel.core.memory.CloseActionFailureEvent",
            "eu.exeris.kernel.core.memory.LeakDetectedEvent",
            "eu.exeris.kernel.core.memory.MemoryEnvironmentProbe$MemoryEnvironmentProbedEvent",
            "eu.exeris.kernel.core.memory.MemoryMaintenanceEvents$CycleEvent",
            "eu.exeris.kernel.core.memory.MemoryMaintenanceEvents$FailureEvent",
            "eu.exeris.kernel.core.memory.PeekMisuseEvent",
            "eu.exeris.kernel.core.persistence.PersistenceBootstrapSelectedEvent",
            "eu.exeris.kernel.core.persistence.PersistenceEngineBootstrapEvent",
            "eu.exeris.kernel.core.persistence.PersistenceTenantPoolCreatedEvent",
            "eu.exeris.kernel.core.persistence.PersistenceTenantPoolReclaimedEvent",
            "eu.exeris.kernel.core.persistence.SchemaMigrationRefusedEvent",
            "eu.exeris.kernel.core.scheduling.SchedulingBootstrapSelectedEvent",
            "eu.exeris.kernel.core.security.jfr.SecurityJfrEvents$RoleRegistryLoadedEvent",
            "eu.exeris.kernel.core.storage.StorageBootstrapSelectedEvent",
            // The sink stack below is cold for a different reason than the rest of this list: not
            // because it is cheap or rare, but because this kernel has no seam to warm it from. The
            // two entries reach that state by different routes, so each states its own; a reason
            // shared across entries holds for one of them and is false for the rest.
            //
            // AsyncTelemetryDropEvent: no main source anywhere in the reactor constructs an
            // AsyncTelemetrySink. AsyncTelemetrySink.start is called from AsyncTelemetrySinkTest
            // and CoreAsyncTelemetryRingBufferTckTest and from nothing else, so there is no
            // construction site to warm it at.
            "eu.exeris.kernel.core.telemetry.AsyncTelemetryDropEvent",
            // The six TelemetryJfrEvents below: a main source does construct the sink that emits
            // them — CommunityTelemetryProvider.createSinks builds a JfrTelemetrySink whenever
            // TelemetryConfig.jfrSinkEnabled() — and these six are exactly what it emits, from
            // increment/gauge/latency and from the typed error events. What makes them cold sits
            // one level further out: nothing in this kernel calls createSinks, nothing binds
            // KernelProviders.TELEMETRY_SINKS, and no Subsystem reports the name "telemetry", so
            // there is no start the warm-up could hang off. A host that stands the sinks up
            // initialises them where it builds them. Whatever stands the sink stack up in the
            // kernel is the seam these belong to — the v0.13 roadmap slice — and they leave this
            // list with it.
            "eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents$CarrierPinnedJfrEvent",
            "eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents$KernelLatencyJfrEvent",
            "eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents$KernelLifecycleJfrEvent",
            "eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents$KernelMetricJfrEvent",
            "eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents$MemoryExhaustionJfrEvent",
            "eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents$TransportBindJfrEvent"));

    private CoreJfrEventCatalogue() {
        // Static catalogue — no instances.
    }

    /**
     * This module's catalogue: both buckets, and the behaviour they share with every other module's.
     *
     * @return the catalogue; never {@code null}
     */
    public static JfrEventCatalogue catalogue() {
        return CATALOGUE;
    }

    /**
     * Warms the hot-path event classes of one subsystem, on the calling thread.
     *
     * <p>Shorthand for {@code catalogue().warmHotPath(name)}. Two seams call it —
     * {@code SubsystemOrchestrator.doStart}, and {@code NativeTcpCarrier.start()} for an engine
     * built without an orchestrator — and it stays a shorthand rather than being inlined to
     * {@code catalogue().warmHotPath(...)} because {@code JfrEventCatalogueCoverageTest} matches a
     * warm-up call by its target owner. Through {@code catalogue()} the owner is
     * {@link JfrEventCatalogue} for every module at once, and the guard could no longer tell one
     * module's seam from another's.
     *
     * @param subsystemName the starting subsystem's {@code Subsystem.name()}; may be {@code null}
     */
    public static void warmHotPath(String subsystemName) {
        CATALOGUE.warmHotPath(subsystemName);
    }
}
