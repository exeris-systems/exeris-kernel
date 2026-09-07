/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p>Measures the hot-path emission cost:
 * <ol>
 *   <li><b>{@code emit()} throughput</b> — pre-built {@link KernelEvent} dispatched to a single sink.
 *       Isolates routing overhead from event construction overhead.</li>
 *   <li><b>No-op baseline</b> — measures whether a discard sink reaches near-zero cost
 *       (the fast-path branch prediction should eliminate all subsequent work); not
 *       asserted by this benchmark.</li>
 * </ol>
 *
 * <h2>SLO targets</h2>
 * <p>None of these figures are enforced by this class — JMH does not fail the build on a
 * miss, so conformance means comparing the printed throughput report to these targets by
 * hand.
 * <ul>
 *   <li>JFR sink throughput: {@code ≥ 2 000 000 ops/s} (pre-built event).</li>
 *   <li>Enterprise binary ring-buffer sink: {@code ≥ 5 000 000 ops/s}, {@code 0 B/op}.</li>
 *   <li>No-op discard sink: {@code ≥ 50 000 000 ops/s} (branch eliminated).</li>
 * </ul>
 *
 * @since 0.5
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

    /**
     * Creates the {@link TelemetrySink} under test (started, ready to emit).
     *
     * @return non-null, ready-to-use sink
     */
    protected abstract TelemetrySink createSink();

    /**
     * Creates a no-op {@link TelemetrySink} that discards all events immediately.
     * Used to establish a zero-work baseline for overhead measurement.
     * Default: anonymous no-op implementation.
     *
     * @return non-null sink whose methods discard every call
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

    /**
     * Creates the contract; subclasses supply the sink under test via {@link #createSink()}.
     */
    public AbstractTelemetrySinkBenchmark() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Trial-level setup: creates the sink under test and the no-op baseline sink, and
     * pre-builds the INFO/WARN events reused by every measurement iteration.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        sink     = createSink();
        noOpSink = createNoOpSink();
        hotInfoEvent = KernelEvent.info("EX-TCK-BENCH-001", "TelemetrySinkBenchmark.info");
        hotWarnEvent = KernelEvent.warn("EX-TCK-BENCH-002", "TelemetrySinkBenchmark.warn", null);
    }

    /**
     * Trial-level teardown: closes both the sink under test and the no-op baseline sink.
     */
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (sink     != null) sink.close();
        if (noOpSink != null) noOpSink.close();
    }

    /**
     * Returns the sink under test for use in subclass {@code @Benchmark} methods.
     * Populated during {@link #setUpTrial()}.
     *
     * @return the sink under test, or {@code null} before {@link #setUpTrial()} runs
     */
    protected final TelemetrySink getSink() {
        return sink;
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
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the pre-built event
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

    /**
     * Measures the throughput of emitting a pre-built WARN-level event, in case WARN
     * severity takes a different routing path than INFO in {@link #emitInfoThroughput}.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the pre-built event
     */
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
     * on an implementation that does nothing. A real sink is expected to stay within 10x
     * of this baseline.
     *
     * <p>{@code TelemetrySink} does not define an {@code isEnabled()} fast-gate; callers
     * that need to skip event construction under a filtered severity must check at the
     * call site (e.g., {@code if (router.level().includes(INFO)) sink.emit(event)}).
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the pre-built event
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void noOpSinkBaseline(Blackhole bh) {
        noOpSink.emit(hotInfoEvent);
        bh.consume(hotInfoEvent);
    }
}
