/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.perf.AbstractTelemetrySinkBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: {@link JfrTelemetrySink} emission throughput.
 *
 * <h2>What is measured</h2>
 * <ol>
 *   <li><b>emit(INFO)</b> — pre-built {@link KernelEvent} routed through the typed
 *       dispatch table to {@code KernelLifecycleJfrEvent.commit()}. Measures the cost
 *       of the {@code isEnabled()} gate + optional JFR event field population.</li>
 *   <li><b>emit(WARN) with MemoryExhaustedException</b> — exercises the
 *       {@code EX-MEM-1001} typed path: {@code rawArgs} extraction +
 *       {@code MemoryExhaustionJfrEvent.commit()}.</li>
 *   <li><b>emit(NET-4001) TransportBind</b> — exercises the {@code EX-NET} typed
 *       path: {@code rawString()} + {@code rawLong()} + {@code TransportBindJfrEvent.commit()}.</li>
 *   <li><b>No-op baseline</b> — inherited from {@link AbstractTelemetrySinkBenchmark};
 *       measures pure dispatch overhead with zero work.</li>
 * </ol>
 *
 * <h2>SLO (from telemetry.md)</h2>
 * <ul>
 *   <li>INFO path (isEnabled() = false, no active recording): {@code ≥ 20 000 000 ops/s}.</li>
 *   <li>INFO path (isEnabled() = true, active recording): {@code ≥ 2 000 000 ops/s}.</li>
 *   <li>Typed EX-MEM path: {@code ≥ 1 500 000 ops/s}.</li>
 * </ul>
 *
 * <h2>isEnabled() gate behaviour</h2>
 * <p>When JMH forks a new JVM with no active recording, {@code jdk.jfr.Event.isEnabled()}
 * returns {@code false} — the entire field-population block is skipped. This measures
 * the pure gate cost, which represents the production steady-state (no-recording) SLO.
 *
 * @since 0.5.0
 */
@Fork(value = 1, jvmArgsAppend = {
        "-XX:StartFlightRecording=duration=60s,filename=jmh-recording.jfr,settings=profile"
})
public class CoreJfrTelemetrySinkBenchmark extends AbstractTelemetrySinkBenchmark {

    private KernelEvent hotMemExhaustEvent;
    private KernelEvent hotTransportBindEvent;

    @Override
    protected TelemetrySink createSink() {
        return new JfrTelemetrySink();
    }

    @Setup(Level.Trial)
    public void setUpTypedEvents() {
        hotMemExhaustEvent = KernelEvent.error(
                "EX-MEM-1001", "CoreJfrTelemetrySinkBenchmark.memExhaust",
                new MemoryExhaustedException(8192L, 512L, null));
        hotTransportBindEvent = KernelEvent.error(
                "EX-NET-4001", "CoreJfrTelemetrySinkBenchmark.transportBind",
                eu.exeris.kernel.spi.exceptions.transport.TransportException
                        .bindFailure("bench-transport", 8443, null));
    }

    // =========================================================================
    // Benchmark: EX-MEM-1001 typed path
    // rawArgs[0]=long, rawArgs[1]=long → MemoryExhaustionJfrEvent
    // =========================================================================

    /**
     * Measures the typed {@code EX-MEM-1001} dispatch path:
     * domain prefix switch → {@code rawLong()} × 2 → {@code MemoryExhaustionJfrEvent.commit()}.
     *
     * <p>SLO: {@code ≥ 1 500 000 ops/s} with active recording.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void emitMemoryExhaustionTyped(Blackhole bh) {
        getSink().emit(hotMemExhaustEvent);
        bh.consume(hotMemExhaustEvent);
    }

    // =========================================================================
    // Benchmark: EX-NET-4001 typed path
    // rawArgs[0]=String, rawArgs[1]=int → TransportBindJfrEvent
    // =========================================================================

    /**
     * Measures the typed {@code EX-NET-4001} dispatch path:
     * domain prefix switch → {@code rawString()} + {@code rawLong()} →
     * {@code TransportBindJfrEvent.commit()}.
     *
     * <p>SLO: {@code ≥ 1 500 000 ops/s} with active recording.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void emitTransportBindTyped(Blackhole bh) {
        getSink().emit(hotTransportBindEvent);
        bh.consume(hotTransportBindEvent);
    }
}
