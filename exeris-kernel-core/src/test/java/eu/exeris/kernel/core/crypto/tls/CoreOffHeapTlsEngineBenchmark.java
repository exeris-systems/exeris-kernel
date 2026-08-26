/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: {@link OffHeapTlsEngine} Core-tier hot-path guard costs.
 *
 * <h2>What is measured</h2>
 * <p>This benchmark isolates Core-tier operations that are independent of BIO wiring
 * (Community/Enterprise responsibility). Four hot-path cost centres are measured:
 *
 * <ol>
 *   <li><b>{@link #guardCheckWrapCost}</b> — cost of {@code wrap()} when the engine is in
 *       {@link eu.exeris.kernel.spi.crypto.TlsPhase#HANDSHAKE_IN_PROGRESS}: one VarHandle
 *       acquire-read ({@code closedFlag}), one acquire-read ({@code phaseOrdinal}), and a
 *       throw/catch of the pre-allocated {@code HOT_PATH_WRAP_SENTINEL} singleton. Zero heap
 *       allocation guaranteed by the Sentinel contract.</li>
 *
 *   <li><b>{@link #guardCheckUnwrapCost}</b> — same measurement for the {@code unwrap()} path
 *       using the {@code DECRYPT_CLOSED_SENTINEL}. Both sentinels are distinct pre-allocated
 *       singletons to satisfy the one-code-one-schema invariant (EX-NET-2001 vs EX-NET-2003).</li>
 *
 *   <li><b>{@link #beginHandshakeNoFdCost}</b> — cost of {@code SSL_connect} FFM downcall
 *       ({@link java.lang.invoke.MethodHandle#invokeExact} → OpenSSL C call → immediate
 *       {@code SSL_ERROR_SSL} return, no real peer). Measures the Panama FFM overhead +
 *       OpenSSL state-machine entry + {@link TlsStateMachine} phase validation.</li>
 *
 *   <li><b>{@link #bindTransportFdCost}</b> — cost of one direct
 *       {@code SSL_set_fd(SSL*, int)} FFM downcall through
 *       {@link OffHeapTlsEngine#bindTransportFd(MethodHandle, int)} against a real
 *       loopback-backed file descriptor extracted from {@link SocketChannel}.</li>
 * </ol>
 *
 * <h2>Architectural scope (The Wall)</h2>
 * <p>Community/Enterprise {@code wrap}/{@code unwrap} THROUGHPUT (steady-state post-handshake
 * path) is measured in their respective {@code AbstractTlsEngineBenchmark} implementations.
 * Core benchmarks here cover only what Core is responsible for: the guard checks, the sentinel
 * pre-allocation contract, and the raw FFM invocation cost.
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>Guard sentinel (wrap/unwrap): {@code ≥ 50 000 000 ops/s} — the guard check must be
 *       negligible (~20 ns) relative to the actual {@code SSL_write} cost in the steady state.</li>
 *   <li>{@code beginHandshake} FFM call (no fd, immediate fail): {@code ≤ 2 µs} p99.</li>
 * </ul>
 *
 * <h2>Requires</h2>
 * <p>OpenSSL 3.x must be installed. Run with:
 * {@code mvn verify -P benchmarks -pl exeris-kernel-core -Dbenchmark.includes=CoreOffHeapTlsEngine}
 *
 * @since 0.5.0
 */
@Fork(value = 1)
@State(Scope.Benchmark)
public class CoreOffHeapTlsEngineBenchmark extends AbstractExerisBenchmark {

    // =========================================================================
    // One-time OpenSSL bootstrap — JVM-global arena, class-load time
    // =========================================================================

    private static final Arena GLOBAL_ARENA = Arena.global();
    private static CoreSslHandles handles;
    private static long sslCtxPtr;
    private static MethodHandle sslSetFdHandle;
    private static Throwable loadError;

    static {
        try {
            CoreOpenSslRuntime runtime = CoreOpenSslLoader.load(GLOBAL_ARENA);
            handles     = runtime.handles();
            long method = handles.ctx().invokeServerMethod();
            sslCtxPtr   = handles.ctx().invokeCtxNew(method);
            sslSetFdHandle = runtime.optionalSslHandle(
                    "SSL_set_fd",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT));
            if (sslSetFdHandle == null) {
                throw new IllegalStateException("OpenSSL symbol SSL_set_fd is required for benchmark");
            }
        } catch (Throwable t) { //NOPMD AvoidCatchingThrowable — FFM library load may throw Error
            loadError = t;
        }
    }

    // =========================================================================
    // Shared off-heap slab allocator — 4 MiB pre-committed, zero heap per slice
    // =========================================================================

    private static final int SLAB_SIZE = 4 * 1024 * 1024;
    private static final MemorySegment SLAB = GLOBAL_ARENA.allocate(SLAB_SIZE, 8L);
    private static long slabCursor;

    private static final MemoryAllocator SLAB_ALLOC = buildSlabAllocator();

    private static MemoryAllocator buildSlabAllocator() {
        return new MemoryAllocator() {
            @Override
            public LoanedBuffer allocate(AllocationHint hint) {
                int bytes = Math.max(hint.sizeBytes(), 16_384);
                long off  = slabCursor;
                if (off + bytes > SLAB_SIZE) {
                    throw new MemoryExhaustedException(bytes, SLAB_SIZE - off);
                }
                slabCursor = off + bytes;
                return slabBufferAt(SLAB.asSlice(off, bytes), bytes);
            }

            @Override public LoanedBuffer allocateNetwork(int b)         { return allocate(AllocationHint.MEDIUM); }
            @Override public LoanedBuffer allocateCarrierSlab(int i)     { return allocate(AllocationHint.MEDIUM); }
            @Override public LoanedBuffer allocateInfrastructure(long b) { return allocate(AllocationHint.MEDIUM); }
            @Override public MemoryStats  stats()                        { return MemoryStats.zero(); }
            @Override public void         close()                        { /* backed by Arena.global() — JVM-lifetime */ }
        };
    }

    private static LoanedBuffer slabBufferAt(MemorySegment slice, int cap) {
        return new LoanedBuffer() {
            private long sz = cap;
            @Override public MemorySegment segment()           { return slice; }
            @Override public long           size()             { return sz; }
            @Override public long           capacity()         { return cap; }
            @Override public void           setSize(long s)    { sz = s; }
            @Override public void           close()            { /* slab: no per-slice release */ }
            @Override public void           retain()           { /* single-owner benchmark harness */ }
            @Override public int            refCount()         { return 1; }
            @Override public boolean        isAlive()          { return true; }
            @Override public void           addCloseAction(Runnable r)      { r.run(); }
            @Override public LoanedBuffer   slice(long o, long l)           { throw new UnsupportedOperationException("slab: slice not supported"); }
            @Override public LoanedBuffer   view()                          { throw new UnsupportedOperationException("slab: view not supported"); }
            @Override public LoanedBuffer   peek(long o, long l)            { throw new UnsupportedOperationException("slab: peek not supported"); }
        };
    }

    // =========================================================================
    // Benchmark state — trial-scoped engine instances
    // =========================================================================

    /**
     * Engine for guard-check benchmarks — stays in HANDSHAKE_IN_PROGRESS throughout
     * the entire trial, ensuring every wrap/unwrap call hits the pre-allocated sentinel.
     */
    private OffHeapTlsEngine guardEngine;
    private LoanedBuffer      guardPlaintext;
    private LoanedBuffer      guardCiphertext;

    /**
     * Engine for {@code beginHandshake} FFM-call benchmark — notifyBound() was called
     * but no real fd is attached. SSL_connect/accept returns immediately with SSL_ERROR_SSL.
     */
    private OffHeapTlsEngine beginHandshakeEngine;
    private LoanedBuffer      beginHandshakeBuf;

    private OffHeapTlsEngine bindFdEngine;
    private ServerSocketChannel bindServerSocket;
    private SocketChannel bindClientSocket;
    private SocketChannel bindAcceptedSocket;
    private int bindFd;

    @Setup(Level.Trial)
    public void setUpTrial() {
        if (loadError != null) {
            throw new IllegalStateException(
                    "OpenSSL 3.x not available — CoreOffHeapTlsEngineBenchmark cannot run", loadError);
        }

        guardEngine     = new OffHeapTlsEngine(handles, sslCtxPtr, false, SLAB_ALLOC);
        guardEngine.notifyBound(); // UNINITIALIZED → HANDSHAKE_IN_PROGRESS
        guardPlaintext  = SLAB_ALLOC.allocate(AllocationHint.MEDIUM);
        guardCiphertext = SLAB_ALLOC.allocate(AllocationHint.MEDIUM);
        guardPlaintext.segment().asSlice(0, 1024).fill((byte) 0xAB);
        guardPlaintext.setSize(1024L);

        beginHandshakeEngine = new OffHeapTlsEngine(handles, sslCtxPtr, false, SLAB_ALLOC);
        beginHandshakeEngine.notifyBound(); // UNINITIALIZED → HANDSHAKE_IN_PROGRESS
        beginHandshakeBuf = SLAB_ALLOC.allocate(AllocationHint.MEDIUM);

        bindFdEngine = new OffHeapTlsEngine(handles, sslCtxPtr, false, SLAB_ALLOC);

        try {
            bindServerSocket = ServerSocketChannel.open();
            bindServerSocket.configureBlocking(true);
            bindServerSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) bindServerSocket.getLocalAddress()).getPort();

            bindClientSocket = SocketChannel.open();
            bindClientSocket.configureBlocking(true);
            bindClientSocket.connect(new InetSocketAddress("127.0.0.1", port));
            bindAcceptedSocket = bindServerSocket.accept();
            bindFd = extractFd(bindAcceptedSocket);
        } catch (Exception e) {
            closeQuietly(bindAcceptedSocket);
            closeQuietly(bindClientSocket);
            closeQuietly(bindServerSocket);
            throw new IllegalStateException("Could not create loopback socket pair for bind benchmark", e);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (guardEngine          != null) guardEngine.close();
        if (beginHandshakeEngine != null) beginHandshakeEngine.close();
        if (bindFdEngine         != null) bindFdEngine.close();
        closeQuietly(bindAcceptedSocket);
        closeQuietly(bindClientSocket);
        closeQuietly(bindServerSocket);
    }

    // =========================================================================
    // Benchmark 1: wrap() guard path — pre-allocated HOT_PATH_WRAP_SENTINEL
    // SLO: ≥ 50 000 000 ops/s | 0 B/op heap allocation
    // =========================================================================

    /**
     * Measures the cost of the {@code wrap()} guard chain when the engine has not yet
     * completed a TLS handshake ({@link eu.exeris.kernel.spi.crypto.TlsPhase#HANDSHAKE_IN_PROGRESS}).
     *
     * <p>Execution path per iteration:
     * <ol>
     *   <li>{@code CLOSED.getAcquire(this)} — one VarHandle acquire-read on {@code closedFlag}.</li>
     *   <li>{@code PHASE.getAcquire(stateMachine)} — one VarHandle acquire-read on phase ordinal.</li>
     *   <li>{@code throw HOT_PATH_WRAP_SENTINEL} — throws the pre-allocated singleton (no alloc).</li>
     *   <li>Catch block in this method — exception consumed by {@link Blackhole}.</li>
     * </ol>
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void guardCheckWrapCost(Blackhole bh) {
        try {
            guardEngine.wrap(guardPlaintext, guardCiphertext);
        } catch (Exception sentinel) { //NOPMD AvoidCatchingGenericException — HOT_PATH_WRAP_SENTINEL expected
            bh.consume(sentinel);
        }
    }

    // =========================================================================
    // Benchmark 2: unwrap() guard path — pre-allocated DECRYPT_CLOSED_SENTINEL
    // SLO: ≥ 50 000 000 ops/s | 0 B/op heap allocation
    // =========================================================================

    /**
     * Measures the cost of the {@code unwrap()} guard chain via the
     * {@code DECRYPT_CLOSED_SENTINEL} (EX-NET-2003).
     *
     * <p>Identical guard structure to {@link #guardCheckWrapCost} but exercises a
     * distinct pre-allocated singleton to validate that both sentinels satisfy
     * the one-code-one-schema invariant independently.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void guardCheckUnwrapCost(Blackhole bh) {
        try {
            guardEngine.unwrap(guardCiphertext, guardPlaintext);
        } catch (Exception sentinel) { //NOPMD AvoidCatchingGenericException — DECRYPT_CLOSED_SENTINEL expected
            bh.consume(sentinel);
        }
    }

    // =========================================================================
    // Benchmark 3: beginHandshake() FFM call cost — SSL_connect with no peer
    // SLO: p99 ≤ 2 µs
    // =========================================================================

    /**
     * Measures the round-trip cost of one {@code SSL_connect} FFM downcall when the
     * {@code SSL*} has no BIO fd attached.
     *
     * <p>Execution path per iteration:
     * <ol>
     *   <li>{@code CLOSED.getAcquire(this)} — closed guard VarHandle read.</li>
     *   <li>{@code PHASE.getAcquire(stateMachine)} — phase guard VarHandle read (HANDSHAKE_IN_PROGRESS).</li>
     *   <li>{@code outbound.setSize(0)} — zero-cost field write on off-heap slab buffer.</li>
     *   <li>{@code cipherCtx.retainSslPointer()} — non-zero {@code long} read (CAS retain).</li>
     *   <li>{@code SSL_connect(sslPtr)} — Panama FFM {@link java.lang.invoke.MethodHandle#invokeExact}
     *       into OpenSSL C: detects missing BIO, returns {@code -1}.</li>
     *   <li>{@code SSL_get_error(sslPtr, -1)} — second FFM call to classify the failure.</li>
     *   <li>{@link TlsStateMachine} phase stays {@code HANDSHAKE_IN_PROGRESS} — no JFR event.</li>
     * </ol>
     *
     * <p>The SSL* is reused across iterations: OpenSSL keeps the broken session in a
     * consistent failed state, so each {@code SSL_connect} call returns {@code SSL_ERROR_SSL}
     * immediately without advancing the internal TLS state machine.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public eu.exeris.kernel.spi.crypto.TlsStatus beginHandshakeNoFdCost(Blackhole bh) {
        eu.exeris.kernel.spi.crypto.TlsStatus status =
                beginHandshakeEngine.beginHandshake(beginHandshakeBuf);
        bh.consume(status);
        return status;
    }

    // =========================================================================
    // Benchmark 4: bindTransportFd() downcall cost — SSL_set_fd(SSL*, int)
    // =========================================================================

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public int bindTransportFdCost(Blackhole bh) {
        int result = bindFdEngine.bindTransportFd(sslSetFdHandle, bindFd);
        bh.consume(result);
        return result;
    }

    private static int extractFd(SocketChannel channel) {
        try {
            java.lang.reflect.Method getFdVal = channel.getClass().getDeclaredMethod("getFDVal");
            getFdVal.setAccessible(true); //NOPMD AvoidAccessibilityAlteration — benchmark harness only
            return (int) getFdVal.invoke(channel);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("SocketChannel.getFDVal() unavailable", exception);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // benchmark teardown should be best-effort
        }
    }
}
