/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.crypto.TlsPhase;
import eu.exeris.kernel.tck.perf.AbstractExerisBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: {@link TlsStateMachine} VarHandle lock-free operations.
 *
 * <h2>What is measured</h2>
 * <p>The {@link TlsStateMachine} is the lock-free backbone of {@link OffHeapTlsEngine}.
 * Its hot-path is invoked on every {@code wrap()} and {@code unwrap()} call as an
 * O(1) guard check. This benchmark verifies two distinct cost centres:
 *
 * <ol>
 *   <li><b>{@link #phaseRead}</b> — raw VarHandle acquire-read of a single
 *       {@code volatile int} field via {@link java.lang.invoke.VarHandle#getAcquire}.
 *       This is the fastest path: one CPU load-acquire instruction + array index lookup
 *       into the pre-cached {@code PHASES[]} constant. Measures the absolute floor cost
 *       of the guard check pattern used in {@code checkActive()} and
 *       {@code checkActiveForDecrypt()}.</li>
 *
 *   <li><b>{@link #activeGuardCheck}</b> — the exact two-instruction pattern emitted
 *       by the JIT for {@code stateMachine.phase() == TlsPhase.ACTIVE}: VarHandle
 *       acquire-read + enum reference comparison. This is what the C2 JIT sees after
 *       inlining {@code checkActive()} into {@code wrap()}. Measures whether the JIT
 *       successfully constant-folds the comparison or introduces a branch.</li>
 *
 *   <li><b>{@link #fullLifecycleUninitToActive}</b> — the complete happy-path bootstrap
 *       sequence: {@code UNINITIALIZED → HANDSHAKE_IN_PROGRESS → HANDSHAKE_COMPLETE → ACTIVE}.
 *       Three sequential CAS transitions plus three {@link TlsPhaseTransitionEvent} JFR
 *       commits. Dual-mode ({@code Throughput} + {@code SampleTime}) enables simultaneous
 *       capacity planning (max new sessions/s) and tail-latency gating (p99 ≤ 3 µs).</li>
 * </ol>
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>{@code phaseRead}: {@code ≥ 500 000 000 ops/s} — must be faster than a nanosecond
 *       (single acquire-load + array index).</li>
 *   <li>{@code activeGuardCheck}: {@code ≥ 200 000 000 ops/s} — additional comparison
 *       overhead must remain below 5 ns.</li>
 *   <li>{@code fullLifecycleUninitToActive}: {@code Throughput ≥ 30 000 000 ops/s},
 *       {@code SampleTime p99 ≤ 3 µs} — three CAS transitions + three JFR commits
 *       (complete session bootstrap cost, once per accepted TLS connection).</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>No identity operations ({@code ==}, {@code synchronized}, {@code identityHashCode}) are
 * performed on {@link TlsStateMachine} or {@link TlsPhase}. The C2 JIT can fully scalarize
 * the phase comparison via escape analysis, eliminating the object header load entirely on
 * the hot path. Results should improve further once JEP 401 ({@code value class}) lands.
 *
 * <h2>Does not require OpenSSL</h2>
 * <p>This benchmark exercises only the pure-Java VarHandle state machine — no FFM
 * calls, no native library required. Safe to run on any platform with JDK 26+.
 *
 * @since 0.5.0
 */
@Fork(value = 1)
@State(Scope.Benchmark)
public class CoreTlsStateMachineBenchmark extends AbstractExerisBenchmark {

    // =========================================================================
    // State — ACTIVE-phase machine (trial-scoped, no per-iteration allocation)
    // =========================================================================

    /**
     * State machine pre-advanced to {@link TlsPhase#ACTIVE} so the guard check
     * always takes the fast (no-throw) path. This is the steady-state condition
     * in production: every {@code wrap()} and {@code unwrap()} on a live session
     * begins with {@code phase() == ACTIVE → continue}.
     */
    private TlsStateMachine activeMachine;

    @Setup(Level.Trial)
    public void setUpTrial() {
        activeMachine = new TlsStateMachine();
        activeMachine.transitionTo(TlsPhase.UNINITIALIZED,          TlsPhase.HANDSHAKE_IN_PROGRESS);
        activeMachine.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS,  TlsPhase.HANDSHAKE_COMPLETE);
        activeMachine.transitionTo(TlsPhase.HANDSHAKE_COMPLETE,     TlsPhase.ACTIVE);
    }

    // =========================================================================
    // Benchmark 1: phase() VarHandle acquire-read
    // SLO: ≥ 500 000 000 ops/s
    // =========================================================================

    /**
     * Measures the raw cost of {@link TlsStateMachine#phase()} — a single
     * {@link java.lang.invoke.VarHandle#getAcquire} on a {@code volatile int} field
     * followed by a {@code PHASES[ordinal]} array lookup.
     *
     * <p>This is the absolute floor latency for the guard check. C2 JIT is expected
     * to inline and hoist the array base address, leaving a single load-acquire
     * instruction in the steady state.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public TlsPhase phaseRead() {
        return activeMachine.phase();
    }

    // =========================================================================
    // Benchmark 2: phase() + ACTIVE comparison (the exact checkActive() pattern)
    // SLO: ≥ 200 000 000 ops/s
    // =========================================================================

    /**
     * Measures the exact guard-check pattern used in {@code OffHeapTlsEngine.checkActive()}:
     * <pre>
     *   stateMachine.phase() != TlsPhase.ACTIVE
     * </pre>
     *
     * <p>With C2 JIT inlining and escape analysis, both the {@code phase()} call and the
     * enum reference comparison should reduce to a single load-acquire + branch instruction
     * in the generated machine code. The benchmark verifies this optimisation holds under
     * JMH's steady-state measurement conditions.
     *
     * <p>Returns {@code boolean} to prevent the JIT from dead-code-eliminating the comparison.
     * {@link Blackhole#consume(boolean)} is called by the caller's JMH-generated harness.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public boolean activeGuardCheck() {
        return activeMachine.phase() == TlsPhase.ACTIVE;
    }

    // =========================================================================
    // Benchmark 3: canTransitionTo() — bitmask check (no CAS, no JFR)
    // SLO: ≥ 300 000 000 ops/s
    // =========================================================================

    /**
     * Measures {@link TlsStateMachine#canTransitionTo(TlsPhase)}: one VarHandle
     * acquire-read + one bitmask AND operation. Used by higher-level orchestration
     * logic to decide whether to call {@code transitionTo} without paying a CAS
     * that would fail.
     *
     * <p>This is a pure read — no CAS, no JFR event. The bitmask check should be
     * essentially free (single branch) after JIT compilation.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public boolean canTransitionToShutdownCheck() {
        return activeMachine.canTransitionTo(TlsPhase.SHUTDOWN_INITIATED);
    }

    // =========================================================================
    // Benchmark 4: Full lifecycle CAS chain — per-invocation fresh state machine
    // SLO: p99 ≤ 1 µs for all 4 transitions
    // =========================================================================

    /**
     * Fresh state machine reset between invocations — transition table state.
     * Declared {@code package-private volatile} so JMH can write it between iterations.
     */
    /* default */ volatile TlsStateMachine freshMachine;

    @Setup(Level.Invocation)
    public void resetFreshMachine() {
        freshMachine = new TlsStateMachine();
        freshMachine.transitionTo(TlsPhase.UNINITIALIZED,         TlsPhase.HANDSHAKE_IN_PROGRESS);
        freshMachine.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS, TlsPhase.HANDSHAKE_COMPLETE);
    }

    /**
     * Measures the last CAS transition in the handshake lifecycle:
     * {@code HANDSHAKE_COMPLETE → ACTIVE}.
     *
     * <p>This is the performance-critical transition: it is emitted exactly once per
     * TLS session, immediately before the connection enters steady-state data transfer.
     * The JFR {@link TlsPhaseTransitionEvent} is emitted inside this call — the benchmark
     * captures the combined CAS + JFR commit cost.
     *
     * <p><b>Note on {@code Level.Invocation} setup:</b> {@link Level#Invocation}
     * adds call overhead (object allocation + 2 prior CAS transitions) that is NOT
     * included in the measurement timing. JMH begins timing after
     * {@link #resetFreshMachine()} returns. Use {@code Mode.SampleTime} to account
     * for per-invocation variance rather than {@code Throughput}.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public TlsPhase handshakeCompleteToActiveCas(Blackhole bh) {
        freshMachine.transitionTo(TlsPhase.HANDSHAKE_COMPLETE, TlsPhase.ACTIVE);
        TlsPhase result = freshMachine.phase();
        bh.consume(result);
        return result;
    }

    // =========================================================================
    // Benchmark 5: Full happy-path UNINITIALIZED → ACTIVE (3 CAS + 3 JFR events)
    // SLO: Throughput ≥ 30 000 000 ops/s  |  SampleTime p99 ≤ 3 µs
    // =========================================================================

    /**
     * Fresh {@link TlsPhase#UNINITIALIZED} state machine per invocation — full lifecycle reset.
     * Declared {@code package-private volatile} so JMH can write it between iterations.
     */
    /* default */ volatile TlsStateMachine uninitMachine;

    @Setup(Level.Invocation)
    public void resetUninitMachine() {
        uninitMachine = new TlsStateMachine();
    }

    /**
     * Measures the complete happy-path lifecycle from {@link TlsPhase#UNINITIALIZED}
     * to {@link TlsPhase#ACTIVE}: three sequential CAS transitions plus three
     * {@link TlsPhaseTransitionEvent} JFR commits.
     *
     * <p>This is the end-to-end cost paid exactly once per accepted TLS connection,
     * between socket-accept and the first {@code wrap()} call. Dual-mode measurement
     * ({@code Throughput} + {@code SampleTime}) enables simultaneous capacity planning
     * (max new sessions/s) and tail-latency gating (p99 ≤ 3 µs).
     *
     * <p>Returns the final {@link TlsPhase#ACTIVE} reference to prevent the JIT from
     * dead-code-eliminating the transition chain.
     *
     * <p><b>Note on {@link Level#Invocation} setup:</b> the {@code new TlsStateMachine()}
     * allocation is excluded from timing. Only the three {@code transitionTo()} CAS calls
     * and their associated JFR event commits are measured.
     */
    @Benchmark
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public TlsPhase fullLifecycleUninitToActive() {
        uninitMachine.transitionTo(TlsPhase.UNINITIALIZED,         TlsPhase.HANDSHAKE_IN_PROGRESS);
        uninitMachine.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS, TlsPhase.HANDSHAKE_COMPLETE);
        uninitMachine.transitionTo(TlsPhase.HANDSHAKE_COMPLETE,    TlsPhase.ACTIVE);
        return uninitMachine.phase();
    }
}
