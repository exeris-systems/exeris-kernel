/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link TelemetryProvider} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code providerName()} returns a non-null, non-blank display name</li>
 *   <li>{@code priority()} follows the Open-Core convention (0 or 100)</li>
 *   <li>{@code createSinks(TelemetryConfig)} returns a non-null, non-empty immutable list</li>
 *   <li>All returned sinks accept {@link KernelEvent#info} without throwing</li>
 *   <li>Each sink has a non-blank {@code sinkName()}</li>
 *   <li>ServiceLoader discovery selects the highest-priority provider</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityTelemetryProviderTckTest extends AbstractTelemetryProviderTck {
 *     @Override protected TelemetryProvider createProvider() {
 *         return new CommunityTelemetryProvider();
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractTelemetryProviderTck {

    /**
     * Creates the {@link TelemetryProvider} under test.
     */
    protected abstract TelemetryProvider createProvider();

    /**
     * Override to supply a custom config. Default: {@link TelemetryConfig#defaults()}.
     */
    protected TelemetryConfig createConfig() {
        return TelemetryConfig.defaults();
    }

    /**
     * Opt-in assertion hook: provider should expose the standard JFR sink
     * when JFR is explicitly enabled.
     */
    protected boolean expectStandardJfrSinkWhenEnabled() {
        return false;
    }

    /**
     * Opt-in assertion hook: provider should expose SLF4J fallback sink when
     * JFR is disabled.
     */
    protected boolean expectSlf4jFallbackWhenJfrDisabled() {
        return false;
    }

    private TelemetryProvider provider;

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    // =========================================================================
    // Provider identity
    // =========================================================================

    @Nested
    @DisplayName("Provider identity contract")
    class IdentityContract {

        @Test
        @DisplayName("providerName() is non-null and non-blank")
        void providerNameIsNonBlank() {
            assertThat(provider.providerName())
                    .as("providerName must be a stable display name")
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("priority() follows Open-Core convention (0 or 100)")
        void priorityConvention() {
            assertThat(provider.priority()).isIn(0, 100);
        }
    }

    // =========================================================================
    // createSinks()
    // =========================================================================

    @Nested
    @DisplayName("createSinks() contract")
    class CreateSinksContract {

        @Test
        @DisplayName("returns a non-null, non-empty list of sinks")
        void returnsNonEmptyList() {
            List<TelemetrySink> sinks = provider.createSinks(createConfig());
            assertThat(sinks)
                    .as("createSinks must return at least one active sink")
                    .isNotNull()
                    .isNotEmpty();
            sinks.forEach(TelemetrySink::close);
        }

        @Test
        @DisplayName("each sink has a non-blank sinkName()")
        void sinkNamesNonBlank() {
            List<TelemetrySink> sinks = provider.createSinks(createConfig());
            try {
                for (TelemetrySink sink : sinks) {
                    assertThat(sink.sinkName())
                            .as("Every TelemetrySink must have a non-blank sinkName()")
                            .isNotNull()
                            .isNotBlank();
                }
            } finally {
                sinks.forEach(TelemetrySink::close);
            }
        }

        @Test
        @DisplayName("all sinks accept KernelEvent.info() without throwing")
        void sinksAcceptInfoEvent() {
            List<TelemetrySink> sinks = provider.createSinks(createConfig());
            KernelEvent event = KernelEvent.info("EX-TCK-PROV-001", "TelemetryTCK");
            try {
                for (TelemetrySink sink : sinks) {
                    assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
                }
            } finally {
                sinks.forEach(TelemetrySink::close);
            }
        }

        @Test
        @DisplayName("all sinks accept KernelEvent.error() with exception")
        void sinksAcceptErrorEvent() {
            List<TelemetrySink> sinks = provider.createSinks(createConfig());
            KernelEvent event = KernelEvent.error("EX-TCK-PROV-002", "TelemetryTCK",
                    new MemoryExhaustedException(1024L, 0L, null));
            try {
                for (TelemetrySink sink : sinks) {
                    assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
                }
            } finally {
                sinks.forEach(TelemetrySink::close);
            }
        }

        @Test
        @DisplayName("all sinks accept increment(), gauge(), latency() metrics")
        void sinksAcceptMetrics() {
            List<TelemetrySink> sinks = provider.createSinks(createConfig());
            try {
                for (TelemetrySink sink : sinks) {
                    assertThatCode(() -> sink.increment("tck.counter", 1)).doesNotThrowAnyException();
                    assertThatCode(() -> sink.gauge("tck.gauge", 42)).doesNotThrowAnyException();
                    assertThatCode(() -> sink.latency("tck.latency", 1_000_000L)).doesNotThrowAnyException();
                }
            } finally {
                sinks.forEach(TelemetrySink::close);
            }
        }

        @Test
        @DisplayName("uses standard JFR sink when enabled (opt-in)")
        void usesStandardJfrSinkWhenEnabled() {
            if (!expectStandardJfrSinkWhenEnabled()) {
                return;
            }

            TelemetryConfig base = createConfig();
            TelemetryConfig jfrEnabledConfig = new TelemetryConfig(
                    false,
                    true,
                    null,
                    base.glassBoxOffHeapBytes(),
                    base.maxEventQueueDepth());

            List<TelemetrySink> sinks = provider.createSinks(jfrEnabledConfig);
            try {
                assertThat(sinks.stream()
                        .map(TelemetrySink::sinkName)
                        .anyMatch(name -> name.contains("JfrTelemetrySink")))
                        .isTrue();
            } finally {
                sinks.forEach(TelemetrySink::close);
            }
        }

        @Test
        @DisplayName("uses SLF4J fallback when JFR disabled (opt-in)")
        void usesSlf4jFallbackWhenJfrDisabled() {
            if (!expectSlf4jFallbackWhenJfrDisabled()) {
                return;
            }

            TelemetryConfig base = createConfig();
            TelemetryConfig jfrDisabledConfig = new TelemetryConfig(
                    true,
                    false,
                    null,
                    base.glassBoxOffHeapBytes(),
                    base.maxEventQueueDepth());

            List<TelemetrySink> sinks = provider.createSinks(jfrDisabledConfig);
            try {
                assertThat(sinks.stream()
                        .map(TelemetrySink::sinkName)
                        .anyMatch(name -> name.contains("Slf4jTelemetrySink")))
                        .isTrue();
            } finally {
                sinks.forEach(TelemetrySink::close);
            }
        }
    }

    // =========================================================================
    // Event routing — all sinks receive the same event
    // =========================================================================

    @Nested
    @DisplayName("Event routing — broadcast to all sinks")
    class EventRouting {

        @Test
        @DisplayName("emit() on all sinks does not throw for FATAL level")
        void fatalBroadcast() {
            List<TelemetrySink> sinks = provider.createSinks(createConfig());
            KernelEvent event = KernelEvent.fatal("EX-TCK-FATAL", "TelemetryTCK",
                    new MemoryExhaustedException(0L, 0L, new OutOfMemoryError()));
            try {
                for (TelemetrySink sink : sinks) {
                    assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
                }
            } finally {
                sinks.forEach(TelemetrySink::close);
            }
        }
    }

    // =========================================================================
    // ServiceLoader integration
    // =========================================================================

    @Nested
    @DisplayName("ServiceLoader integration")
    class ServiceLoaderIntegration {

        @Test
        @DisplayName("TelemetryProvider is discoverable via ServiceLoader")
        void discoverableViaServiceLoader() {
            ServiceLoader<TelemetryProvider> loader = ServiceLoader.load(TelemetryProvider.class);
            assertThat(loader.stream().count())
                    .as("At least one TelemetryProvider must be on the classpath")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Highest-priority provider wins selection")
        void highestPriorityWins() {
            TelemetryProvider selected = ServiceLoader.load(TelemetryProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .max(Comparator.comparingInt(TelemetryProvider::priority))
                    .orElseThrow();
            assertThat(selected.priority())
                    .isGreaterThanOrEqualTo(provider.priority());
        }
    }
}
