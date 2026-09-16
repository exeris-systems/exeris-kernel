/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.core.telemetry.jfr.JfrEventWarmup;

import java.util.List;
import java.util.Map;

/**
 * Every JFR event class this driver declares, split the same way
 * {@link eu.exeris.kernel.core.telemetry.jfr.CoreJfrEventCatalogue} splits the engine's: warmed when
 * the owning subsystem starts, or deliberately left to initialise at its first emit.
 *
 * <p>The mechanism, the measurement and why the catalogue holds names rather than class literals are
 * stated once, on {@link JfrEventWarmup} and on the Core catalogue, and are not repeated here. What
 * this file adds is this driver's own classification — and the guarantee, enforced by
 * {@code JfrEventCatalogueCoverageTest}, that no event class in this module is in neither bucket.
 *
 * <p>The Kafka driver ships in its own module and is not covered here; it warms its own three events
 * where its engine starts.
 *
 * @since 0.12
 * @see JfrEventWarmup
 */
public final class CommunityJfrEventCatalogue {

    /** Hot-path event classes, keyed by the {@code Subsystem.name()} that warms them. */
    private static final Map<String, List<String>> HOT_PATH = Map.ofEntries(
            // transport — per accept, per connection and per drain, from the acceptor's and
            // the caller's virtual threads.
            Map.entry("transport", List.of(
                    "eu.exeris.kernel.community.transport.CommunityAcceptFaultEvent",
                    "eu.exeris.kernel.community.transport.CommunityAcceptRetryEvent",
                    "eu.exeris.kernel.community.transport.CommunityConnectionIdleTimeoutEvent",
                    "eu.exeris.kernel.community.transport.CommunityConnectionRefusedEvent",
                    "eu.exeris.kernel.community.transport.CommunityReactorDispatchFaultEvent",
                    "eu.exeris.kernel.community.transport.CommunityTransportDrainEvent",
                    "eu.exeris.kernel.community.transport.TransportTlsDeclinedEvent")),
            // http — per routed request and per pooled connection; a rapid-reset flood arrives
            // as a burst by definition.
            Map.entry("http", List.of(
                    "eu.exeris.kernel.community.http.RouteExecutionEvent",
                    "eu.exeris.kernel.community.http.CommunityHttpClientPoolEvent",
                    "eu.exeris.kernel.community.http.Http2RapidResetFloodEvent")),
            // websocket — per session, on the virtual thread that owns the upgraded stream.
            Map.entry("websocket", List.of(
                    "eu.exeris.kernel.community.websocket.CommunityWebSocketLifecycleEvent")),
            // events — per publish, per append and per queue overflow; an overflow arrives as a burst.
            Map.entry("events", List.of(
                    "eu.exeris.kernel.community.events.jfr.CommunityEventQueueOverflowEvent",
                    "eu.exeris.kernel.community.events.jfr.CommunityEventPayloadEncodeFailedEvent",
                    "eu.exeris.kernel.community.events.jfr.EventLogAppendConflictEvent",
                    "eu.exeris.kernel.community.events.jfr.EventLogAppendFailedEvent",
                    "eu.exeris.kernel.community.events.jfr.EventLoopFailureEvent")),
            // flow — per snapshot write and per optimistic-lock conflict; conflicts arrive together under contention.
            Map.entry("flow", List.of(
                    "eu.exeris.kernel.community.flow.FlowSnapshotSaveFailedEvent",
                    "eu.exeris.kernel.community.flow.OptimisticLockConflictEvent")),
            // memory — the hottest path there is: allocation, release and overflow return, on
            // the allocating virtual thread.
            Map.entry("memory", List.of(
                    "eu.exeris.kernel.community.memory.CommunityAllocationEvent",
                    "eu.exeris.kernel.community.memory.CommunityReleaseEvent",
                    "eu.exeris.kernel.community.memory.CommunityOverflowReturnEvent")),
            // security — per request, on the request virtual thread.
            Map.entry("security", List.of(
                    "eu.exeris.kernel.community.security.CommunityIdentityJfrEvents$IdentityValidationEvent",
                    "eu.exeris.kernel.community.security.CommunityIdentityJfrEvents$IdentityRejectionEvent")),
            // storage — per blob transfer and per isolation or ceiling refusal, on the request virtual thread.
            Map.entry("storage", List.of(
                    "eu.exeris.kernel.community.storage.CommunityBlobFailures$BlobTransferFailedEvent",
                    "eu.exeris.kernel.community.storage.CommunityBlobFailures$BlobCeilingExceededEvent",
                    "eu.exeris.kernel.community.storage.CommunityBlobFailures$BlobIsolationDeniedEvent")),
            // scheduling — per job dispatch, completion and failure, on the job's virtual thread.
            Map.entry("scheduling", List.of(
                    "eu.exeris.kernel.community.scheduling.CommunityJobJfrEvents$JobDispatchEvent",
                    "eu.exeris.kernel.community.scheduling.CommunityJobJfrEvents$JobCompletionEvent",
                    "eu.exeris.kernel.community.scheduling.CommunityJobJfrEvents$JobFailureEvent")),
            // crypto — per handshake, on the virtual thread driving it.
            Map.entry("crypto", List.of(
                    "eu.exeris.kernel.community.crypto.CommunityTlsHandshakeEvent")));

    private static final List<String> DELIBERATELY_COLD = List.of(
            "eu.exeris.kernel.community.crypto.CommunityProviderBootstrapEvent",
            "eu.exeris.kernel.community.diagnostics.CommunityKernelDiagnosticsEvent",
            "eu.exeris.kernel.community.http.CommunityHttpLifecycleEvent",
            "eu.exeris.kernel.community.security.CommunityJwksKeyRotationEvent");

    private CommunityJfrEventCatalogue() {
        // Static catalogue — no instances.
    }

    /**
     * Warms the hot-path event classes of one subsystem, on the calling thread.
     *
     * @param subsystemName the starting subsystem's {@code Subsystem.name()}; may be {@code null}
     */
    public static void warmHotPath(String subsystemName) {
        JfrEventWarmup.ensureInitialised(hotPathFor(subsystemName));
    }

    /**
     * The hot-path event classes of one subsystem.
     *
     * @param subsystemName the subsystem's {@code Subsystem.name()}; may be {@code null}
     * @return their fully-qualified names, empty for a subsystem this catalogue does not cover
     */
    public static List<String> hotPathFor(String subsystemName) {
        if (subsystemName == null) {
            return List.of();
        }
        return HOT_PATH.getOrDefault(subsystemName, List.of());
    }

    /**
     * Every hot-path event class in this module, across all subsystems.
     *
     * @return their fully-qualified names; never {@code null}
     */
    public static List<String> allHotPath() {
        return HOT_PATH.values().stream().flatMap(List::stream).toList();
    }

    /**
     * The subsystem names this catalogue warms something for.
     *
     * @return the keys of the hot-path map; never {@code null}
     */
    public static List<String> warmedSubsystems() {
        return HOT_PATH.keySet().stream().sorted().toList();
    }

    /**
     * Event classes this driver deliberately does not warm: a lifecycle event emitted once per
     * engine, a key rotation the refresher drives, the provider bootstrap record, and the
     * diagnostics snapshot a tool asks for. None can arrive as a burst on a request path.
     *
     * @return their fully-qualified names; never {@code null}
     */
    public static List<String> deliberatelyCold() {
        return DELIBERATELY_COLD;
    }
}
