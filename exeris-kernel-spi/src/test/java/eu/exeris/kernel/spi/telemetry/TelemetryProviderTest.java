/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
/**
 * Unit: {@link TelemetryProvider} default contract.
 *
 * @since 0.5.0
 */
@DisplayName("TelemetryProvider — default contract")
class TelemetryProviderTest {
    @Test
    @DisplayName("default priority() == 0 (Community baseline)")
    void defaultPriorityIsZero() {
        TelemetryProvider provider = new TelemetryProvider() {
            @Override
            public List<TelemetrySink> createSinks(TelemetryConfig config) {
                return List.of();
            }
            @Override
            public String providerName() {
                return "TestProvider";
            }
        };
        assertThat(provider.priority()).isZero();
    }
}