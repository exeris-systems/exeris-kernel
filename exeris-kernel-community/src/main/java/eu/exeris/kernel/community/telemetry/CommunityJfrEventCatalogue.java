/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.core.telemetry.jfr.JfrEventCatalogue;

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
    private static final JfrEventCatalogue CATALOGUE = new JfrEventCatalogue(
            CommunityJfrEventCatalogue.class,
            Map.ofEntries(
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
                    "eu.exeris.kernel.community.crypto.CommunityTlsHandshakeEvent"))),
            List.of(
            "eu.exeris.kernel.community.crypto.CommunityProviderBootstrapEvent",
            "eu.exeris.kernel.community.diagnostics.CommunityKernelDiagnosticsEvent",
            "eu.exeris.kernel.community.http.CommunityHttpLifecycleEvent",
            "eu.exeris.kernel.community.security.CommunityJwksKeyRotationEvent"));

    private CommunityJfrEventCatalogue() {
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
     * <p>Shorthand for {@code catalogue().warmHotPath(name)}, kept because it is what the two
     * start seams call and reads as what it does at those call sites.
     *
     * @param subsystemName the starting subsystem's {@code Subsystem.name()}; may be {@code null}
     */
    public static void warmHotPath(String subsystemName) {
        CATALOGUE.warmHotPath(subsystemName);
    }
}
