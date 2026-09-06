/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
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
 * JMH Benchmark: TLS Engine wrap/unwrap throughput (zero-copy in-place).
 *
 * <p>Measures the hot-path cryptographic throughput:
 * <ol>
 *   <li><b>{@code wrap} throughput:</b> plaintext → ciphertext encryption of a
 *       1 KB application data record. Enterprise is required to operate entirely on
 *       off-heap {@link LoanedBuffer}s — no {@code byte[]} copy to the heap.</li>
 *   <li><b>{@code unwrap} throughput:</b> ciphertext → plaintext decryption of a
 *       pre-encrypted record. Same zero-copy constraint.</li>
 * </ol>
 *
 * <h2>SLO targets</h2>
 * <p>None of these figures are enforced by this class — conformance means reading the
 * printed report and comparing it to these targets by hand.
 * <ul>
 *   <li>wrap throughput: {@code ≥ 500 000 ops/s} at 1 KB record size.</li>
 *   <li>wrap + unwrap round-trip: {@code ≤ 5 µs} p99.</li>
 *   <li>Enterprise: {@code 0 B/op} heap allocation in steady state (post-handshake).</li>
 * </ul>
 *
 * <h2>Allocation profiling</h2>
 * <p>Run with {@code -prof gc} to read {@code norm.alloc}: TLS vectors are required to
 * work in-place on {@link LoanedBuffer}, never materialising temporary {@code byte[]}
 * arrays on the Java heap. This benchmark does not assert {@code norm.alloc = 0 B/op}
 * itself; {@link eu.exeris.kernel.tck.contract.crypto.CryptoZeroAllocTck} is the contract
 * test that enforces zero {@code eu.exeris.*} allocations per {@code wrap()}/{@code unwrap()}
 * call for an Enterprise-tier implementation.
 *
 * @since 0.5
 * @see eu.exeris.kernel.tck.contract.crypto.CryptoZeroAllocTck
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractTlsEngineBenchmark extends AbstractExerisBenchmark {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Returns a {@link KernelCryptoProvider} under test (post-ServiceLoader discovery).
     *
     * @return non-null provider, ready to create a {@link TlsEngine}
     */
    protected abstract KernelCryptoProvider createCryptoProvider();

    /**
     * Returns the {@link MemoryAllocator} used for off-heap plaintext/ciphertext buffers.
     * Must be started before {@link #setUpTrial()} returns.
     *
     * @return non-null, ready-to-use allocator
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Returns {@code true} if this is Enterprise-tier (zero-GC TLS path). Default: false.
     *
     * @return {@code true} for an Enterprise-tier implementation, {@code false} otherwise
     */
    protected boolean isEnterpriseTier() {
        return false;
    }

    // =========================================================================
    // State — pre-allocated buffers, re-used across ALL iterations (Enterprise: 0 B/op)
    // =========================================================================

    private TlsEngine tlsEngine;
    private MemoryAllocator allocator;
    private LoanedBuffer plaintext;
    private LoanedBuffer ciphertext;
    private LoanedBuffer decrypted;

    /**
     * 1 KB of synthetic application data written once at setup.
     */
    private static final int PAYLOAD_BYTES = 1024;

    /**
     * Trial-level setup: creates the allocator and TLS engine, pre-allocates the
     * plaintext/ciphertext/decrypted buffers reused across every iteration, fills the
     * plaintext payload, and drives the handshake to completion.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        allocator = createAllocator();
        tlsEngine = createCryptoProvider().createTlsEngine(buildTlsConfig());

        // Pre-allocate three MEDIUM buffers (≥ 16 KB — large enough for TLS record overhead).
        // These are NOT re-allocated per iteration — Enterprise: 0 B/op after this point.
        plaintext = allocator.allocate(AllocationHint.MEDIUM);
        ciphertext = allocator.allocate(AllocationHint.MEDIUM);
        decrypted = allocator.allocate(AllocationHint.MEDIUM);

        // Fill first PAYLOAD_BYTES of plaintext with 0xAB using bulk MemorySegment.fill().
        // fill() operates on the entire segment — we slice to PAYLOAD_BYTES to stay within bounds
        // and avoid any per-byte loop that could trigger CodeQL "Constant loop condition".
        plaintext.segment().asSlice(0, PAYLOAD_BYTES).fill((byte) 0xAB);

        // Complete handshake (loopback self-test via beginHandshake)
        completeHandshake();
    }

    /**
     * Trial-level teardown: closes the TLS engine, the three buffers, and the allocator.
     */
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (tlsEngine != null) tlsEngine.close();
        if (plaintext != null) plaintext.close();
        if (ciphertext != null) ciphertext.close();
        if (decrypted != null) decrypted.close();
        if (allocator != null) allocator.close();
    }

    // =========================================================================
    // Benchmark 1: wrap (encrypt) throughput
    // SLO: ≥ 500 000 ops/s at 1 KB | Enterprise: 0 B/op
    // =========================================================================

    /**
     * Encrypts {@code plaintext} → {@code ciphertext} using the pre-warmed TLS session.
     *
     * <p>The buffer is reused across iterations: Enterprise implementations are required
     * to operate in-place on the {@code LoanedBuffer}'s {@code MemorySegment.asSlice()}
     * view without creating temporary {@code byte[]} arrays. Any heap allocation here
     * is a violation of the {@link eu.exeris.kernel.tck.contract.crypto.CryptoZeroAllocTck}
     * contract.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the wrap status
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void wrapThroughput(Blackhole bh) {
        TlsStatus status = tlsEngine.wrap(plaintext, ciphertext);
        bh.consume(status);
    }

    // =========================================================================
    // Benchmark 2: wrap + unwrap round-trip latency
    // SLO: p99 ≤ 5 µs
    // =========================================================================

    /**
     * Full round-trip: plaintext → encrypt → decrypt back.
     *
     * <p>This measures the symmetric crypto path as experienced by a transport
     * handler receiving a complete TLS record and decrypting it into the
     * application-layer buffer.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the wrap/unwrap status
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void wrapUnwrapRoundTrip(Blackhole bh) {
        TlsStatus wrapStatus = tlsEngine.wrap(plaintext, ciphertext);
        TlsStatus unwrapStatus = tlsEngine.unwrap(ciphertext, decrypted);
        bh.consume(wrapStatus);
        bh.consume(unwrapStatus);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a minimal TLS config for benchmark purposes.
     * Subclass may override to customise (e.g., QUIC for Enterprise).
     *
     * @return non-null config, TLS 1.3 over TCP with no certificate, key or session cache
     */
    protected eu.exeris.kernel.spi.crypto.CryptoProviderConfig buildTlsConfig() {
        return new eu.exeris.kernel.spi.crypto.CryptoProviderConfig(
                eu.exeris.kernel.spi.crypto.CryptoProviderConfig.Protocol.TCP_TLS,
                null,          // no cert needed for loopback/mock benchmark
                null,          // no key needed for loopback/mock benchmark
                java.util.List.of("h2"),
                0,             // no session cache — fresh handshake per trial
                false,         // JFR disabled in benchmark (overhead)
                eu.exeris.kernel.spi.crypto.CryptoProviderConfig.TLS_1_3
        );
    }

    /**
     * Drives the handshake loop until the engine reaches a post-handshake state.
     *
     * <p>Benchmark setup is strict and fails fast when handshake cannot complete.
     */
    private void completeHandshake() {
        TlsStatus status = tlsEngine.beginHandshake(ciphertext);
        final int maxSteps = 64;
        int steps = 0;
        while (steps++ < maxSteps &&
                (status == TlsStatus.NEED_WRAP
                        || status == TlsStatus.NEED_UNWRAP
                        || status == TlsStatus.NEED_HANDSHAKE)) {
            if (status == TlsStatus.NEED_WRAP) {
                status = tlsEngine.wrap(plaintext, ciphertext);
                continue;
            }
            if (status == TlsStatus.NEED_UNWRAP) {
                status = tlsEngine.unwrap(ciphertext, decrypted);
                continue;
            }
            status = tlsEngine.beginHandshake(ciphertext);
        }

        if (status == TlsStatus.FINISHED || status == TlsStatus.OK) {
            return;
        }
        if (status == TlsStatus.CLOSED) {
            throw new IllegalStateException("TLS handshake closed unexpectedly during benchmark setup");
        }
        throw new IllegalStateException("TLS handshake did not complete in benchmark setup, final status=" + status);
    }
}
