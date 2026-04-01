/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted for each admission control decision in the persistence layer.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AdmissionDecisionEvent.emit(new AdmissionDecisionEvent.Payload(
 *         providerId, accepted, queueDepth, saturation, fairnessRatio,
 *         queueDepthP95, queueWaitP95Ms, decisionReason));
 * }</pre>
 *
 * <h2>Hot-Path Guard</h2>
 * <p>Guards on {@link FlightRecorder#isInitialized()} and {@link Event#isEnabled()}
 * before allocating event object — zero allocation when JFR is inactive.
 * {@link StackTrace @StackTrace(false)} eliminates stack-walk overhead.
 *
 * <h2>Fairness Composite Metric</h2>
 * <p>The {@code fairnessRatio} field tracks per-1s-bucket fairness as:
 * <pre>fairnessRatio = min(throughput per VT tier) / mean(throughput)</pre>
 * Target: ≥0.90 for equitable service delivery.
 *
 * @since 0.6.0
 */
@Name("eu.exeris.kernel.persistence.AdmissionDecision")
@Label("Persistence Admission Decision")
@Description("Emitted when a request is admitted or rejected by the persistence layer admission gate")
@Category({"Exeris Kernel", "Persistence", "Fairness"})
@StackTrace(false)
public final class AdmissionDecisionEvent {

    /** Cached event-type probe; {@code isEnabled()} is dynamically evaluated. */
    private static final EventType EVENT_TYPE =
            EventType.getEventType(JfrEvent.class);

    private AdmissionDecisionEvent() {
    }

    public static boolean isEnabled() {
        return EVENT_TYPE.isEnabled();
    }

    /**
     * Records an admission decision event.
     *
     * <p>Emitted for every HTTP request boundary decision whether to route to
     * the persistence layer or trigger backpressure (503 ServiceUnavailable).
     *
     * @param payload immutable stage snapshot for admission telemetry
     */
    public static void emit(Payload payload) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        JfrEvent evt = new JfrEvent();
        evt.providerId = payload.providerId();
        evt.accepted = payload.accepted();
        evt.queueDepth = payload.queueDepth();
        evt.saturation = payload.saturation();
        evt.fairnessRatio = payload.fairnessRatio();
        evt.queueDepthP95 = payload.queueDepthP95();
        evt.queueWaitP95Ms = payload.queueWaitP95Ms();
        evt.decisionReason = payload.decisionReason();
        evt.commit();
    }

    /**
     * Immutable payload for a single admission decision event.
     */
    public record Payload(String providerId,
                          boolean accepted,
                          int queueDepth,
                          double saturation,
                          double fairnessRatio,
                          long queueDepthP95,
                          long queueWaitP95Ms,
                          String decisionReason) {
    }

    @Name("eu.exeris.kernel.persistence.AdmissionDecision")
    @Label("Persistence Admission Decision")
    @Description("Emitted when a request is admitted or rejected by the persistence layer admission gate")
    @Category({"Exeris Kernel", "Persistence", "Fairness"})
    @StackTrace(false)
    @SuppressWarnings("unused")
    private static final class JfrEvent extends Event {

        /** Provider tier identifier (e.g., {@code "postgres-community"}). */
        @Label("Provider ID")
        private String providerId;

        /** {@code true} if request was accepted; {@code false} if rejected with backpressure. */
        @Label("Accepted")
        private boolean accepted;

        /** Current depth of pending connection requests in queue. */
        @Label("Queue Depth")
        private int queueDepth;

        /** Active connections as percentage of max pool size (0.0–1.0). */
        @Label("Saturation")
        private double saturation;

        /** Fairness ratio: min(tier throughput) / mean(throughput). Target ≥0.90. */
        @Label("Fairness Composite")
        private double fairnessRatio;

        /** P95 queue depth over recent fairness window. */
        @Label("Queue Depth P95")
        private long queueDepthP95;

        /** P95 queue wait in milliseconds over recent fairness window. */
        @Label("Queue Wait P95 (ms)")
        private long queueWaitP95Ms;

        /** Deterministic admission decision reason for stage-level diagnostics. */
        @Label("Decision Reason")
        private String decisionReason;
    }
}
