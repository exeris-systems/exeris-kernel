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

    public static boolean isEnabled() {
        return EVENT_TYPE.isEnabled();
    }

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