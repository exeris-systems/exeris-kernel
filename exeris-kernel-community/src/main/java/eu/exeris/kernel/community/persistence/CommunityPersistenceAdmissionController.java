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
    private static final long QUEUE_WAIT_TELEMETRY_THRESHOLD_MS = 0L;

    private final FairnessTracker fairnessTracker = new FairnessTracker();

    /* default */ boolean canServiceRequest(CommunityHikariSupport.AdmissionSnapshot snapshot,
                                            boolean closed,
                                            CommunityAdmissionConfig config) {
        int active = snapshot.activeConnections();
        int queued = snapshot.pendingAcquires();
        int idle = snapshot.idleConnections();
        int max = snapshot.maxConnections();
        String decisionReason = evaluateAdmissionReason(active, queued, idle, max, closed, config);
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

    /* default */ String decisionReason(CommunityHikariSupport.AdmissionSnapshot snapshot,
                                        boolean closed,
                                        CommunityAdmissionConfig config) {
        return evaluateAdmissionReason(
                snapshot.activeConnections(),
                snapshot.pendingAcquires(),
                snapshot.idleConnections(),
                snapshot.maxConnections(),
                closed,
                config);
    }

    // ADR-035: a full or saturated pool no longer sheds on the first queued acquire. It admits
    // (deferring to the connection-acquire timeout) until pending acquires exceed a pool-size-scaled
    // allowance, then sheds — preserving backpressure under a genuinely deep queue while restoring
    // availability for small pools whose queue drains within the No-Waste-Compute latency bound.
    private String evaluateAdmissionReason(int active, int queued, int idle, int max, boolean closed,
                                           CommunityAdmissionConfig config) {
        if (closed) {
            return ADMISSION_REJECT_ENGINE_CLOSED;
        }
        if (max <= 0) {
            return ADMISSION_REJECT_NO_CAPACITY;
        }
        boolean queueExceedsAllowance = queued > config.queueDepthAllowance(max);
        double saturation = (double) active / (double) max;
        if (saturation >= config.hardSaturationThreshold() && queueExceedsAllowance) {
            return ADMISSION_REJECT_HARD_SATURATION;
        }
        if (saturation >= config.guardBandThreshold()
                && queueExceedsAllowance
                && (shouldRejectEarlyInGuardBand(active, queued, max, config)
                || fairnessTracker.indicatesAdmissionStress(
                        config.fairnessStressThreshold(),
                        config.fairnessQueueDepthThreshold()))) {
            return ADMISSION_REJECT_GUARD_BAND_FAIRNESS;
        }
        if (idle <= 0 && queueExceedsAllowance) {
            return ADMISSION_REJECT_NO_CAPACITY;
        }
        return ADMISSION_ACCEPT;
    }

    private boolean shouldRejectEarlyInGuardBand(int active, int queued, int max,
                                                 CommunityAdmissionConfig config) {
        if (queued <= 0 || max <= 0) {
            return false;
        }
        int remainingHeadroom = Math.max(0, max - active);
        if (remainingHeadroom <= 0) {
            return false;
        }
        int lowHeadroomThreshold = Math.clamp(
                (int) Math.ceil(max * config.earlyGuardBandHeadroomRatio()),
                1,
                config.earlyGuardBandHeadroomCap());
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
