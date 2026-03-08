/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link ScalingContext} — SLA threshold validation and action mapping.
 *
 * @since 0.5.0
 */
@DisplayName("L1: ScalingContext")
class ScalingContextTest {

    @Nested
    @DisplayName("Predefined profiles")
    class PredefinedProfiles {

        @Test
        @DisplayName("premium() — tierName == 'premium', throttle 0.75, reject 0.88, shed 0.97")
        void premiumProfile() {
            ScalingContext ctx = ScalingContext.premium();
            assertThat(ctx.tierName()).isEqualTo("premium");
            assertThat(ctx.throttleUtilization()).isEqualTo(0.75);
            assertThat(ctx.rejectUtilization()).isEqualTo(0.88);
            assertThat(ctx.shedUtilization()).isEqualTo(0.97);
        }

        @Test
        @DisplayName("standard() — thresholds match WatermarkLevel defaults")
        void standardProfile() {
            ScalingContext ctx = ScalingContext.standard();
            assertThat(ctx.tierName()).isEqualTo("standard");
            assertThat(ctx.throttleUtilization()).isEqualTo(0.70);
            assertThat(ctx.rejectUtilization()).isEqualTo(0.85);
            assertThat(ctx.shedUtilization()).isEqualTo(0.95);
            assertThat(ctx.maxQueueDepth()).isEqualTo(5_000);
        }

        @Test
        @DisplayName("free() — shed first: throttle 0.60, reject 0.75, shed 0.85")
        void freeProfile() {
            ScalingContext ctx = ScalingContext.free();
            assertThat(ctx.tierName()).isEqualTo("free");
            assertThat(ctx.throttleUtilization()).isEqualTo(0.60);
            assertThat(ctx.rejectUtilization()).isEqualTo(0.75);
            assertThat(ctx.shedUtilization()).isEqualTo(0.85);
        }
    }

    @Nested
    @DisplayName("Construction validation — threshold ordering")
    class ConstructionValidation {

        @Test
        @DisplayName("null tierName throws NPE")
        void nullTierNameThrows() {
            assertThatThrownBy(() -> new ScalingContext(null, 0.7, 0.85, 0.95, 5000, 250))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank tierName throws IllegalArgumentException")
        void blankTierNameThrows() {
            assertThatThrownBy(() -> new ScalingContext("  ", 0.7, 0.85, 0.95, 5000, 250))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejectUtilization <= throttleUtilization throws IllegalArgumentException")
        void rejectNotGreaterThanThrottleThrows() {
            assertThatThrownBy(() -> new ScalingContext("t", 0.85, 0.85, 0.95, 5000, 250))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("shedUtilization <= rejectUtilization throws IllegalArgumentException")
        void shedNotGreaterThanRejectThrows() {
            assertThatThrownBy(() -> new ScalingContext("t", 0.70, 0.85, 0.85, 5000, 250))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("maxQueueDepth <= 0 throws IllegalArgumentException")
        void nonPositiveQueueDepthThrows() {
            assertThatThrownBy(() -> new ScalingContext("t", 0.70, 0.85, 0.95, 0, 250))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("maxRttMs <= 0 throws IllegalArgumentException")
        void nonPositiveRttThrows() {
            assertThatThrownBy(() -> new ScalingContext("t", 0.70, 0.85, 0.95, 5000, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("actionFor() — O(1) threshold mapping")
    class ActionFor {

        @Test
        @DisplayName("0.0 utilization → ALLOW")
        void zeroIsAllow() {
            assertThat(ScalingContext.standard().actionFor(0.0))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("below throttle → ALLOW")
        void belowThrottleIsAllow() {
            assertThat(ScalingContext.standard().actionFor(0.69))
                    .isEqualTo(ResourceArbiter.Action.ALLOW);
        }

        @Test
        @DisplayName("at throttle threshold → THROTTLE")
        void atThrottleIsThrottle() {
            assertThat(ScalingContext.standard().actionFor(0.70))
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);
        }

        @Test
        @DisplayName("at reject threshold → REJECT")
        void atRejectIsReject() {
            assertThat(ScalingContext.standard().actionFor(0.85))
                    .isEqualTo(ResourceArbiter.Action.REJECT);
        }

        @Test
        @DisplayName("at shed threshold → SHED_LOAD")
        void atShedIsShedLoad() {
            assertThat(ScalingContext.standard().actionFor(0.95))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
        }

        @Test
        @DisplayName("free tier sheds at 0.85 while premium still ALLOWs")
        void freeShedsBeforePremium() {
            double utilization = 0.86;
            assertThat(ScalingContext.free().actionFor(utilization))
                    .isEqualTo(ResourceArbiter.Action.SHED_LOAD);
            assertThat(ScalingContext.premium().actionFor(utilization))
                    .isEqualTo(ResourceArbiter.Action.THROTTLE);
        }
    }

    @Nested
    @DisplayName("isQueueSaturated()")
    class QueueSaturation {

        @Test
        @DisplayName("depth <= maxQueueDepth → not saturated")
        void belowLimitNotSaturated() {
            assertThat(ScalingContext.standard().isQueueSaturated(5_000)).isFalse();
        }

        @Test
        @DisplayName("depth > maxQueueDepth → saturated")
        void aboveLimitIsSaturated() {
            assertThat(ScalingContext.standard().isQueueSaturated(5_001)).isTrue();
        }
    }
}
