/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.memory.AbstractLoanedBuffer;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit Tests: {@link AlpnReader} — ALPN protocol string extraction from off-heap memory.
 *
 * <h2>Test Strategy</h2>
 * <p>AlpnReader reads from a scratch LoanedBuffer that is filled by the
 * {@code SSL_get0_alpn_selected} MethodHandle. We simulate this by providing a
 * {@code scriptedAlpn} bridge method — a static method acting as a synthetic ALPN handle
 * that writes {@code dataAddr} and {@code alpnLen} through the provided pointer
 * arguments, exactly as OpenSSL does. The first {@code long} argument mirrors the
 * FFM signature ({@code SSL*} pointer) and is intentionally ignored by the bridge.
 * This accurately tests the zeroing-before-invoke contract introduced to prevent
 * JVM crashes when the symbol is absent and the backing segment contains
 * uninitialized memory.
 *
 * @since 0.5.0
 */
@DisplayName("L1: AlpnReader — ALPN extraction from off-heap")
class AlpnReaderTest {

    // =========================================================================
    // Infrastructure: TestLoanedBuffer
    // =========================================================================

    private static final class TestLoanedBuffer extends AbstractLoanedBuffer {
        private final MemorySegment seg;
        private final Arena arena;

        TestLoanedBuffer(long bytes) {
            super();
            this.arena = Arena.ofShared(); //NOPMD DirectArena — test harness only
            this.seg   = arena.allocate(bytes);
            setSize(bytes);
        }

        @Override protected MemorySegment backingSegment() { return seg; }
        @Override protected void onRelease()               { arena.close(); }
    }

    // =========================================================================
    // Infrastructure: SimpleAllocator (scratch starts zeroed — matches
    // deferred-zeroing guarantee after AlpnReader's explicit zero-fill)
    // =========================================================================

    private static final class SimpleAllocator implements MemoryAllocator {
        @Override public LoanedBuffer allocate(AllocationHint hint) {
            return new TestLoanedBuffer(Math.max(hint.sizeBytes(), 12L));
        }
        @Override public LoanedBuffer allocateNetwork(int n) { return new TestLoanedBuffer(n); }
        @Override public LoanedBuffer allocateCarrierSlab(int i) { return new TestLoanedBuffer(12); }
        @Override public LoanedBuffer allocateInfrastructure(long b) { return new TestLoanedBuffer(b); }
        @Override public MemoryStats stats()  { return MemoryStats.zero(); }
        @Override public void close()         { /* empty — test stub */ }
    }

    // =========================================================================
    // Infrastructure: ScriptedAlpnHandle
    // =========================================================================

    /**
     * Static state used to script what the synthetic ALPN handle writes through
     * the {@code dataPtrAddr} and {@code lenPtrAddr} pointer arguments.
     *
     * <p>Tests set {@link #scriptedDataAddr} and {@link #scriptedAlpnLen} before
     * calling {@link AlpnReader#read}. The static bridge method {@link #scriptedAlpn}
     * receives the pointer addresses from AlpnReader and writes through them —
     * exactly as {@code SSL_get0_alpn_selected} does in native code.
     *
     * <p>Single-threaded test execution guarantees no race on these fields.
     */
    private static long scriptedDataAddr;
    private static int  scriptedAlpnLen;

    @SuppressWarnings("unused")
    private static void scriptedAlpn(long ignoredSslPtr, long dataPtrAddr, long lenPtrAddr) {
        assert ignoredSslPtr >= 0; // signature required by FFM bridge; value unused
        MemorySegment.ofAddress(dataPtrAddr).reinterpret(Long.BYTES)
                .set(JAVA_LONG, 0, scriptedDataAddr);
        MemorySegment.ofAddress(lenPtrAddr).reinterpret(Integer.BYTES)
                .set(JAVA_INT, 0, scriptedAlpnLen);
    }

    @SuppressWarnings("unused")
    private static void throwingAlpn(long ignoredSslPtr, long dataPtrAddr, long lenPtrAddr) {
        assert ignoredSslPtr >= 0; // signature required by FFM bridge; value unused
        assert dataPtrAddr >= 0;
        assert lenPtrAddr >= 0;
        throw new RuntimeException("simulated FFM failure");
    }

    @SuppressWarnings("unused") private static int noop3(long a, long b, int c) { assert a >= 0 && b >= 0 && c >= 0; return 0; }
    @SuppressWarnings("unused") private static int noop1(long a)                 { assert a >= 0; return 0; }
    @SuppressWarnings("unused") private static int noop2(long a, int b)          { assert a >= 0 && b >= 0; return 0; }

    private static MethodHandle buildVoidHandle(String name) {
        try {
            return MethodHandles.lookup()
                    .findStatic(AlpnReaderTest.class, name,
                            MethodType.methodType(void.class, long.class, long.class, long.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static MethodHandle buildNoop3Handle() {
        try {
            return MethodHandles.lookup()
                    .findStatic(AlpnReaderTest.class, "noop3",
                            MethodType.methodType(int.class, long.class, long.class, int.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static MethodHandle buildNoop1Handle() {
        try {
            return MethodHandles.lookup()
                    .findStatic(AlpnReaderTest.class, "noop1",
                            MethodType.methodType(int.class, long.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static MethodHandle buildNoop2Handle() {
        try {
            return MethodHandles.lookup()
                    .findStatic(AlpnReaderTest.class, "noop2",
                            MethodType.methodType(int.class, long.class, int.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /** IoHandles whose ALPN bridge writes scripted values through the pointer args. */
    private static CoreSslHandles.IoHandles scriptedIoHandles() {
        return new CoreSslHandles.IoHandles(
                buildNoop3Handle(),
                buildNoop3Handle(),
                buildNoop1Handle(),
                buildNoop1Handle(),
                buildNoop2Handle(),
                buildVoidHandle("scriptedAlpn"),
                null,
                null
        );
    }

    /** IoHandles whose ALPN bridge throws — simulates FFM failure. */
    private static CoreSslHandles.IoHandles throwingIoHandles() {
        return new CoreSslHandles.IoHandles(
                buildNoop3Handle(),
                buildNoop3Handle(),
                buildNoop1Handle(),
                buildNoop1Handle(),
                buildNoop2Handle(),
                buildVoidHandle("throwingAlpn"),
                null,
                null
        );
    }

    /** IoHandles with a null ALPN handle — simulates symbol absent from OpenSSL build. */
    private static CoreSslHandles.IoHandles nullAlpnIoHandles() {
        return new CoreSslHandles.IoHandles(
                buildNoop3Handle(),
                buildNoop3Handle(),
                buildNoop1Handle(),
                buildNoop1Handle(),
                buildNoop2Handle(),
                null,
                null,
                null
        );
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private Arena testArena;

    @BeforeEach
    void setUp() {
        testArena = Arena.ofShared(); //NOPMD DirectArena — test harness only
    }

    @AfterEach
    void tearDown() {
        testArena.close();
    }

    private long allocAlpnString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = testArena.allocate(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, 0, bytes.length);
        return seg.address();
    }

    // =========================================================================
    // Nested: NONE sentinel cases
    // =========================================================================

    @Nested
    @DisplayName("NONE sentinel — no ALPN negotiated")
    class NoneSentinel {

        @Test
        @DisplayName("null ALPN handle (symbol absent) → scratch zeroed → returns NONE sentinel")
        void nullHandleReturnsNone() {
            String result = AlpnReader.read(0L, nullAlpnIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("dataAddr=0 written by handle → returns NONE sentinel")
        void zeroDataAddrReturnsNone() {
            scriptedDataAddr = 0L;
            scriptedAlpnLen  = 2;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("alpnLen=0 written by handle → returns NONE sentinel")
        void zeroLenReturnsNone() {
            scriptedDataAddr = allocAlpnString("h2");
            scriptedAlpnLen  = 0;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("alpnLen < 0 written by handle → returns NONE sentinel")
        void negativeLenReturnsNone() {
            scriptedDataAddr = allocAlpnString("h2");
            scriptedAlpnLen  = -1;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("alpnLen > 255 (RFC 7301 violation) → returns NONE sentinel")
        void tooLongReturnsNone() {
            scriptedDataAddr = allocAlpnString("x");
            scriptedAlpnLen  = 256;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("FFM exception in invokeGetAlpnSelected → returns NONE, no crash")
        void ffmExceptionReturnsNone() {
            String result = AlpnReader.read(0L, throwingIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.NONE);
        }
    }

    // =========================================================================
    // Nested: Successful ALPN extraction
    // =========================================================================

    @Nested
    @DisplayName("Successful ALPN extraction")
    class SuccessfulExtraction {

        @ParameterizedTest(name = "Protocol ''{0}'' decoded correctly")
        @ValueSource(strings = {"h2", "http/1.1", "x"})
        @DisplayName("Standard ALPN protocol names decoded to correct Java String")
        void standardProtocolsDecoded(String protocol) {
            scriptedDataAddr = allocAlpnString(protocol);
            scriptedAlpnLen  = protocol.length();
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isEqualTo(protocol);
        }

        @Test
        @DisplayName("Max length (255 bytes) ALPN string → correctly decoded")
        void maxLengthProtocol() {
            String protocol = "a".repeat(255);
            scriptedDataAddr = allocAlpnString(protocol);
            scriptedAlpnLen  = 255;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isEqualTo(protocol);
        }
    }

    // =========================================================================
    // Nested: Flyweight Dictionary — zero-allocation fast path
    // =========================================================================

    /**
     * Verifies the Flyweight Dictionary contract: standard protocols ({@code h2},
     * {@code http/1.1}, {@code h3}) must return the <em>pre-interned</em> String
     * constant — i.e. {@code result == AlpnReader.ALPN_H2} (reference identity,
     * not just equality). This is the TCK zero-allocation guarantee: no new
     * {@code String} or {@code byte[]} is created on the hot path.
     */
    @Nested
    @DisplayName("Flyweight Dictionary — zero-allocation fast path (reference identity)")
    class FlyweightDictionary {

        @Test
        @DisplayName("'h2' → returns pre-interned ALPN_H2 constant (isSameAs, no new String)")
        void h2ReturnsSameInstance() {
            scriptedDataAddr = allocAlpnString("h2");
            scriptedAlpnLen  = 2;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.ALPN_H2);
        }

        @Test
        @DisplayName("'http/1.1' → returns pre-interned ALPN_HTTP_1_1 constant (isSameAs)")
        void http11ReturnsSameInstance() {
            scriptedDataAddr = allocAlpnString("http/1.1");
            scriptedAlpnLen  = 8;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.ALPN_HTTP_1_1);
        }

        @Test
        @DisplayName("'h3' → returns pre-interned ALPN_H3 constant (isSameAs)")
        void h3ReturnsSameInstance() {
            scriptedDataAddr = allocAlpnString("h3");
            scriptedAlpnLen  = 2;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result).isSameAs(AlpnReader.ALPN_H3);
        }

        @Test
        @DisplayName("'x' (unknown) → falls through to slow path, returns correct value")
        void unknownProtocolSlowPath() {
            scriptedDataAddr = allocAlpnString("x");
            scriptedAlpnLen  = 1;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result)
                    .isEqualTo("x")
                    .isNotSameAs(AlpnReader.ALPN_H2)
                    .isNotSameAs(AlpnReader.ALPN_HTTP_1_1)
                    .isNotSameAs(AlpnReader.ALPN_H3);
        }

        @Test
        @DisplayName("'h3' — same length as 'h2' but different bytes → slow path, correct value")
        void h2LengthButDifferentBytes() {
            scriptedDataAddr = allocAlpnString("h3");
            scriptedAlpnLen  = 2;
            String result = AlpnReader.read(0L, scriptedIoHandles(), new SimpleAllocator());
            assertThat(result)
                    .isNotSameAs(AlpnReader.ALPN_H2)
                    .isEqualTo("h3");
        }
    }

    // =========================================================================
    // Nested: NONE constant
    // =========================================================================

    @Nested
    @DisplayName("NONE constant semantics")
    class NoneConstant {

        @Test
        @DisplayName("NONE is an empty string — not null")
        void noneIsEmptyString() {
            assertThat(AlpnReader.NONE).isNotNull().isEmpty();
        }
    }
}
