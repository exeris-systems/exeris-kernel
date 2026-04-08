/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.ArenaLoanedBuffer;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandlesTestFactory;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.TlsPhase;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link OffHeapTlsEngine} — lifecycle, guard checks, state machine
 * integration, idempotent close, hot-path sentinel.
 *
 * <h2>Strategy</h2>
 * <p>All OpenSSL FFM calls are replaced with static stub {@link MethodHandle}s
 * resolved via {@link MethodHandles#lookup()}. No native library required.
 * Tests validate Java-layer contracts only — not OpenSSL behaviour.
 *
 * @since 0.5.0
 */
@DisplayName("L1: OffHeapTlsEngine")
class OffHeapTlsEngineTest {

    // =========================================================================
    // ABI Stubs
    // =========================================================================

    private static final long FAKE_SSL_PTR = 0xCAFEBABEL;

    @SuppressWarnings("unused")
    public static long stubSslNew(long ctx)                         {
        assert ctx >= 0 : "stubSslNew:ctx";
        return FAKE_SSL_PTR;
    }
    @SuppressWarnings("unused")
    public static void stubSslFree(long ssl)                        {
        assert ssl >= 0 : "stubSslFree";
    }
    @SuppressWarnings("unused")
    public static int  stubSslAcceptOk(long ssl)                    {
        assert ssl >= 0 : "stubSslAcceptOk";
        return 1;
    }
    @SuppressWarnings("unused")
    public static int  stubSslAcceptWantRead(long ssl)              {
        assert ssl >= 0 : "stubSslAcceptWantRead";
        return -1;
    }
    @SuppressWarnings("unused")
    public static int  stubSslConnectOk(long ssl)                   {
        assert ssl >= 0 : "stubSslConnectOk";
        return 1;
    }
    @SuppressWarnings("unused")
    public static int  stubSslDoHandshake(long ssl)                 {
        assert ssl >= 0 : "stubSslDoHandshake";
        return 1;
    }
    @SuppressWarnings("unused")
    public static int  stubSslRead(long ssl, long buf, int len)     {
        assert ssl >= 0 : "stubSslRead:ssl";
        assert buf >= 0 : "stubSslRead:buf";
        assert len >= 0 : "stubSslRead:len";
        return -1;
    }
    @SuppressWarnings("unused")
    public static int  stubSslWrite(long ssl, long buf, int len)    {
        assert ssl >= 0 : "stubSslWrite:ssl";
        assert buf >= 0 : "stubSslWrite:buf";
        return len;
    }
    @SuppressWarnings("unused")
    public static int  stubSslShutdown(long ignoredSsl)             {
        assert ignoredSsl >= 0 : "stubSslShutdown";
        return 1;
    }
    @SuppressWarnings("unused")
    public static int  stubSslGetShutdown(long ignoredSsl)          {
        assert ignoredSsl >= 0 : "stubSslGetShutdown";
        return 0;
    }
    @SuppressWarnings("unused")
    public static int  stubSslGetErrorWantRead(long ignoredSsl, int ret) {
        assert ignoredSsl >= 0          : "stubSslGetErrorWantRead:ssl";
        assert ret >= Integer.MIN_VALUE : "stubSslGetErrorWantRead:ret";
        return 2;
    }
    @SuppressWarnings("unused") // ABI stub: signature matches SSL_get0_alpn_selected (ssl, dataOut, lenOut)
    public static void stubAlpnSelected(long ignoredSsl, long ignoredD, long ignoredL) {
        assert ignoredSsl >= Long.MIN_VALUE : "stubAlpnSelected:ssl";
        assert ignoredD   >= Long.MIN_VALUE : "stubAlpnSelected:out";
        assert ignoredL   >= Long.MIN_VALUE : "stubAlpnSelected:outLen";
    }
    // =========================================================================
    // Handle factory
    // =========================================================================

    private static MethodHandle mh(String name, Class<?> ret, Class<?>... params) {
        try {
            return MethodHandles.lookup().findStatic(OffHeapTlsEngineTest.class, name,
                    MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private CoreSslHandles buildHandles(String acceptStub) {
        CoreSslHandles.CtxHandles ctx = new CoreSslHandles.CtxHandles(
                null, null, null, null, null, null, null, null, null, null);
        CoreSslHandles.HandshakeHandles hs = new CoreSslHandles.HandshakeHandles(
                mh("stubSslNew",         long.class, long.class),
                mh("stubSslFree",        void.class, long.class),
                mh(acceptStub,           int.class,  long.class),
                mh("stubSslConnectOk",   int.class,  long.class),
                mh("stubSslDoHandshake", int.class,  long.class));
        CoreSslHandles.IoHandles io = new CoreSslHandles.IoHandles(
                mh("stubSslRead",             int.class,  long.class, long.class, int.class),
                mh("stubSslWrite",            int.class,  long.class, long.class, int.class),
                mh("stubSslShutdown",         int.class,  long.class),
                mh("stubSslGetShutdown",      int.class,  long.class),
                mh("stubSslGetErrorWantRead", int.class,  long.class, int.class),
                mh("stubAlpnSelected",        void.class, long.class, long.class, long.class),
                null, // sslGetCurrentCipher — not needed in unit tests
                null  // sslCipherGetName    — not needed in unit tests
        );
            return CoreSslHandlesTestFactory.build(ctx, hs, io);
    }

    // =========================================================================
    // Stub MemoryAllocator
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
        @Override public void         close()                       { /* stateless test allocator */ }
    };

    // =========================================================================
    // Fixtures
    // =========================================================================

    private LoanedBuffer outbound;

    @BeforeEach
    void setUp() {
        outbound = STUB_ALLOC.allocate(AllocationHint.MEDIUM);
    }

    @AfterEach
    void tearDown() {
        outbound.close();
    }

    private OffHeapTlsEngine serverEngine() {
        return new OffHeapTlsEngine(buildHandles("stubSslAcceptOk"), 0x1234L, true, STUB_ALLOC);
    }

    // =========================================================================
    // Nested: Construction
    // =========================================================================

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("New engine starts in UNINITIALIZED phase")
        void newEngineIsUninitialized() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                assertThat(engine.phase()).isEqualTo(TlsPhase.UNINITIALIZED);
            }
        }

        @Test
        @DisplayName("isHandshakeComplete() is false before bind")
        void handshakeNotCompleteBeforeBind() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                assertThat(engine.isHandshakeComplete()).isFalse();
            }
        }

        @Test
        @DisplayName("negotiatedProtocol() is null before handshake")
        void negotiatedProtocolNullBeforeHandshake() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                assertThat(engine.negotiatedProtocol()).isNull();
            }
        }

        @Test
        @DisplayName("sslPointerForDiagnostics() returns non-zero SSL* after construction")
        void returnsNonZeroPointer() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                assertThat(engine.sslPointerForDiagnostics()).isEqualTo(FAKE_SSL_PTR);
            }
        }
    }

    // =========================================================================
    // Nested: notifyBound
    // =========================================================================

    @Nested
    @DisplayName("notifyBound()")
    class NotifyBound {

        @Test
        @DisplayName("notifyBound() transitions UNINITIALIZED → HANDSHAKE_IN_PROGRESS")
        void notifyBoundTransitionsState() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                engine.notifyBound();
                assertThat(engine.phase()).isEqualTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
            }
        }

        @Test
        @DisplayName("notifyBound() twice throws TlsHandshakeException")
        void notifyBoundTwiceThrows() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                engine.notifyBound();
                assertThatThrownBy(engine::notifyBound)
                        .isInstanceOf(TlsHandshakeException.class);
            }
        }

        @Test
        @DisplayName("beginHandshake() before notifyBound() throws TlsHandshakeException")
        void beginHandshakeBeforeNotifyBoundThrows() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                assertThatThrownBy(() -> engine.beginHandshake(outbound))
                        .isInstanceOf(TlsHandshakeException.class);
            }
        }
    }

    // =========================================================================
    // Nested: beginHandshake
    // =========================================================================

    @Nested
    @DisplayName("beginHandshake()")
    class Handshake {

        @Test
        @DisplayName("SSL_accept returns 1 → FINISHED, phase ACTIVE")
        void successfulAcceptReturnsFinished() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                try (OffHeapTlsEngine engine = serverEngine()) {
                    engine.notifyBound();
                    TlsStatus status = engine.beginHandshake(outbound);
                    assertThat(status).isEqualTo(TlsStatus.FINISHED);
                    assertThat(engine.phase()).isEqualTo(TlsPhase.ACTIVE);
                    assertThat(engine.isHandshakeComplete()).isTrue();
                }
            });
        }

        @Test
        @DisplayName("SSL_accept returns -1 / WANT_READ → NEED_UNWRAP")
        void wantReadReturnsNeedUnwrap() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                try (OffHeapTlsEngine engine = new OffHeapTlsEngine(
                        buildHandles("stubSslAcceptWantRead"), 0x1234L, true, STUB_ALLOC)) {
                    engine.notifyBound();
                    TlsStatus status = engine.beginHandshake(outbound);
                    assertThat(status).isEqualTo(TlsStatus.NEED_UNWRAP);
                    assertThat(engine.phase()).isEqualTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
                }
            });
        }

        @Test
        @DisplayName("beginHandshake() called before notifyBound() throws TlsHandshakeException")
        void handshakeBeforeNotifyBoundThrows() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                assertThatThrownBy(() -> engine.beginHandshake(outbound))
                        .isInstanceOf(TlsHandshakeException.class);
            }
        }

        @Test
        @DisplayName("beginHandshake() on ERROR phase throws")
        void handshakeAfterErrorThrows() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                try (OffHeapTlsEngine engine = serverEngine()) {
                    engine.notifyBound();
                    // Force ERROR by calling notifyBound() again — illegal transition
                    try { engine.notifyBound(); } catch (TlsHandshakeException _) { /* expected */ }
                    assertThatThrownBy(() -> engine.beginHandshake(outbound))
                            .isInstanceOf(RuntimeException.class);
                }
            });
        }
    }

    // =========================================================================
    // Nested: wrap / unwrap hot-path guards
    // =========================================================================

    @Nested
    @DisplayName("wrap() / unwrap() — phase guards")
    class IoGuards {

        @Test
        @DisplayName("wrap() before handshake throws TlsHandshakeException (EX-NET-2001)")
        void wrapBeforeHandshakeThrows() {
            try (OffHeapTlsEngine engine = serverEngine();
                 LoanedBuffer plain = STUB_ALLOC.allocate(AllocationHint.SMALL);
                 LoanedBuffer cipher = STUB_ALLOC.allocate(AllocationHint.MEDIUM)) {
                assertThatThrownBy(() -> engine.wrap(plain, cipher))
                        .isInstanceOf(TlsHandshakeException.class);
            }
        }

        @Test
        @DisplayName("unwrap() before handshake throws TlsDecryptException (EX-NET-2003)")
        void unwrapBeforeHandshakeThrows() {
            try (OffHeapTlsEngine engine = serverEngine();
                 LoanedBuffer cipher = STUB_ALLOC.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer plain = STUB_ALLOC.allocate(AllocationHint.MEDIUM)) {
                assertThatThrownBy(() -> engine.unwrap(cipher, plain))
                        .isInstanceOf(TlsDecryptException.class);
            }
        }

        @Test
        @DisplayName("wrap() after close() throws TlsHandshakeException (EX-NET-2001)")
        void wrapAfterCloseThrows() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                try (LoanedBuffer plain = STUB_ALLOC.allocate(AllocationHint.SMALL);
                     LoanedBuffer cipher = STUB_ALLOC.allocate(AllocationHint.MEDIUM)) {
                    OffHeapTlsEngine engine = serverEngine();
                    engine.notifyBound();
                    engine.beginHandshake(outbound);
                    engine.close();
                    assertThatThrownBy(() -> engine.wrap(plain, cipher))
                            .isInstanceOf(TlsHandshakeException.class);
                }
            });
        }

        @Test
        @DisplayName("unwrap() after close() throws TlsDecryptException (EX-NET-2003)")
        void unwrapAfterCloseThrows() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                try (LoanedBuffer cipher = STUB_ALLOC.allocate(AllocationHint.MEDIUM);
                     LoanedBuffer plain = STUB_ALLOC.allocate(AllocationHint.MEDIUM)) {
                    OffHeapTlsEngine engine = serverEngine();
                    engine.notifyBound();
                    engine.beginHandshake(outbound);
                    engine.close();
                    assertThatThrownBy(() -> engine.unwrap(cipher, plain))
                            .isInstanceOf(TlsDecryptException.class);
                }
            });
        }

        @Test
        @DisplayName("wrap() in ACTIVE phase returns OK for positive SSL_write")
        void wrapInActiveReturnsOk() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                try (OffHeapTlsEngine engine = serverEngine();
                     LoanedBuffer plain = STUB_ALLOC.allocate(AllocationHint.SMALL);
                     LoanedBuffer cipher = STUB_ALLOC.allocate(AllocationHint.MEDIUM)) {
                    engine.notifyBound();
                    engine.beginHandshake(outbound);
                    plain.segment().fill((byte) 0xAB);
                    plain.setSize(plain.capacity());
                    assertThat(engine.wrap(plain, cipher)).isEqualTo(TlsStatus.OK);
                }
            });
        }
    }

    // =========================================================================
    // Nested: initiateShutdown
    // =========================================================================

    @Nested
    @DisplayName("initiateShutdown()")
    class Shutdown {

        @Test
        @DisplayName("SSL_shutdown returns 1 → SHUTDOWN_COMPLETE")
        void shutdownCompletesGracefully() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                try (OffHeapTlsEngine engine = serverEngine()) {
                    engine.notifyBound();
                    engine.beginHandshake(outbound);
                    engine.initiateShutdown(outbound);
                    assertThat(engine.phase()).isEqualTo(TlsPhase.SHUTDOWN_COMPLETE);
                }
            });
        }

        @Test
        @DisplayName("initiateShutdown() before ACTIVE is a no-op")
        void shutdownBeforeActiveIsNoop() {
            try (OffHeapTlsEngine engine = serverEngine()) {
                assertThatCode(() -> engine.initiateShutdown(outbound))
                        .doesNotThrowAnyException();
                assertThat(engine.phase()).isEqualTo(TlsPhase.UNINITIALIZED);
            }
        }
    }

    // =========================================================================
    // Nested: close() idempotency
    // =========================================================================

    @Nested
    @DisplayName("close() — idempotency")
    class CloseIdempotency {

        @Test
        @DisplayName("double-close() does not throw")
        void doubleCloseIsSafe() {
            OffHeapTlsEngine engine = serverEngine();
            engine.close();
            assertThatCode(engine::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() after full lifecycle does not throw — engine is in terminal phase")
        void closeAfterFullLifecycle() {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOC).run(() -> {
                OffHeapTlsEngine engine = serverEngine();
                engine.notifyBound();
                engine.beginHandshake(outbound);
                engine.initiateShutdown(outbound);
                engine.close();
                assertThat(engine.phase())
                        .as("After graceful shutdown + close(), engine must be in CLOSED")
                        .isEqualTo(TlsPhase.CLOSED);
            });
        }
    }
}
