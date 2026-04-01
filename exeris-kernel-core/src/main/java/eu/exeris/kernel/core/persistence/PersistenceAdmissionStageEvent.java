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
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Lightweight staged JFR event for persistence admission request-path telemetry.
 *
 * <p>Emits one of stages: {@code queue_enter}, {@code queue_wait}, {@code persistence_admission}.
 * Guarded by {@link FlightRecorder#isInitialized()} and {@link Event#isEnabled()}.
 */
@Name("eu.exeris.kernel.persistence.AdmissionStage")
@Label("Persistence Admission Stage")
@Category({"Exeris Kernel", "Persistence", "Admission"})
@StackTrace(false)
public final class PersistenceAdmissionStageEvent extends Event {

    /** Cached event-type probe; {@code isEnabled()} is dynamically evaluated. */
    private static final EventType EVENT_TYPE =
            EventType.getEventType(PersistenceAdmissionStageEvent.class);

    @Label("Provider ID")
    public String providerId;

    @Label("Stage")
    public String stage;

    @Label("Queue Depth")
    public int queueDepth;

    @Label("Queue Wait P95 (ms)")
    public long queueWaitP95Ms;

    @Label("Accepted")
    public boolean accepted;

    @Label("Decision Reason")
    public String decisionReason;

    public static void emit(Payload payload) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        PersistenceAdmissionStageEvent evt = new PersistenceAdmissionStageEvent();
        evt.providerId = payload.providerId();
        evt.stage = payload.stage();
        evt.queueDepth = payload.queueDepth();
        evt.queueWaitP95Ms = payload.queueWaitP95Ms();
        evt.accepted = payload.accepted();
        evt.decisionReason = payload.decisionReason();
        evt.commit();
    }

    public record Payload(String providerId,
                          String stage,
                          int queueDepth,
                          long queueWaitP95Ms,
                          boolean accepted,
                          String decisionReason) {
    }
}