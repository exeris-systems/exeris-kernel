/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.metrics;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Community {@link TelemetrySink} that records counters, gauges and latency samples
 * in concurrent maps keyed by metric name, and emits the Prometheus 0.0.4 text
 * exposition format on demand via {@link #exposeText()}.
 *
 * <h2>Why pull, not push</h2>
 * <p>This sink and {@link PrometheusMetricsHandler} implement the Prometheus pull
 * model exclusively: operators wire both into their HTTP server engine and
 * Prometheus scrapes {@code /metrics}, with no client-side connection state,
 * retry logic, or push-protocol dependency for this sink to manage. Community
 * ships no push-based metrics exporter and no distributed-tracing exporter.
 *
 * <h2>Metric mapping</h2>
 * <table border="1">
 *   <caption>SPI metric → Prometheus type</caption>
 *   <tr><th>{@link TelemetrySink} call</th><th>Prometheus type</th></tr>
 *   <tr><td>{@link #increment(String, long)}</td><td>{@code counter}</td></tr>
 *   <tr><td>{@link #gauge(String, long)}</td><td>{@code gauge}</td></tr>
 *   <tr><td>{@link #latency(String, long)}</td>
 *       <td>{@code summary} ({@code _count} + {@code _sum} only — no quantiles in the baseline)</td></tr>
 * </table>
 *
 * <h2>Naming</h2>
 * <p>Metric names supplied by callers are mapped to the Prometheus name regex
 * {@code [a-zA-Z_:][a-zA-Z0-9_:]*}: any character outside that set is replaced
 * with {@code '_'} and a leading digit is prefixed with {@code '_'}. The original
 * name remains the lookup key in the concurrent maps so multiple {@code increment}
 * calls accumulate as expected.
 *
 * <h2>{@link #emit(KernelEvent)}</h2>
 * <p>This sink is metric-only — events are intentionally ignored. Pair it with
 * {@code JfrTelemetrySink} or {@code Slf4jTelemetrySink} when both signals are
 * needed.
 *
 * <h2>Thread safety</h2>
 * <p>All public methods are safe to call from any number of producer threads.
 * {@link #exposeText()} produces a snapshot using sequential reads of the
 * concurrent state — counters/gauges may shift between read and write of
 * different metrics within a single export, which is the standard Prometheus
 * exposition contract.
 *
 * @since 0.7
 */
@SuppressWarnings({
    "PMD.TooManyMethods",                  // sink contract + 3 metric appenders + sanitize + ctors
    "PMD.LooseCoupling",                   // ConcurrentHashMap chosen for atomic computeIfAbsent semantics
    "PMD.ConsecutiveAppendsShouldReuse",   // sequence is more readable than chained .append()
    "PMD.ConsecutiveLiteralAppends",       // same — readability over micro-perf in exposition path
    "PMD.LawOfDemeter"                     // LatencyAggregate is a private member; depth-2 access is fine
})
public final class PrometheusMetricsSink implements TelemetrySink {

    /** Prometheus text exposition format version 0.0.4 — content type for HTTP responses. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final String SINK_NAME = "ExerisCommunity/PrometheusMetricsSink";

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LatencyAggregate> latencies = new ConcurrentHashMap<>();

    /**
     * Built by the application wiring Prometheus metrics collection — typically paired with a
     * {@link PrometheusMetricsHandler} that serves {@link #exposeText()} over {@code GET /metrics}
     * — or directly by tests exercising the sink in isolation.
     */
    public PrometheusMetricsSink() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * No-op — this sink is metric-only. Pair it with {@code JfrTelemetrySink} or
     * {@code Slf4jTelemetrySink} to also surface events.
     */
    @Override
    public void emit(KernelEvent event) {
        // Metrics-only sink. Events are surfaced by paired event sinks (JFR/SLF4J).
    }

    /**
     * Adds {@code delta} to the named counter, creating it at zero on first use.
     *
     * @param name  metric name; never {@code null}
     * @param delta increment value (usually {@code 1})
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Override
    public void increment(String name, long delta) {
        Objects.requireNonNull(name, "name");
        counters.computeIfAbsent(name, _ -> new LongAdder()).add(delta);
    }

    /**
     * Sets the named gauge to {@code value}, creating it on first use.
     *
     * @param name  metric name; never {@code null}
     * @param value new gauge value
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Override
    public void gauge(String name, long value) {
        Objects.requireNonNull(name, "name");
        gauges.computeIfAbsent(name, _ -> new AtomicLong()).set(value);
    }

    /**
     * Records one latency sample into the named summary's {@code _count}/{@code _sum}
     * aggregate, creating it on first use.
     *
     * @param name        metric name; never {@code null}
     * @param nanoseconds latency sample
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Override
    public void latency(String name, long nanoseconds) {
        Objects.requireNonNull(name, "name");
        latencies.computeIfAbsent(name, _ -> new LatencyAggregate()).record(nanoseconds);
    }

    /**
     * Returns {@code "ExerisCommunity/PrometheusMetricsSink"}.
     */
    @Override
    public String sinkName() {
        return SINK_NAME;
    }

    /**
     * Discards all recorded counters, gauges and latency aggregates.
     *
     * <p>Unlike the other Community sinks, this is not a closed-flag guard: the
     * sink remains usable afterward and {@link #increment}/{@link #gauge}/
     * {@link #latency} recreate metrics on their next call.
     */
    @Override
    public void close() {
        counters.clear();
        gauges.clear();
        latencies.clear();
    }

    /**
     * Returns the Prometheus 0.0.4 text exposition snapshot.
     *
     * <p>Each metric block contains a {@code # HELP} line, a {@code # TYPE} line,
     * and one or more sample lines. Names are sorted lexicographically within each
     * type group so the output is deterministic for snapshot tests.
     *
     * @return non-null exposition text, each line newline-terminated; empty if no
     *         counter, gauge or latency metric has been recorded yet
     */
    public String exposeText() {
        StringBuilder out = new StringBuilder(256);
        appendCounters(out);
        appendGauges(out);
        appendLatencies(out);
        return out.toString();
    }

    private void appendCounters(StringBuilder out) {
        for (Map.Entry<String, LongAdder> entry : sortedSnapshot(counters).entrySet()) {
            String exported = sanitize(entry.getKey());
            out.append("# HELP ").append(exported).append(" Counter recorded by ").append(SINK_NAME).append('\n');
            out.append("# TYPE ").append(exported).append(" counter\n");
            out.append(exported).append(' ').append(entry.getValue().sum()).append('\n');
        }
    }

    private void appendGauges(StringBuilder out) {
        for (Map.Entry<String, AtomicLong> entry : sortedSnapshot(gauges).entrySet()) {
            String exported = sanitize(entry.getKey());
            out.append("# HELP ").append(exported).append(" Gauge recorded by ").append(SINK_NAME).append('\n');
            out.append("# TYPE ").append(exported).append(" gauge\n");
            out.append(exported).append(' ').append(entry.getValue().get()).append('\n');
        }
    }

    private void appendLatencies(StringBuilder out) {
        for (Map.Entry<String, LatencyAggregate> entry : sortedSnapshot(latencies).entrySet()) {
            String exported = sanitize(entry.getKey());
            LatencyAggregate aggregate = entry.getValue();
            out.append("# HELP ").append(exported).append(" Latency summary (nanoseconds) recorded by ")
                    .append(SINK_NAME).append('\n');
            out.append("# TYPE ").append(exported).append(" summary\n");
            out.append(exported).append("_count ").append(aggregate.count.sum()).append('\n');
            out.append(exported).append("_sum ").append(aggregate.sum.sum()).append('\n');
        }
    }

    private static <V> TreeMap<String, V> sortedSnapshot(Map<String, V> source) {
        return new TreeMap<>(source);
    }

    /**
     * Maps an arbitrary metric name to the Prometheus name regex
     * {@code [a-zA-Z_:][a-zA-Z0-9_:]*}.
     */
    /* default */ static String sanitize(String name) {
        StringBuilder builder = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            boolean firstCharValid = index == 0 && (Character.isLetter(current) || current == '_' || current == ':');
            boolean otherCharValid = index > 0
                    && (Character.isLetterOrDigit(current) || current == '_' || current == ':');
            if (firstCharValid || otherCharValid) {
                builder.append(current);
            } else if (index == 0) {
                // Prefix a leading digit/invalid first char with '_'.
                builder.append('_').append(current);
            } else {
                builder.append('_');
            }
        }
        return builder.length() == 0 ? "_" : builder.toString();
    }

    private static final class LatencyAggregate {
        private final LongAdder count = new LongAdder();
        private final LongAdder sum = new LongAdder();

        /* default */ void record(long nanoseconds) {
            count.increment();
            sum.add(nanoseconds);
        }
    }
}
