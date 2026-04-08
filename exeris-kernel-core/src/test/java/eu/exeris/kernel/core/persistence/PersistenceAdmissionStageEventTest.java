/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L1 Unit: PersistenceAdmissionStageEvent payload contract")
class PersistenceAdmissionStageEventTest {

    @Test
    @DisplayName("Payload captures stage metadata and deterministic decision reason")
    void payloadCapturesStageMetadataAndDecisionReason() {
        PersistenceAdmissionStageEvent.Payload payload = new PersistenceAdmissionStageEvent.Payload(
                "postgres-community",
                "queue_wait",
                3,
                25L,
                false,
                "REJECT_GUARD_BAND_FAIRNESS"
        );

        assertThat(payload.providerId()).isEqualTo("postgres-community");
        assertThat(payload.stage()).isEqualTo("queue_wait");
        assertThat(payload.queueDepth()).isEqualTo(3);
        assertThat(payload.queueWaitP95Ms()).isEqualTo(25L);
        assertThat(payload.accepted()).isFalse();
        assertThat(payload.decisionReason()).isEqualTo("REJECT_GUARD_BAND_FAIRNESS");
    }
}