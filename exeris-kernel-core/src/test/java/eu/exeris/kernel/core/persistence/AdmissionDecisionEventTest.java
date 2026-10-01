/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L1 Unit: AdmissionDecisionEvent payload contract")
class AdmissionDecisionEventTest {

    @Test
    @DisplayName("Payload captures queue metrics and decision reason")
    void payloadCapturesQueueMetricsAndDecisionReason() {
        AdmissionDecisionEvent.Payload payload = new AdmissionDecisionEvent.Payload(
                "postgres-community",
                false,
                3,
                0.91d,
                0.72d,
                7L,
                20L,
                "REJECT_HARD_SATURATION"
        );

        assertThat(payload.providerId()).isEqualTo("postgres-community");
        assertThat(payload.accepted()).isFalse();
        assertThat(payload.queueDepth()).isEqualTo(3);
        assertThat(payload.saturation()).isEqualTo(0.91d);
        assertThat(payload.fairnessRatio()).isEqualTo(0.72d);
        assertThat(payload.queueDepthP95()).isEqualTo(7L);
        assertThat(payload.queueWaitP95Ms()).isEqualTo(20L);
        assertThat(payload.decisionReason()).isEqualTo("REJECT_HARD_SATURATION");
    }
}
