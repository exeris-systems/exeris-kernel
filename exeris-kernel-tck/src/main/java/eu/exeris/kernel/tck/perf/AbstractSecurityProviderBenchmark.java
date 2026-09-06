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
 * <h2>What is measured</h2>
 * <ol>
 *   <li><b>authenticate() throughput</b> — token extraction + verification from a
 *       pre-allocated off-heap {@link LoanedBuffer}. Enterprise: JWT parsing in-place
 *       on the slab via {@code MemorySegment.asSlice()} — no {@code String.split()},
 *       no heap {@code byte[]} copy.</li>
 *   <li><b>hasRole() latency</b> — RBAC check against a pre-bound {@code PrincipalContext},
 *       expected to be O(1) ({@code EnumSet.contains()}).</li>
 * </ol>
 *
 * <h2>SLO targets</h2>
 * <p>Neither figure is enforced by this class — JMH does not fail the build on an SLO
 * breach, so conformance means comparing the printed report to these targets by hand.
 * <ul>
 *   <li>authenticate(): {@code >= 500 000 ops/s}; Enterprise target: {@code 0 B/op},
 *       checked via the JMH {@code -prof gc} allocation profiler.</li>
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

    /**
     * Returns the {@link SecurityProvider} under test.
     *
     * @return non-null provider, ready to {@link SecurityProvider#authenticate} a token
     */
    protected abstract SecurityProvider createProvider();

    /**
     * Returns the {@link MemoryAllocator} used to hold the benchmark's off-heap token buffer.
     *
     * @return non-null, ready-to-use allocator
     */
    protected abstract MemoryAllocator  createAllocator();

    /**
     * Returns a valid signed token as UTF-8 bytes for the benchmark.
     *
     * @return non-null token bytes that {@link SecurityProvider#authenticate} accepts
     */
    protected abstract byte[] validTokenBytes();

    /**
     * Returns the role name string to check in hasRole() benchmark (e.g. "ROLE_USER").
     *
     * @return non-null role name granted to the pre-authenticated principal
     */
    protected abstract String benchmarkRole();

    /**
     * Returns a scope expected to be granted for hasScope allow-path latency checks.
     *
     * @return non-null scope name granted to the pre-authenticated principal
     */
    protected String benchmarkGrantedScope() {
        return "security:read";
    }

    /**
     * Returns a scope expected to be denied for hasScope deny-path latency checks.
     *
     * @return non-null scope name not granted to the pre-authenticated principal
     */
    protected String benchmarkDeniedScope() {
        return "security:write";
    }

    private SecurityProvider   provider;
    private MemoryAllocator    allocator;
    private LoanedBuffer       tokenBuffer;
    private AuthenticationResult preAuthenticated;

    /**
     * Trial-level setup: creates the provider and allocator, copies the benchmark's
     * signed token into an off-heap buffer, and authenticates once so that
     * {@code hasRole}/{@code hasScope} benchmarks measure only the RBAC check, not
     * authentication.
     */
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

    /**
     * Trial-level teardown: closes the token buffer and the allocator.
     */
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (tokenBuffer != null) tokenBuffer.close();
        if (allocator   != null) allocator.close();
    }

    /**
     * Hot-path benchmark — measures {@link SecurityProvider#authenticate} throughput on the
     * pre-copied token buffer. Throughput target: {@code >= 500 000 ops/s}; Enterprise
     * allocation target: {@code 0 B/op}, checked via the JMH {@code -prof gc} profiler.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the authentication result
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void authenticateThroughput(Blackhole bh) {
        bh.consume(provider.authenticate(tokenBuffer));
    }

    /**
     * Hot-path benchmark — measures the latency of one {@code hasRole} check against the
     * pre-authenticated principal. p99 target: {@code <= 100 ns}.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the boolean result
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void hasRoleLatency(Blackhole bh) {
        bh.consume(preAuthenticated.principal().hasRole(benchmarkRole()));
    }

    /**
     * Hot-path benchmark — measures the latency of one {@code hasScope} check on the
     * allow-path, using {@link #benchmarkGrantedScope()}.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the boolean result
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void hasScopeAllowLatency(Blackhole bh) {
        bh.consume(preAuthenticated.principal().hasScope(benchmarkGrantedScope()));
    }

    /**
     * Hot-path benchmark — measures the latency of one {@code hasScope} check on the
     * deny-path, using {@link #benchmarkDeniedScope()}.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the boolean result
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void hasScopeDenyLatency(Blackhole bh) {
        bh.consume(preAuthenticated.principal().hasScope(benchmarkDeniedScope()));
    }
}
