/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.persistence.AdmissionDecisionEvent;
import eu.exeris.kernel.core.persistence.PersistenceAdmissionStageEvent;

@SuppressWarnings("PMD.CyclomaticComplexity")
final class CommunityPersistenceAdmissionController {

    private static final String ADMISSION_ACCEPT = "ACCEPT";
    private static final String ADMISSION_REJECT_HARD_SATURATION = "REJECT_HARD_SATURATION";
    private static final String ADMISSION_REJECT_GUARD_BAND_FAIRNESS = "REJECT_GUARD_BAND_FAIRNESS";
    private static final String ADMISSION_REJECT_ENGINE_CLOSED = "REJECT_ENGINE_CLOSED";
    private static final String ADMISSION_REJECT_NO_CAPACITY = "REJECT_NO_CAPACITY";
    private static final double HARD_SATURATION_THRESHOLD = 0.90d;
    private static final double GUARD_BAND_THRESHOLD = 0.85d;
    private static final double FAIRNESS_STRESS_THRESHOLD = 0.90d;
    private static final long FAIRNESS_QUEUE_DEPTH_THRESHOLD = 1L;
    private static final double EARLY_GUARD_BAND_HEADROOM_RATIO = 0.15d;
    private static final int EARLY_GUARD_BAND_HEADROOM_CAP = 3;
    private static final long QUEUE_WAIT_TELEMETRY_THRESHOLD_MS = 0L;

    private final FairnessTracker fairnessTracker = new FairnessTracker();

    /* default */ boolean canServiceRequest(CommunityHikariSupport.AdmissionSnapshot snapshot, boolean closed) {
        int active = snapshot.activeConnections();
        int queued = snapshot.pendingAcquires();
        int idle = snapshot.idleConnections();
        int max = snapshot.maxConnections();
        String decisionReason = evaluateAdmissionReason(active, queued, idle, max, closed);
        boolean accepted = ADMISSION_ACCEPT.equals(decisionReason);
        double saturation = admissionSaturation(active, max, decisionReason);

        fairnessTracker.recordDecision(accepted, queued);

        if (admissionTelemetryEnabled()) {
            FairnessTracker.FairnessSnapshot fairnessSnapshot = fairnessTracker.computeSnapshot();
            emitAdmissionTelemetry(
                    accepted,
                    queued,
                    saturation,
                    fairnessSnapshot.fairnessRatio(),
                    fairnessSnapshot.queueDepthP95(),
                    fairnessSnapshot.queueWaitP95Ms(),
                    decisionReason);
        } else {
            emitAdmissionTelemetry(accepted, queued, saturation, 0.0d, 0L, 0L, decisionReason);
        }
        return accepted;
    }

    /* default */ String decisionReason(CommunityHikariSupport.AdmissionSnapshot snapshot, boolean closed) {
        return evaluateAdmissionReason(
                snapshot.activeConnections(),
                snapshot.pendingAcquires(),
                snapshot.idleConnections(),
                snapshot.maxConnections(),
                closed);
    }

    private String evaluateAdmissionReason(int active, int queued, int idle, int max, boolean closed) {
        if (closed) {
            return ADMISSION_REJECT_ENGINE_CLOSED;
        }
        if (max <= 0) {
            return ADMISSION_REJECT_NO_CAPACITY;
        }
        double saturation = (double) active / (double) max;
        if (saturation >= HARD_SATURATION_THRESHOLD) {
            return ADMISSION_REJECT_HARD_SATURATION;
        }
        if (saturation >= GUARD_BAND_THRESHOLD
                && queued > 0
                && (shouldRejectEarlyInGuardBand(active, queued, max)
                || fairnessTracker.indicatesAdmissionStress(
                        FAIRNESS_STRESS_THRESHOLD,
                        FAIRNESS_QUEUE_DEPTH_THRESHOLD))) {
            return ADMISSION_REJECT_GUARD_BAND_FAIRNESS;
        }
        if (idle <= 0 && queued > 0) {
            return ADMISSION_REJECT_NO_CAPACITY;
        }
        return ADMISSION_ACCEPT;
    }

    private boolean shouldRejectEarlyInGuardBand(int active, int queued, int max) {
        if (queued <= 0 || max <= 0) {
            return false;
        }
        int remainingHeadroom = Math.max(0, max - active);
        if (remainingHeadroom <= 0) {
            return false;
        }
        int lowHeadroomThreshold = Math.clamp(
                (int) Math.ceil(max * EARLY_GUARD_BAND_HEADROOM_RATIO),
                1,
                EARLY_GUARD_BAND_HEADROOM_CAP);
        return remainingHeadroom <= lowHeadroomThreshold && queued >= remainingHeadroom;
    }

    private double admissionSaturation(int active, int max, String decisionReason) {
        if (ADMISSION_REJECT_ENGINE_CLOSED.equals(decisionReason) || max <= 0) {
            return 1.0d;
        }
        return (double) active / (double) max;
    }

    private boolean admissionTelemetryEnabled() {
        return AdmissionDecisionEvent.isEnabled() || PersistenceAdmissionStageEvent.isEnabled();
    }

    private static void emitAdmissionTelemetry(boolean accepted,
                                               int queueDepth,
                                               double saturation,
                                               double fairnessRatio,
                                               long queueDepthP95,
                                               long queueWaitP95Ms,
                                               String decisionReason) {
        if (queueDepth > 0 && PersistenceAdmissionStageEvent.isEnabled()) {
            PersistenceAdmissionStageEvent.emit(new PersistenceAdmissionStageEvent.Payload(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    "queue_enter",
                    queueDepth,
                    0L,
                    accepted,
                    decisionReason));
        }
        if (queueWaitP95Ms > QUEUE_WAIT_TELEMETRY_THRESHOLD_MS
                && PersistenceAdmissionStageEvent.isEnabled()) {
            PersistenceAdmissionStageEvent.emit(new PersistenceAdmissionStageEvent.Payload(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    "queue_wait",
                    queueDepth,
                    queueWaitP95Ms,
                    accepted,
                    decisionReason));
        }
        if (PersistenceAdmissionStageEvent.isEnabled()) {
            PersistenceAdmissionStageEvent.emit(new PersistenceAdmissionStageEvent.Payload(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    "persistence_admission",
                    queueDepth,
                    queueWaitP95Ms,
                    accepted,
                    decisionReason));
        }

        if (AdmissionDecisionEvent.isEnabled()) {
            AdmissionDecisionEvent.emit(new AdmissionDecisionEvent.Payload(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    accepted,
                    queueDepth,
                    saturation,
                    fairnessRatio,
                    queueDepthP95,
                    queueWaitP95Ms,
                    decisionReason));
        }
    }
}
