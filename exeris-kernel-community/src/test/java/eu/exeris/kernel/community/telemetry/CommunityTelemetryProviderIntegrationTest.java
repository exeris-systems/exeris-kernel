/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * L2 Integration: {@link CommunityTelemetryProvider} routing — verifies that
 * provider creates correct sinks from config and routes events without throwing.
 *
 * @since 0.5.0
 */
@DisplayName("L2: CommunityTelemetryProvider routing")
class CommunityTelemetryProviderIntegrationTest {

    @Test
    @DisplayName("providerName() contains 'Community'")
    void providerName() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        assertThat(provider.providerName()).containsIgnoringCase("Community");
    }

    @Test
    @DisplayName("priority() returns 0 (Community tier)")
    void priority() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        assertThat(provider.priority()).isZero();
    }

    @Test
    @DisplayName("createSinks(defaults) returns non-empty list")
    void createSinksDefaults() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        List<TelemetrySink> sinks = provider.createSinks(TelemetryConfig.defaults());
        assertThat(sinks).isNotEmpty();
        sinks.forEach(TelemetrySink::close);
    }

    @Test
    @DisplayName("JFR-enabled config uses standard Community JfrTelemetrySink")
    void jfrEnabledUsesCommunityJfrTelemetrySink() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        TelemetryConfig config = new TelemetryConfig(false, true, null, 0L, 128);

        List<TelemetrySink> sinks = provider.createSinks(config);

        assertThat(sinks.stream().map(TelemetrySink::sinkName))
            .anyMatch(name -> name.contains("ExerisCommunity/JfrTelemetrySink"));
        assertThat(sinks.stream().map(TelemetrySink::sinkName))
                .noneMatch(name -> name.contains("ExerisCommunity/JfrSink"));
        sinks.forEach(TelemetrySink::close);
    }

    @Test
    @DisplayName("JFR-disabled config includes SLF4J fallback sink")
    void jfrDisabledIncludesSlf4jFallback() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        TelemetryConfig config = new TelemetryConfig(true, false, null, 0L, 128);

        List<TelemetrySink> sinks = provider.createSinks(config);

        assertThat(sinks.stream().map(TelemetrySink::sinkName))
                .anyMatch(name -> name.contains("ExerisCommunity/Slf4jTelemetrySink"));
        sinks.forEach(TelemetrySink::close);
    }

    @Test
    @DisplayName("All sinks accept INFO event without throwing")
    void allSinksAcceptInfo() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        List<TelemetrySink> sinks = provider.createSinks(TelemetryConfig.defaults());
        KernelEvent event = KernelEvent.info("EX-INTEG-001", "Integration");

        sinks.forEach(sink -> assertThatCode(() -> sink.emit(event))
                .as("Sink %s must not throw on emit()", sink.getClass().getSimpleName())
                .doesNotThrowAnyException());

        sinks.forEach(TelemetrySink::close);
    }

    @Test
    @DisplayName("All sinks accept ERROR event without throwing")
    void allSinksAcceptError() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        List<TelemetrySink> sinks = provider.createSinks(TelemetryConfig.defaults());
        KernelEvent event = KernelEvent.error("EX-INTEG-002", "Integration",
                new MemoryExhaustedException(4096L, 0L, null));

        sinks.forEach(sink -> assertThatCode(() -> sink.emit(event))
                .doesNotThrowAnyException());

        sinks.forEach(TelemetrySink::close);
    }

    @Test
    @DisplayName("Sinks are closed without error after use")
    void sinksCloseCleanly() {
        TelemetryProvider provider = new CommunityTelemetryProvider();
        List<TelemetrySink> sinks = provider.createSinks(TelemetryConfig.defaults());

        sinks.forEach(sink -> sink.emit(KernelEvent.info("EX-CLOSE", "Integration")));

        assertThatCode(() -> sinks.forEach(TelemetrySink::close))
                .doesNotThrowAnyException();
    }
}

