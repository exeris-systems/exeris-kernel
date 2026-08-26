/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.metrics;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.StructuredTaskScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PrometheusMetricsSink")
class PrometheusMetricsSinkTest {

    @Test
    @DisplayName("Counter exposition: HELP + TYPE + value")
    void counterExposesValue() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.increment("kernel_bootstrap_total", 1L);
        sink.increment("kernel_bootstrap_total", 2L);

        String exposition = sink.exposeText();

        assertThat(exposition)
                .contains("# TYPE kernel_bootstrap_total counter")
                .contains("kernel_bootstrap_total 3");
    }

    @Test
    @DisplayName("Gauge exposition: last writer wins")
    void gaugeReflectsLastWrite() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.gauge("kernel_heap_bytes", 1024L);
        sink.gauge("kernel_heap_bytes", 4096L);

        String exposition = sink.exposeText();

        assertThat(exposition)
                .contains("# TYPE kernel_heap_bytes gauge")
                .contains("kernel_heap_bytes 4096")
                .doesNotContain("kernel_heap_bytes 1024");
    }

    @Test
    @DisplayName("Latency exposition: summary with _count and _sum (no quantiles in baseline)")
    void latencyExposesSummary() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.latency("flush_nanos", 1_000L);
        sink.latency("flush_nanos", 2_000L);
        sink.latency("flush_nanos", 3_000L);

        String exposition = sink.exposeText();

        assertThat(exposition)
                .contains("# TYPE flush_nanos summary")
                .contains("flush_nanos_count 3")
                .contains("flush_nanos_sum 6000");
    }

    @Test
    @DisplayName("Output is sorted lexicographically within each metric type")
    void outputIsDeterministic() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.increment("zeta", 1L);
        sink.increment("alpha", 1L);
        sink.gauge("delta", 7L);
        sink.gauge("beta", 3L);

        String exposition = sink.exposeText();
        int alphaIndex = exposition.indexOf("\nalpha ");
        int zetaIndex = exposition.indexOf("\nzeta ");
        int betaIndex = exposition.indexOf("\nbeta ");
        int deltaIndex = exposition.indexOf("\ndelta ");

        assertThat(alphaIndex).as("alpha sample line is present").isPositive();
        assertThat(zetaIndex).isPositive();
        assertThat(betaIndex).isPositive();
        assertThat(deltaIndex).isPositive();
        assertThat(alphaIndex).as("alpha < zeta within counters").isLessThan(zetaIndex);
        assertThat(betaIndex).as("beta < delta within gauges").isLessThan(deltaIndex);
    }

    @Test
    @DisplayName("Names with invalid characters are sanitized to the Prometheus regex")
    void invalidNameCharactersAreSanitized() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.increment("kernel.bootstrap.total", 5L);
        sink.gauge("9starts-with-digit", 1L);
        sink.gauge("with space", 2L);

        String exposition = sink.exposeText();

        assertThat(exposition)
                .contains("kernel_bootstrap_total 5")
                .contains("_9starts_with_digit 1")
                .contains("with_space 2");
    }

    @Test
    @DisplayName("emit(KernelEvent) is intentionally a no-op")
    void emitDoesNotInterfereWithMetrics() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.emit(KernelEvent.info("EX-METRIC-001", "PromTest"));
        sink.increment("metric", 1L);

        String exposition = sink.exposeText();
        assertThat(exposition)
                .contains("metric 1")
                .doesNotContain("EX-METRIC-001");
    }

    @Test
    @DisplayName("Concurrent updates produce a consistent total")
    void concurrentIncrementsAccumulateCorrectly() throws InterruptedException {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        int threads = 16;
        int perThread = 1_000;

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
            for (int t = 0; t < threads; t++) {
                scope.fork(() -> {
                    for (int j = 0; j < perThread; j++) {
                        sink.increment("hot", 1L);
                    }
                    return null;
                });
            }
            scope.join();
        }

        assertThat(sink.exposeText()).contains("hot " + (long) threads * perThread);
    }

    @Test
    @DisplayName("close() clears recorded state")
    void closeClearsState() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.increment("a", 1L);
        sink.gauge("b", 2L);
        sink.latency("c", 3L);

        sink.close();

        assertThat(sink.exposeText()).isEmpty();
    }

    @Test
    @DisplayName("Null metric name is rejected")
    void nullNameRejected() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        assertThatThrownBy(() -> sink.increment(null, 1L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sink.gauge(null, 1L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sink.latency(null, 1L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Sink name is canonical for diagnostics")
    void sinkNameIsCanonical() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        assertThat(sink.sinkName()).isEqualTo("ExerisCommunity/PrometheusMetricsSink");
    }
}
