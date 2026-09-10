/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.core.telemetry.JfrCommitGate;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Lightweight staged JFR event for persistence admission request-path telemetry.
 *
 * <p>Emits one of stages: {@code queue_enter}, {@code queue_wait}, {@code persistence_admission}.
 * Guarded by a cached {@link EventType#isEnabled()} probe.
 */
public final class PersistenceAdmissionStageEvent {

    /** Cached event-type probe; {@code isEnabled()} is dynamically evaluated. */
    private static final EventType EVENT_TYPE =
            EventType.getEventType(JfrEvent.class);

    private PersistenceAdmissionStageEvent() {
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
     * Records a persistence admission stage event.
     *
     * @param payload immutable stage snapshot for the admission request-path stage
     */
    public static void emit(Payload payload) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        JfrEvent evt = new JfrEvent();
        evt.providerId = payload.providerId();
        evt.stage = payload.stage();
        evt.queueDepth = payload.queueDepth();
        evt.queueWaitP95Ms = payload.queueWaitP95Ms();
        evt.accepted = payload.accepted();
        evt.decisionReason = payload.decisionReason();
        // VT-JFR safety: commit off the request virtual thread (see AdmissionDecisionEvent / JfrCommitGate).
        if (!JfrCommitGate.offer(evt)) {
            evt.commit();
        }
    }

    /**
     * Immutable payload for a single persistence admission stage event.
     *
     * @param providerId     stable identifier of the provider tier, e.g. {@code "postgres-community"}
     * @param stage          request-path stage name, one of {@code "queue_enter"}, {@code "queue_wait"}
     *                       or {@code "persistence_admission"}
     * @param queueDepth     depth of pending connection requests at this stage
     * @param queueWaitP95Ms P95 queue wait, in milliseconds, over the recent fairness window
     * @param accepted       {@code true} if the request was admitted at this stage; {@code false} if rejected
     * @param decisionReason deterministic reason code for this stage's decision
     */
    public record Payload(String providerId,
                          String stage,
                          int queueDepth,
                          long queueWaitP95Ms,
                          boolean accepted,
                          String decisionReason) {
    }

    @Name("eu.exeris.kernel.persistence.AdmissionStage")
    @Label("Persistence Admission Stage")
    @Category({"Exeris Kernel", "Persistence", "Admission"})
    @StackTrace(false)
    @SuppressWarnings("unused")
    private static final class JfrEvent extends Event {

        @Label("Provider ID")
        private String providerId;

        @Label("Stage")
        private String stage;

        @Label("Queue Depth")
        private int queueDepth;

        @Label("Queue Wait P95 (ms)")
        private long queueWaitP95Ms;

        @Label("Accepted")
        private boolean accepted;

        @Label("Decision Reason")
        private String decisionReason;
    }
}