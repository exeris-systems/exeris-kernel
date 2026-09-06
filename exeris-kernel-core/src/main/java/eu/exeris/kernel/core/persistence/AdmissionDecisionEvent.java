/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.core.telemetry.JfrCommitGate;

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
 * {@snippet lang="java" :
 * AdmissionDecisionEvent.emit(new AdmissionDecisionEvent.Payload(
 *         providerId, accepted, queueDepth, saturation, fairnessRatio,
 *         queueDepthP95, queueWaitP95Ms, decisionReason));
 * }
 *
 * <h2>Hot-Path Guard</h2>
 * <p>Uses a cached {@link EventType} probe and checks {@link EventType#isEnabled()}
 * before allocating event object — zero allocation when JFR is inactive.
 * {@link StackTrace @StackTrace(false)} eliminates stack-walk overhead.
 *
 * <h2>Fairness Composite Metric</h2>
 * <p>The {@code fairnessRatio} field records the acceptance ratio over the recent window:
 * <pre>fairnessRatio = accepted / (accepted + rejected)</pre>
 * Target: ≥0.90 for equitable service delivery.
 *
 * @since 0.5
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

    /**
     * Reports whether this event's JFR event type is currently enabled.
     *
     * @return {@code true} if an active recording would accept this event; {@code false} if
     *         {@link #emit} would return immediately without allocating a payload-backed event
     */
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
        // VT-JFR safety: commit off the request virtual thread via the platform-thread committer.
        // Falls back to inline commit only when no committer is installed (tests / pre-bootstrap,
        // where the caller is a platform thread and inline commit is safe).
        if (!JfrCommitGate.offer(evt)) {
            evt.commit();
        }
    }

    /**
     * Immutable payload for a single admission decision event.
     *
     * @param providerId     stable identifier of the provider tier, e.g. {@code "postgres-community"}
     * @param accepted       {@code true} if the request was admitted; {@code false} if rejected with backpressure
     * @param queueDepth     depth of pending connection requests at decision time
     * @param saturation     active connections as a fraction of max pool size, in {@code [0.0, 1.0]}
     * @param fairnessRatio  acceptance ratio over the recent window, {@code accepted / (accepted + rejected)}
     * @param queueDepthP95  P95 queue depth over the recent fairness window
     * @param queueWaitP95Ms P95 queue wait, in milliseconds, over the recent fairness window
     * @param decisionReason deterministic reason code for this admission decision
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

        /** Acceptance ratio over the recent window: accepted/(accepted+rejected). Target ≥0.90. */
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
