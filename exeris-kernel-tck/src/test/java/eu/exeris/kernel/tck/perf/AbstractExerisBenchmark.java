/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.perf;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Base JMH harness for all Exeris Kernel performance benchmarks.
 *
 * <h2>JVM Configuration</h2>
 * <p>Enforces {@code --enable-preview} for JEP 401 (Valhalla) and JEP 454 (FFM/Panama).
 * Uses ZGC ({@code -XX:+UseZGC}) to ensure consistent sub-millisecond GC pauses that
 * do not skew throughput measurements. Without ZGC, stop-the-world G1 pauses would
 * introduce artificial latency spikes in the 5-second measurement windows, masking
 * true implementation performance differences between Community and Enterprise tiers.
 *
 * <h2>Execution Profile</h2>
 * <ul>
 *   <li><b>Warmup:</b> 3 × 2 s — allows C2 JIT to compile and inline hot paths
 *       (especially VarHandle CAS loops in Enterprise slab pools)</li>
 *   <li><b>Measurement:</b> 5 × 5 s — sufficient steady-state window for JMH's
 *       statistical analysis to yield low-noise throughput estimates</li>
 *   <li><b>Mode:</b> {@link Mode#Throughput} — operations/second; the primary SLA
 *       metric for kernel subsystems</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This class itself carries no fields and is Valhalla-transparent. Subclasses
 * MUST avoid identity operations ({@code ==}, {@code synchronized}) on
 * Valhalla-ready data carriers (records, immutable finals) to allow JIT escape
 * analysis to scalarize them during the measurement phase.
 *
 * @since 0.5.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
        "--enable-preview",
        "-XX:+UseZGC",
    "-XX:+UnlockExperimentalVMOptions",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED"
})
public abstract class AbstractExerisBenchmark {
    // Base class for shared JVM configuration, JFR profiling hooks (future),
    // and Valhalla-readiness annotations inherited by all TCK benchmarks.
}
