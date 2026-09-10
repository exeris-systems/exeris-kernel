/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.community.config.CommunityConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityAdmissionConfig} — tunable admission thresholds (ADR-035).
 */
@DisplayName("L1 Unit: CommunityAdmissionConfig (ADR-035)")
class CommunityAdmissionConfigTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("exeris.persistence.admission.hardSaturationThreshold");
        System.clearProperty("exeris.persistence.admission.queueDepthAllowanceRatio");
        System.clearProperty("exeris.persistence.admission.fairnessQueueDepthThreshold");
    }

    @Nested
    @DisplayName("queueDepthAllowance scaling")
    class QueueDepthAllowance {

        @Test
        @DisplayName("DEFAULT (ratio 8.0) scales allowance with pool size")
        void defaultRatioScalesWithPool() {
            assertThat(CommunityAdmissionConfig.DEFAULT.queueDepthAllowance(2)).isEqualTo(16);
            assertThat(CommunityAdmissionConfig.DEFAULT.queueDepthAllowance(4)).isEqualTo(32);
            assertThat(CommunityAdmissionConfig.DEFAULT.queueDepthAllowance(256)).isEqualTo(2048);
        }

        @Test
        @DisplayName("ratio 0.0 yields a strict allowance of 0 (pre-035 behavior)")
        void zeroRatioIsStrict() {
            CommunityAdmissionConfig strict = new CommunityAdmissionConfig(
                    0.90d, 0.85d, 0.90d, 1L, 0.15d, 3, 0.0d);
            assertThat(strict.queueDepthAllowance(2)).isZero();
            assertThat(strict.queueDepthAllowance(256)).isZero();
        }

        @Test
        @DisplayName("non-positive pool size yields allowance 0")
        void nonPositivePoolIsZero() {
            assertThat(CommunityAdmissionConfig.DEFAULT.queueDepthAllowance(0)).isZero();
            assertThat(CommunityAdmissionConfig.DEFAULT.queueDepthAllowance(-1)).isZero();
        }
    }

    @Nested
    @DisplayName("fromConfigProvider resolution")
    class FromConfigProvider {

        @Test
        @DisplayName("returns DEFAULT when no admission keys are set")
        void defaultsWhenUnset() {
            CommunityAdmissionConfig resolved =
                    CommunityAdmissionConfig.fromConfigProvider(new CommunityConfigProvider());
            assertThat(resolved).isEqualTo(CommunityAdmissionConfig.DEFAULT);
        }

        @Test
        @DisplayName("overrides individual fields from config properties")
        void overridesFromProperties() {
            System.setProperty("exeris.persistence.admission.hardSaturationThreshold", "0.75");
            System.setProperty("exeris.persistence.admission.queueDepthAllowanceRatio", "2.5");
            System.setProperty("exeris.persistence.admission.fairnessQueueDepthThreshold", "4");

            CommunityAdmissionConfig resolved =
                    CommunityAdmissionConfig.fromConfigProvider(new CommunityConfigProvider());

            assertThat(resolved.hardSaturationThreshold()).isEqualTo(0.75d);
            assertThat(resolved.queueDepthAllowanceRatio()).isEqualTo(2.5d);
            assertThat(resolved.fairnessQueueDepthThreshold()).isEqualTo(4L);
            // unset fields keep their defaults
            assertThat(resolved.guardBandThreshold()).isEqualTo(CommunityAdmissionConfig.DEFAULT.guardBandThreshold());
        }

        @Test
        @DisplayName("malformed double falls back to the default for that field")
        void malformedDoubleFallsBack() {
            System.setProperty("exeris.persistence.admission.hardSaturationThreshold", "not-a-number");

            CommunityAdmissionConfig resolved =
                    CommunityAdmissionConfig.fromConfigProvider(new CommunityConfigProvider());

            assertThat(resolved.hardSaturationThreshold())
                    .isEqualTo(CommunityAdmissionConfig.DEFAULT.hardSaturationThreshold());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects saturation ratio outside (0, 1]")
        void rejectsBadSaturation() {
            assertThatThrownBy(() -> new CommunityAdmissionConfig(0.0d, 0.85d, 0.90d, 1L, 0.15d, 3, 8.0d))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CommunityAdmissionConfig(1.5d, 0.85d, 0.90d, 1L, 0.15d, 3, 8.0d))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects negative or non-finite queue allowance ratio")
        void rejectsBadAllowanceRatio() {
            assertThatThrownBy(() -> new CommunityAdmissionConfig(0.90d, 0.85d, 0.90d, 1L, 0.15d, 3, -1.0d))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CommunityAdmissionConfig(
                    0.90d, 0.85d, 0.90d, 1L, 0.15d, 3, Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("DEFAULT is the live CURRENT pointer at class init")
        void currentDefaultsToDefault() {
            assertThat(CommunityAdmissionConfig.CURRENT).isEqualTo(CommunityAdmissionConfig.DEFAULT);
        }
    }
}
