/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.SecurityProvider;
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
 * JMH Benchmark: Security Provider — authentication throughput and RBAC check latency.
 *
 * <h2>Front 4 — Arena wydajności (Security SLO)</h2>
 * <ol>
 *   <li><b>authenticate() throughput</b> — token extraction + verification from a
 *       pre-allocated off-heap {@link LoanedBuffer}. Enterprise: JWT parsing in-place
 *       on the slab via {@code MemorySegment.asSlice()} — no {@code String.split()},
 *       no heap {@code byte[]} copy.</li>
 *   <li><b>hasRole() latency</b> — RBAC check against a pre-bound {@code PrincipalContext}.
 *       Must be O(1) — {@code EnumSet.contains()}.</li>
 * </ol>
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>authenticate(): {@code >= 500 000 ops/s}, Enterprise: {@code 0 B/op}.</li>
 *   <li>hasRole() p99: {@code <= 100 ns}.</li>
 * </ul>
 *
 * @since 0.5
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractSecurityProviderBenchmark extends AbstractExerisBenchmark {

    protected abstract SecurityProvider createProvider();
    protected abstract MemoryAllocator  createAllocator();
    /** Returns a valid signed token as UTF-8 bytes for the benchmark. */
    protected abstract byte[] validTokenBytes();
    /** Returns the role name string to check in hasRole() benchmark (e.g. "ROLE_USER"). */
    protected abstract String benchmarkRole();
    /** Returns a scope expected to be granted for hasScope allow-path latency checks. */
    protected String benchmarkGrantedScope() {
        return "security:read";
    }
    /** Returns a scope expected to be denied for hasScope deny-path latency checks. */
    protected String benchmarkDeniedScope() {
        return "security:write";
    }

    private SecurityProvider   provider;
    private MemoryAllocator    allocator;
    private LoanedBuffer       tokenBuffer;
    private AuthenticationResult preAuthenticated;

    @Setup(Level.Trial)
    public void setUpTrial() {
        provider  = createProvider();
        allocator = createAllocator();

        byte[] bytes = validTokenBytes();
        tokenBuffer  = allocator.allocate(AllocationHint.SMALL);
        // Bulk copy: MemorySegment.copyFrom avoids a per-byte loop and any
        // "Constant loop condition" CodeQL warning. Slice guards against tokens
        // longer than the allocated segment.
        long copyLen = Math.min(bytes.length, tokenBuffer.segment().byteSize());
        tokenBuffer.segment().asSlice(0, copyLen)
                .copyFrom(java.lang.foreign.MemorySegment.ofArray(bytes).asSlice(0, copyLen));
        preAuthenticated = provider.authenticate(tokenBuffer);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (tokenBuffer != null) tokenBuffer.close();
        if (allocator   != null) allocator.close();
    }

    // SLO: >= 500 000 ops/s | Enterprise: 0 B/op
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void authenticateThroughput(Blackhole bh) {
        bh.consume(provider.authenticate(tokenBuffer));
    }

    // SLO: p99 <= 100 ns — EnumSet O(1) contains
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void hasRoleLatency(Blackhole bh) {
        bh.consume(preAuthenticated.principal().hasRole(benchmarkRole()));
    }

    // SLO: authorization fast-path by granted scope remains O(1)
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void hasScopeAllowLatency(Blackhole bh) {
        bh.consume(preAuthenticated.principal().hasScope(benchmarkGrantedScope()));
    }

    // SLO: authorization fast-path by denied scope remains O(1)
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void hasScopeDenyLatency(Blackhole bh) {
        bh.consume(preAuthenticated.principal().hasScope(benchmarkDeniedScope()));
    }
}
