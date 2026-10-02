/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit: {@link TelemetryConfig} factory method contracts.
 *
 * @since 0.5.0
 */
@DisplayName("TelemetryConfig — factory method contract")
class TelemetryConfigTest {

    @Test
    @DisplayName("communityProduction(null) — throws IllegalArgumentException")
    void communityProduction_rejectsNullPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TelemetryConfig.communityProduction(null))
                .withMessage("logPath must not be null or blank for community production configuration");
    }

    @ParameterizedTest(name = "communityProduction(\"{0}\") — throws IllegalArgumentException")
    @ValueSource(strings = {"", " ", "\t", "\n"})
    @DisplayName("communityProduction(blank) — throws IllegalArgumentException")
    void communityProduction_rejectsBlankPath(String blank) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TelemetryConfig.communityProduction(blank))
                .withMessage("logPath must not be null or blank for community production configuration");
    }

    @Test
    @DisplayName("communityProduction(validPath) — console disabled, JFR enabled, path set")
    void communityProduction_validPath() {
        TelemetryConfig cfg = TelemetryConfig.communityProduction("/var/log/exeris/kernel.log");

        assertThat(cfg.consoleSinkEnabled()).isFalse();
        assertThat(cfg.jfrSinkEnabled()).isTrue();
        assertThat(cfg.fileSinkPath()).isEqualTo("/var/log/exeris/kernel.log");
    }

    @Test
    @DisplayName("defaults() — console enabled, JFR disabled, no file sink")
    void defaults_consoleSinkOnly() {
        TelemetryConfig cfg = TelemetryConfig.defaults();

        assertThat(cfg.consoleSinkEnabled()).isTrue();
        assertThat(cfg.jfrSinkEnabled()).isFalse();
        assertThat(cfg.fileSinkPath()).isNull();
    }
}

