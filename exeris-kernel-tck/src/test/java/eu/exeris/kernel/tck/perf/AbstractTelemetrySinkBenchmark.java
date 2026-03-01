/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: Telemetry sink emission throughput.
 *
 * <h2>Front 4 — Arena wydajności (Telemetry SLO)</h2>
 * <p>Measures the hot-path emission cost:
 * <ol>
 *   <li><b>{@code emit()} throughput</b> — pre-built {@link KernelEvent} dispatched to a single sink.
 *       Isolates routing overhead from event construction overhead.</li>
 *   <li><b>isEnabled() fast-gate</b> — verifies that a disabled sink achieves near-zero cost
 *       (the fast-path branch prediction should eliminate all subsequent work).</li>
 * </ol>
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>JFR sink throughput: {@code ≥ 2 000 000 ops/s} (pre-built event).</li>
 *   <li>Enterprise binary ring-buffer sink: {@code ≥ 5 000 000 ops/s}, {@code 0 B/op}.</li>
 *   <li>Disabled sink (isEnabled=false): {@code ≥ 50 000 000 ops/s} (branch eliminated).</li>
 * </ul>
 *
 * <h2>Black-Box alignment (telemetry.md)</h2>
 * <p>The doc mandates: "Emission of 1M MemoryAllocationEvent samples adds &lt; 50 µs/req overhead."
 * This benchmark validates that SLO by extrapolation: at 2M ops/s, 1M samples cost &lt; 500 ms total,
 * meaning per-request overhead &lt; 50 µs at 1% sampling (1 sample per 100 requests).
 *
 * @since 0.5.0
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractTelemetrySinkBenchmark extends AbstractExerisBenchmark {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates the {@link TelemetrySink} under test (started, ready to emit). */
    protected abstract TelemetrySink createSink();

    /**
     * Creates a no-op {@link TelemetrySink} that discards all events immediately.
     * Used to establish a zero-work baseline for overhead measurement.
     * Default: anonymous no-op implementation.
     */
    protected TelemetrySink createNoOpSink() {
        return new TelemetrySink() {
            @Override public void   emit(KernelEvent e)             { /* no-op intentionally: baseline sink discards all events */ }
            @Override public void   increment(String n, long delta) { /* no-op intentionally: baseline sink discards counters */ }
            @Override public void   gauge(String n, long value)     { /* no-op intentionally: baseline sink discards gauges */ }
            @Override public void   latency(String n, long ns)      { /* no-op intentionally: baseline sink discards latency records */ }
            @Override public String sinkName()                      { return "no-op-baseline"; }
            @Override public void   close()                         { /* no-op intentionally: no resources to release */ }
        };
    }

    // =========================================================================
    // State
    // =========================================================================

    private TelemetrySink sink;
    private TelemetrySink noOpSink;
    private KernelEvent   hotInfoEvent;
    private KernelEvent   hotWarnEvent;

    @Setup(Level.Trial)
    public void setUpTrial() {
        sink     = createSink();
        noOpSink = createNoOpSink();
        hotInfoEvent = KernelEvent.info("EX-TCK-BENCH-001", "TelemetrySinkBenchmark.info");
        hotWarnEvent = KernelEvent.warn("EX-TCK-BENCH-002", "TelemetrySinkBenchmark.warn", null);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (sink     != null) sink.close();
        if (noOpSink != null) noOpSink.close();
    }

    // =========================================================================
    // Benchmark 1: emit(INFO) throughput
    // SLO: ≥ 2 000 000 ops/s (JFR) | ≥ 5 000 000 ops/s (Enterprise ring-buffer)
    // =========================================================================

    /**
     * Measures the throughput of emitting a pre-built INFO-level event.
     *
     * <p>JFR sink: one {@code jdk.jfr.Event.commit()} call per iteration —
     * the JFR framework copies fields into the recording buffer (off-heap).
     * No heap allocation per commit (JFR manages its own event memory).
     *
     * <p>Enterprise binary ring-buffer: single {@code MemorySegment.set()} CAS
     * into the off-heap ring — true 0 B/op.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void emitInfoThroughput(Blackhole bh) {
        sink.emit(hotInfoEvent);
        bh.consume(hotInfoEvent); // prevent DCE of the pre-built event
    }

    // =========================================================================
    // Benchmark 2: emit(WARN) throughput
    // WARN events may take a different routing path (severity filtering)
    // =========================================================================

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void emitWarnThroughput(Blackhole bh) {
        sink.emit(hotWarnEvent);
        bh.consume(hotWarnEvent);
    }

    // =========================================================================
    // Benchmark 3: no-op sink baseline — overhead of the dispatch call itself
    // SLO: ≥ 50 000 000 ops/s (pure dispatch table, zero work)
    // =========================================================================

    /**
     * No-op sink baseline: measures the irreducible overhead of calling {@code emit()}
     * on an implementation that does nothing. Any real sink should be within 10x of this.
     *
     * <p>This replaces the isEnabled() fast-gate benchmark — TelemetrySink does not
     * define isEnabled(). Callers that need filtering should check at the call site
     * (e.g., {@code if (router.level().includes(INFO)) sink.emit(event)}).
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void noOpSinkBaseline(Blackhole bh) {
        noOpSink.emit(hotInfoEvent);
        bh.consume(hotInfoEvent);
    }
}






