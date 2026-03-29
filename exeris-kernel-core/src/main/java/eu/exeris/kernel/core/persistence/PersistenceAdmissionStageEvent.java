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
import jdk.jfr.FlightRecorder;
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
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        PersistenceAdmissionStageEvent event = new PersistenceAdmissionStageEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerId = payload.providerId();
        event.stage = payload.stage();
        event.queueDepth = payload.queueDepth();
        event.queueWaitP95Ms = payload.queueWaitP95Ms();
        event.accepted = payload.accepted();
        event.decisionReason = payload.decisionReason();
        event.commit();
    }

    public record Payload(String providerId,
                          String stage,
                          int queueDepth,
                          long queueWaitP95Ms,
                          boolean accepted,
                          String decisionReason) {
    }
}