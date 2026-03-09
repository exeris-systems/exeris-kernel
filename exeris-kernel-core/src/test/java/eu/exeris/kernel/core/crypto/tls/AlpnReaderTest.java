/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * {@link PrefilledAllocator} — an allocator whose scratch buffer is pre-populated
 * with the scripted {@code dataAddr} and {@code alpnLen} values <em>before</em>
 * AlpnReader tries to read them. The ALPN MethodHandle is a no-op that does nothing
 * (simulating a handle that has already written into the segment, which is true
 * because we pre-fill).
 *
 * <p>This approach avoids the fragility of writing to raw {@code long} addresses
 * (ZGC colored pointers, restricted native access scoping).
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
            this.arena = Arena.ofShared(); // CHECKSTYLE:OFF — test harness arena only
            this.seg   = arena.allocate(bytes);
            setSize(bytes);
        }

        @Override protected MemorySegment backingSegment() { return seg; }
        @Override protected void onRelease()               { arena.close(); }
    }

    // =========================================================================
    // Infrastructure: SimpleAllocator (for NONE sentinel tests — scratch is clean)
    // =========================================================================

    /**
     * Standard allocator — scratch segment starts zeroed.
     * Used for NONE-sentinel tests where dataAddr=0 and alpnLen=0 is the expected state.
     */
    private static final class SimpleAllocator implements MemoryAllocator {
        @Override public LoanedBuffer allocate(AllocationHint hint) {
            return new TestLoanedBuffer(Math.max(hint.sizeBytes(), 12L));
        }
        @Override public LoanedBuffer allocateNetwork(int n) { return new TestLoanedBuffer(n); }
        @Override public LoanedBuffer allocateCarrierSlab(int i) { return new TestLoanedBuffer(12); }
        @Override public LoanedBuffer allocateInfrastructure(long b) { return new TestLoanedBuffer(b); }
        @Override public MemoryStats stats()  { return null; }
        @Override public void close()         { /* empty — test stub */ }
    }

    // =========================================================================
    // Infrastructure: PrefilledAllocator
    // =========================================================================

    /**
     * Allocator that pre-fills the scratch segment with scripted {@code dataAddr}
     * and {@code alpnLen} values. AlpnReader will read these values after the
     * ALPN no-op handle "returns" — simulating what OpenSSL would have written.
     *
     * <p>Layout (mirrors AlpnReader internal scratch layout):
     * <pre>
     *   [0..7]  → dataAddr (long)
     *   [8..11] → alpnLen  (int)
     * </pre>
     */
    private static final class PrefilledAllocator implements MemoryAllocator {
        private final long scriptedDataAddr;
        private final int  scriptedAlpnLen;

        PrefilledAllocator(long scriptedDataAddr, int scriptedAlpnLen) {
            this.scriptedDataAddr = scriptedDataAddr;
            this.scriptedAlpnLen  = scriptedAlpnLen;
        }

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            TestLoanedBuffer buf = new TestLoanedBuffer(Math.max(hint.sizeBytes(), 12L));
            // Pre-fill scratch to match what SSL_get0_alpn_selected would have written
            buf.segment().set(JAVA_LONG, 0,           scriptedDataAddr);
            buf.segment().set(JAVA_INT,  Long.BYTES,  scriptedAlpnLen);
            return buf;
        }
        @Override public LoanedBuffer allocateNetwork(int n)        { return allocate(AllocationHint.MICRO); }
        @Override public LoanedBuffer allocateCarrierSlab(int i)    { return allocate(AllocationHint.MICRO); }
        @Override public LoanedBuffer allocateInfrastructure(long b){ return allocate(AllocationHint.MICRO); }
        @Override public MemoryStats stats()                        { return null; }
        @Override public void close()                               { /* empty — test stub */ }
    }

    // =========================================================================
    // Infrastructure: no-op and throwing IoHandles
    // =========================================================================

    /** Returns an {@link CoreSslHandles.IoHandles} where the ALPN handle is a no-op. */
    private static CoreSslHandles.IoHandles noopIoHandles() {
        return new CoreSslHandles.IoHandles(
                buildNoop3Handle(),
                buildNoop3Handle(),
                buildNoop1Handle(),
                buildNoop1Handle(),
                buildNoop2Handle(),
                buildVoidHandle("noopAlpn"),
                null, // sslGetCurrentCipher — not needed for ALPN tests
                null  // sslCipherGetName    — not needed for ALPN tests
        );
    }

    /** Returns an {@link CoreSslHandles.IoHandles} where the ALPN handle throws. */
    private static CoreSslHandles.IoHandles throwingIoHandles() {
        return new CoreSslHandles.IoHandles(
                buildNoop3Handle(),
                buildNoop3Handle(),
                buildNoop1Handle(),
                buildNoop1Handle(),
                buildNoop2Handle(),
                buildVoidHandle("throwingAlpn"),
                null, // sslGetCurrentCipher — not needed for ALPN tests
                null  // sslCipherGetName    — not needed for ALPN tests
        );
    }

    // =========================================================================
    // Static bridge methods — no raw address writes needed
    // =========================================================================

    @SuppressWarnings("unused")
    private static void noopAlpn(long sslPtr, long dataPtrAddr, long lenPtrAddr) {
        // no-op — scratch is pre-filled by PrefilledAllocator before AlpnReader reads it
    }

    @SuppressWarnings("unused")
    private static void throwingAlpn(long sslPtr, long dataPtrAddr, long lenPtrAddr) {
        throw new RuntimeException("simulated FFM failure");
    }

    @SuppressWarnings("unused") private static int noop3(long a, long b, int c)  { return 0; }
    @SuppressWarnings("unused") private static int noop1(long a)                 { return 0; }
    @SuppressWarnings("unused") private static int noop2(long a, int b)          { return 0; }

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

    // =========================================================================
    // Fixtures
    // =========================================================================

    private Arena testArena;

    @BeforeEach
    void setUp() {
        testArena = Arena.ofShared(); // CHECKSTYLE:OFF — test harness arena
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
        @DisplayName("dataAddr=0 (scratch pre-filled with 0) → returns NONE sentinel")
        void zeroDataAddrReturnsNone() {
            // PrefilledAllocator with dataAddr=0 → AlpnReader sees dataAddr==0 → NONE
            MemoryAllocator allocator = new PrefilledAllocator(0L, 2);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("alpnLen=0 → returns NONE sentinel")
        void zeroLenReturnsNone() {
            long fakeAddr = allocAlpnString("h2");
            MemoryAllocator allocator = new PrefilledAllocator(fakeAddr, 0);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("alpnLen < 0 → returns NONE sentinel")
        void negativeLenReturnsNone() {
            long fakeAddr = allocAlpnString("h2");
            MemoryAllocator allocator = new PrefilledAllocator(fakeAddr, -1);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("alpnLen > 255 (RFC 7301 violation) → returns NONE sentinel")
        void tooLongReturnsNone() {
            long fakeAddr = allocAlpnString("x");
            MemoryAllocator allocator = new PrefilledAllocator(fakeAddr, 256);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result).isSameAs(AlpnReader.NONE);
        }

        @Test
        @DisplayName("FFM exception in invokeGetAlpnSelected → returns NONE, no crash")
        void ffmExceptionReturnsNone() {
            MemoryAllocator allocator = new SimpleAllocator();
            String result = AlpnReader.read(0L, throwingIoHandles(), allocator);
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
            long addr = allocAlpnString(protocol);
            MemoryAllocator allocator = new PrefilledAllocator(addr, protocol.length());
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result).isEqualTo(protocol);
        }

        @Test
        @DisplayName("Max length (255 bytes) ALPN string → correctly decoded")
        void maxLengthProtocol() {
            String protocol = "a".repeat(255);
            long addr = allocAlpnString(protocol);
            MemoryAllocator allocator = new PrefilledAllocator(addr, 255);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
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
            long addr = allocAlpnString("h2");
            MemoryAllocator allocator = new PrefilledAllocator(addr, 2);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            // isSameAs verifies reference identity — proves zero allocation on fast path
            assertThat(result).isSameAs(AlpnReader.ALPN_H2);
        }

        @Test
        @DisplayName("'http/1.1' → returns pre-interned ALPN_HTTP_1_1 constant (isSameAs)")
        void http11ReturnsSameInstance() {
            long addr = allocAlpnString("http/1.1");
            MemoryAllocator allocator = new PrefilledAllocator(addr, 8);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result).isSameAs(AlpnReader.ALPN_HTTP_1_1);
        }

        @Test
        @DisplayName("'h3' → returns pre-interned ALPN_H3 constant (isSameAs)")
        void h3ReturnsSameInstance() {
            long addr = allocAlpnString("h3");
            MemoryAllocator allocator = new PrefilledAllocator(addr, 2);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result).isSameAs(AlpnReader.ALPN_H3);
        }

        @Test
        @DisplayName("'x' (unknown) → falls through to slow path, returns correct value")
        void unknownProtocolSlowPath() {
            long addr = allocAlpnString("x");
            MemoryAllocator allocator = new PrefilledAllocator(addr, 1);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
            assertThat(result)
                    .isEqualTo("x")
                    .isNotSameAs(AlpnReader.ALPN_H2)
                    .isNotSameAs(AlpnReader.ALPN_HTTP_1_1)
                    .isNotSameAs(AlpnReader.ALPN_H3);
        }

        @Test
        @DisplayName("'h2x' — same length as 'h2' but different bytes → slow path, correct value")
        void h2LengthButDifferentBytes() {
            long addr = allocAlpnString("h3"); // length 2, same as h2 — but bytes differ
            MemoryAllocator allocator = new PrefilledAllocator(addr, 2);
            String result = AlpnReader.read(0L, noopIoHandles(), allocator);
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
