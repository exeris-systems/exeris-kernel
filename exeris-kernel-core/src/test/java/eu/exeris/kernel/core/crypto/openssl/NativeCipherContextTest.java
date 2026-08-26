/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.core.crypto.ArenaLoanedBuffer;
import eu.exeris.kernel.spi.exceptions.crypto.TlsException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1: {@link NativeCipherContext} hardware-sympathetic concurrency tests.
 *
 * <p>Validates that the lock-free reference counting mechanism (VarHandle)
 * strictly prevents Use-After-Free Segfaults when virtual threads concurrently
 * attempt to release/close the SSL pointer.
 *
 * <p>No Mockito. At L0, we wire up plain static {@link MethodHandle} stubs
 * (resolved via {@link MethodHandles#lookup()}) to simulate OpenSSL's
 * {@code SSL_new} / {@code SSL_free} ABI — zero framework overhead,
 * maximum JIT transparency.
 *
 * @since 0.5.0
 */
@DisplayName("L1: NativeCipherContext (Lock-Free Memory Shield)")
@SuppressWarnings("preview") // StructuredTaskScope (JEP 525) is --enable-preview in JDK 26
class NativeCipherContextTest {

    // =========================================================================
    // Dummy OpenSSL Native Targets
    // =========================================================================

    /** Monotonic allocation counter — verified in each test via assertion. */
    private static final AtomicInteger ALLOCATIONS = new AtomicInteger(0);

    /** Monotonic free counter — the key invariant: must always equal exactly 1 after close. */
    private static final AtomicInteger FREES = new AtomicInteger(0);

    /**
     * Dummy {@code SSL_new(ctxPtr)} stub.
     *
     * <p>Increments {@link #ALLOCATIONS} and returns the canonical fake C pointer
     * {@code 0xDEADBEEF} — a value distinguishable from NULL to satisfy
     * the null-pointer guard in {@link NativeCipherContext}.
     */
    @SuppressWarnings("unused") // ABI stub: parameter matches OpenSSL SSL_new(SSL_CTX*) signature
    public static long dummySslNew(long ignoredCtx) {
        assert ignoredCtx >= 0; // ABI-required parameter — formally read to satisfy static analysis
        ALLOCATIONS.incrementAndGet();
        return 0xDEADBEEFL; // Fake C pointer — distinguishable from NULL_PTR=0
    }

    /**
     * Dummy {@code SSL_free(ptr)} stub.
     *
     * <p>Increments {@link #FREES} — the key metric under test.
     * Must be called exactly once per {@link NativeCipherContext} lifetime.
     */
    @SuppressWarnings("unused") // ABI stub: parameter matches OpenSSL SSL_free(SSL*) signature
    public static void dummySslFree(long ignoredPtr) {
        assert ignoredPtr >= 0; // ABI-required parameter — formally read to satisfy static analysis
        FREES.incrementAndGet();
    }

    // =========================================================================
    // Stub MemoryAllocator for tests
    // =========================================================================

    private static final MemoryAllocator STUB_ALLOC = new MemoryAllocator() {
        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return ArenaLoanedBuffer.allocateOwning(hint, 64);
        }
        @Override public LoanedBuffer allocateNetwork(int b)        { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateCarrierSlab(int i)    { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateInfrastructure(long b){ return allocate(AllocationHint.MEDIUM); }
        @Override public MemoryStats  stats()                       { return MemoryStats.zero(); }
        @Override public void         close()                       { /* stateless test allocator — each LoanedBuffer owns its Arena */ }
    };

    // =========================================================================
    // Factory helper
    // =========================================================================

    /**
    * Assembles a {@link CoreSslHandles} wired to the local static stubs.
     *
     * <p>Only {@code sslNew} and {@code sslFree} are meaningful for lifecycle tests.
     * All other handshake slots ({@code sslAccept}, {@code sslConnect},
     * {@code sslDoHandshake}) are {@code null} — acceptable because
     * {@link NativeCipherContext} never invokes them during the operations under test.
     */
    private CoreSslHandles createDummyHandles() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle sslNew = lookup.findStatic(
                NativeCipherContextTest.class,
                "dummySslNew",
                MethodType.methodType(long.class, long.class));
        MethodHandle sslFree = lookup.findStatic(
                NativeCipherContextTest.class,
                "dummySslFree",
                MethodType.methodType(void.class, long.class));

        CoreSslHandles.HandshakeHandles handshakeHandles =
                new CoreSslHandles.HandshakeHandles(sslNew, sslFree, null, null, null);

        return CoreSslHandlesTestFactory.build(null, handshakeHandles, null);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("Normal lifecycle: close() frees the pointer exactly once")
    void normalLifecycle() throws Exception {
        FREES.set(0);
        try (NativeCipherContext ctx = new NativeCipherContext(createDummyHandles(), 0x1234L, STUB_ALLOC)) {
            assertThat(ctx.sslPointer()).isEqualTo(0xDEADBEEFL);
        }
        assertThat(FREES.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Active retention defers SSL_free until last release")
    void deferredFreeOnActiveRetention() throws Exception {
        FREES.set(0);
        NativeCipherContext ctx = new NativeCipherContext(createDummyHandles(), 0x1234L, STUB_ALLOC);

        long ptr = ctx.retainSslPointer(); // RefCount goes to 2
        assertThat(ptr).isEqualTo(0xDEADBEEFL);

        ctx.close(); // Drops base reference: RefCount 2 → 1

        // SSL_free must NOT be called yet — one virtual-thread owner still holds the pointer
        assertThat(FREES.get()).isZero();

        // Simulate I/O completion — finally block releases the retained reference
        ctx.release(); // RefCount 1 → 0 → SSL_free fires

        // Now and only now the pointer is freed
        assertThat(FREES.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent assault: 100 Virtual Threads trying to retain and release")
    void concurrentVirtualThreadAssault() throws Exception {
        FREES.set(0);
        NativeCipherContext ctx = new NativeCipherContext(createDummyHandles(), 0x1234L, STUB_ALLOC);

        // StructuredTaskScope ensures all virtual threads finish before we proceed.
        // awaitAllSuccessfulOrThrow provides fail-fast semantics: any subtask exception
        // propagates to the main thread, avoiding silent failures.
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
            for (int i = 0; i < 100; i++) {
                scope.fork(() -> {
                    long ptr = ctx.retainSslPointer(); // CAS increment: RefCount → N+1
                    assertThat(ptr).isEqualTo(0xDEADBEEFL);
                    // Simulate off-heap I/O work — yields to the scheduler to maximise
                    // interleaving pressure on the VarHandle CAS loop
                    Thread.yield();
                    ctx.release();                     // CAS decrement: RefCount → N-1
                    return null;
                });
            }
            scope.join();
        }

        // Close from the main thread — drops the base reference.
        // If any retain/release pair was asymmetric, this triggers a double-release
        // and the test fails immediately rather than causing a silent Segfault.
        ctx.close();

        // Exactly ONE SSL_free — no double-free, no leak
        assertThat(FREES.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Retaining a closed context throws IllegalStateException")
    void retainClosedThrows() throws Exception {
        NativeCipherContext ctx = new NativeCipherContext(createDummyHandles(), 0x1234L, STUB_ALLOC);
        ctx.close();

        assertThatThrownBy(ctx::retainSslPointer)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed")
                .hasMessageContaining("freed");
    }

    // =========================================================================
    // Nested: Construction Guards
    // =========================================================================

    @Nested
    @DisplayName("Construction Guards")
    class ConstructionGuards {

        @Test
        @DisplayName("SSL_new returning NULL throws TlsException — no larval leak")
        void sslNewNullThrowsTlsException() throws Exception {
            MethodHandle nullSslNew = MethodHandles.lookup().findStatic(
                    NativeCipherContextTest.class, "nullSslNew",
                    MethodType.methodType(long.class, long.class));
            MethodHandle sslFree = MethodHandles.lookup().findStatic(
                    NativeCipherContextTest.class, "dummySslFree",
                    MethodType.methodType(void.class, long.class));

            CoreSslHandles.HandshakeHandles handshakeHandles = new CoreSslHandles.HandshakeHandles(
                    nullSslNew, sslFree, null, null, null);
            CoreSslHandles handles = CoreSslHandlesTestFactory.build(null, handshakeHandles, null);

            assertThatThrownBy(() -> new NativeCipherContext(handles, 0x1234L, STUB_ALLOC))
                    .isInstanceOf(TlsException.class);
            // No try-with-resources needed: constructor throws — no instance created, no resource to close.
        }

        @Test
        @DisplayName("CryptoContextAllocEvent emitted after successful SSL_new")
        void cryptoContextAllocEventEmitted() throws Exception {
            AtomicReference<RecordedEvent> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            try (RecordingStream rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.core.crypto.ContextAlloc");
                rs.onEvent("eu.exeris.kernel.core.crypto.ContextAlloc", evt -> {
                    captured.set(evt);
                    latch.countDown();
                });
                rs.startAsync();

                try (NativeCipherContext ctx = new NativeCipherContext(createDummyHandles(), 0xABCDL, STUB_ALLOC)) {
                    assertThat(ctx.sslPointer()).isEqualTo(0xDEADBEEFL);
                }

                boolean received = latch.await(5L, TimeUnit.SECONDS);
                assertThat(received).as("JFR CryptoContextAllocEvent not received within 5s").isTrue();
            }

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().getLong("sslPtr")).isEqualTo(0xDEADBEEFL);
            assertThat(captured.get().getLong("sslCtxPtr")).isEqualTo(0xABCDL);
        }
    }

    // =========================================================================
    // Nested: Release Guards
    // =========================================================================

    @Nested
    @DisplayName("Release Guards")
    class ReleaseGuards {

        @Test
        @DisplayName("Double-release after close() throws IllegalStateException")
        void doubleReleaseAfterCloseThrows() throws Exception {
            NativeCipherContext ctx = new NativeCipherContext(createDummyHandles(), 0x1234L, STUB_ALLOC);
            ctx.close(); // refCount 1 → 0, SSL_free called
            assertThatThrownBy(ctx::release)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Double release");
        }
    }

    // =========================================================================
    // Additional ABI stubs
    // =========================================================================

    @SuppressWarnings("unused") // ABI stub: parameter matches OpenSSL SSL_new(SSL_CTX*) signature — NULL-returning variant
    public static long nullSslNew(long ignoredCtx) {
        assert ignoredCtx >= 0; // ABI-required parameter — formally read to satisfy static analysis
        return 0L; // NULL — triggers guard in NativeCipherContext constructor
    }
}
