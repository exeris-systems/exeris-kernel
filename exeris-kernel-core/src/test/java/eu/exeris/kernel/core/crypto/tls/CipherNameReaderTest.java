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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit Tests: {@link CipherNameReader} — cipher suite name extraction from off-heap memory.
 *
 * <h2>Test Strategy</h2>
 * <p>All OpenSSL FFM calls are replaced with static stub {@link MethodHandle}s resolved
 * via {@link MethodHandles#lookup()}. No native library is required. Tests validate the
 * Java-layer contract of {@link CipherNameReader#read} in isolation:
 * <ul>
 *   <li>Null {@code SSL_CIPHER*} pointer (cipher not negotiated or unavailable)</li>
 *   <li>Null name pointer returned by {@code SSL_CIPHER_get_name}</li>
 *   <li>FFM exception propagated from either handle</li>
 *   <li>Successful cipher name decode for real OpenSSL cipher suite strings</li>
 *   <li>Cipher name at the scanner's 127-byte boundary (one below the 128-byte scan cap)</li>
 *   <li>{@code null} handles (absent symbol) short-circuit to {@link CipherNameReader#UNKNOWN}</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1: CipherNameReader — cipher name extraction from off-heap")
class CipherNameReaderTest {

    // =========================================================================
    // Arena — all native strings live here for test lifetime
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

    // =========================================================================
    // Infrastructure: native C-string allocation
    // =========================================================================

    /**
     * Allocates a null-terminated C string in the test arena and returns its address.
     * The extra null byte is added to satisfy the scanner's boundary check.
     */
    private long allocCString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = testArena.allocate(bytes.length + 1L);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, 0, bytes.length);
        seg.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
        return seg.address();
    }

    // =========================================================================
    // Infrastructure: IoHandles factory
    // =========================================================================

    /**
     * Builds an {@link CoreSslHandles.IoHandles} whose cipher handles return the
     * scripted {@code cipherPtr} and {@code namePtr} values, respectively.
     * All other handles in the record are {@code null} (not exercised by CipherNameReader).
     */
    private CoreSslHandles.IoHandles handlesReturning(long cipherPtr, long namePtr) {
        return new CoreSslHandles.IoHandles(
                null, null, null, null, null, null,
                buildGetCurrentCipherHandle(cipherPtr),
                buildGetNameHandle(namePtr)
        );
    }

    /**
     * Builds an {@link CoreSslHandles.IoHandles} where {@code sslGetCurrentCipher} throws.
     */
    private static CoreSslHandles.IoHandles handlesThrowingOnGetCipher() {
        MethodHandle constantName = MethodHandles.dropArguments(
                MethodHandles.constant(long.class, 0xDEADL), 0, long.class);
        return new CoreSslHandles.IoHandles(
                null, null, null, null, null, null,
                buildThrowingLongHandle("throwingGetCipher"),
                constantName
        );
    }

    /**
     * Builds an {@link CoreSslHandles.IoHandles} where {@code sslCipherGetName} throws.
     */
    private CoreSslHandles.IoHandles handlesThrowingOnGetName(long fakeCipherPtr) {
        return new CoreSslHandles.IoHandles(
                null, null, null, null, null, null,
                buildGetCurrentCipherHandle(fakeCipherPtr),
                buildThrowingLongHandle("throwingGetName")
        );
    }

    /**
     * Builds an {@link CoreSslHandles.IoHandles} with {@code null} cipher handles
     * (simulates an OpenSSL build where the symbol is absent).
     */
    private static CoreSslHandles.IoHandles handlesWithNullCipherHandles() {
        return new CoreSslHandles.IoHandles(
                null, null, null, null, null, null,
                null,  // sslGetCurrentCipher absent
                null   // sslCipherGetName absent
        );
    }

    // =========================================================================
    // Static bridge methods — MethodHandle targets
    // =========================================================================

    @SuppressWarnings("unused")
    private static long returnCipherPtr0(long sslPtr) {
        assert sslPtr >= 0 : "returnCipherPtr0:sslPtr";
        return 0L;
    }

    @SuppressWarnings("unused")
    private static long returnNamePtr0(long cipherPtr) {
        assert cipherPtr >= 0 : "returnNamePtr0:cipherPtr";
        return 0L;
    }

    @SuppressWarnings("unused")
    private static long throwingGetCipher(long sslPtr) {
        assert sslPtr >= 0 : "throwingGetCipher:sslPtr";
        throw new RuntimeException("simulated FFM failure in SSL_get_current_cipher");
    }

    @SuppressWarnings("unused")
    private static long throwingGetName(long cipherPtr) {
        assert cipherPtr >= 0 : "throwingGetName:cipherPtr";
        throw new RuntimeException("simulated FFM failure in SSL_CIPHER_get_name");
    }

    // =========================================================================
    // MethodHandle builders
    // =========================================================================

    private static MethodHandle buildGetCurrentCipherHandle(long returnValue) {
        if (returnValue == 0L) {
            return findStaticLongToLong("returnCipherPtr0");
        }
        return MethodHandles.dropArguments(
                MethodHandles.constant(long.class, returnValue), 0, long.class);
    }

    private static MethodHandle buildGetNameHandle(long returnValue) {
        if (returnValue == 0L) {
            return findStaticLongToLong("returnNamePtr0");
        }
        return MethodHandles.dropArguments(
                MethodHandles.constant(long.class, returnValue), 0, long.class);
    }

    private static MethodHandle buildThrowingLongHandle(String name) {
        return findStaticLongToLong(name);
    }

    private static MethodHandle findStaticLongToLong(String name) {
        try {
            return MethodHandles.lookup()
                    .findStatic(CipherNameReaderTest.class, name,
                            MethodType.methodType(long.class, long.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    // =========================================================================
    // Nested: Null pointer sentinels
    // =========================================================================

    @Nested
    @DisplayName("Null pointer sentinels — returns UNKNOWN without throwing")
    class NullPointerSentinels {

        @Test
        @DisplayName("SSL_get_current_cipher returns 0 → UNKNOWN (cipher not yet negotiated)")
        void nullCipherPtrReturnsUnknown() {
            CoreSslHandles.IoHandles handles = handlesReturning(0L, 0xBEEFL);
            assertThat(CipherNameReader.read(0xCAFEL, handles)).isSameAs(CipherNameReader.UNKNOWN);
        }

        @Test
        @DisplayName("SSL_CIPHER_get_name returns 0 → UNKNOWN (name unavailable)")
        void nullNamePtrReturnsUnknown() {
            long fakeCipherPtr = 0xABCDL;
            CoreSslHandles.IoHandles handles = handlesReturning(fakeCipherPtr, 0L);
            assertThat(CipherNameReader.read(0xCAFEL, handles)).isSameAs(CipherNameReader.UNKNOWN);
        }

        @Test
        @DisplayName("null sslGetCurrentCipher handle (absent symbol) → UNKNOWN")
        void nullGetCurrentCipherHandleReturnsUnknown() {
            CoreSslHandles.IoHandles handles = handlesWithNullCipherHandles();
            assertThat(CipherNameReader.read(0xCAFEL, handles)).isSameAs(CipherNameReader.UNKNOWN);
        }
    }

    // =========================================================================
    // Nested: FFM exception resilience
    // =========================================================================

    @Nested
    @DisplayName("FFM exception resilience — cipher name must never abort handshake")
    class FfmExceptionResilience {

        @Test
        @DisplayName("Exception in SSL_get_current_cipher → returns UNKNOWN, no propagation")
        void exceptionInGetCipherReturnsUnknown() {
            CoreSslHandles.IoHandles handles = handlesThrowingOnGetCipher();
            assertThat(CipherNameReader.read(0xCAFEL, handles)).isSameAs(CipherNameReader.UNKNOWN);
        }

        @Test
        @DisplayName("Exception in SSL_CIPHER_get_name → returns UNKNOWN, no propagation")
        void exceptionInGetNameReturnsUnknown() {
            long fakeCipherPtr = 0xABCDL;
            CoreSslHandles.IoHandles handles = handlesThrowingOnGetName(fakeCipherPtr);
            assertThat(CipherNameReader.read(0xCAFEL, handles)).isSameAs(CipherNameReader.UNKNOWN);
        }
    }

    // =========================================================================
    // Nested: Successful cipher name decode
    // =========================================================================

    @Nested
    @DisplayName("Successful cipher name decode")
    class SuccessfulDecode {

        @ParameterizedTest(name = "Cipher ''{0}'' decoded correctly")
        @ValueSource(strings = {
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256",
                "TLS_CHACHA20_POLY1305_SHA256",
                "ECDHE-RSA-AES256-GCM-SHA384"
        })
        @DisplayName("Real OpenSSL cipher suite names decoded to correct Java String")
        void realCipherNamesDecoded(String cipherName) {
            long namePtr = allocCString(cipherName);
            long fakeCipherPtr = 0xABCDL;
            CoreSslHandles.IoHandles handles = handlesReturning(fakeCipherPtr, namePtr);

            assertThat(CipherNameReader.read(0xCAFEL, handles)).isEqualTo(cipherName);
        }

        @Test
        @DisplayName("Single-character cipher name decoded correctly")
        void singleCharName() {
            long namePtr = allocCString("X");
            long fakeCipherPtr = 0x1L;
            CoreSslHandles.IoHandles handles = handlesReturning(fakeCipherPtr, namePtr);

            assertThat(CipherNameReader.read(0xCAFEL, handles)).isEqualTo("X");
        }

        @Test
        @DisplayName("Cipher name at MAX_SCAN_BYTES - 1 boundary (127 chars) decoded correctly")
        void maxLengthMinusOneName() {
            String name = "A".repeat(127);
            long namePtr = allocCString(name);
            long fakeCipherPtr = 0x2L;
            CoreSslHandles.IoHandles handles = handlesReturning(fakeCipherPtr, namePtr);

            assertThat(CipherNameReader.read(0xCAFEL, handles)).isEqualTo(name);
        }

        @Test
        @DisplayName("Cipher name filling MAX_SCAN_BYTES (128 chars, no null terminator) → UNKNOWN")
        void exactlyMaxScanBytesWithNoNullTerminatorReturnsUnknown() {
            byte[] bytes = "B".repeat(128).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            MemorySegment seg = testArena.allocate(bytes.length);
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, 0, bytes.length);
            long namePtr = seg.address();
            long fakeCipherPtr = 0x3L;
            CoreSslHandles.IoHandles handles = handlesReturning(fakeCipherPtr, namePtr);

            assertThat(CipherNameReader.read(0xCAFEL, handles)).isSameAs(CipherNameReader.UNKNOWN);
        }

        @Test
        @DisplayName("Empty C string (null terminator at offset 0) → UNKNOWN per failure policy")
        void emptyCStringReturnsUnknown() {
            MemorySegment seg = testArena.allocate(1L);
            seg.set(ValueLayout.JAVA_BYTE, 0, (byte) 0);
            long fakeCipherPtr = 0x4L;
            CoreSslHandles.IoHandles handles = handlesReturning(fakeCipherPtr, seg.address());

            assertThat(CipherNameReader.read(0xCAFEL, handles)).isSameAs(CipherNameReader.UNKNOWN);
        }
    }

    // =========================================================================
    // Nested: UNKNOWN constant contract
    // =========================================================================

    @Nested
    @DisplayName("UNKNOWN constant contract")
    class UnknownConstant {

        @Test
        @DisplayName("UNKNOWN is an empty string — not null")
        void unknownIsEmptyString() {
            assertThat(CipherNameReader.UNKNOWN).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("All null-pointer paths return the same UNKNOWN instance (isSameAs)")
        void allNullPathsReturnSameInstance() {
            CoreSslHandles.IoHandles nullCipherHandles  = handlesWithNullCipherHandles();
            CoreSslHandles.IoHandles zeroCipherPtr      = handlesReturning(0L, 0xBEEFL);
            CoreSslHandles.IoHandles zeroNamePtr        = handlesReturning(0xABCDL, 0L);

            String r1 = CipherNameReader.read(0xCAFEL, nullCipherHandles);
            String r2 = CipherNameReader.read(0xCAFEL, zeroCipherPtr);
            String r3 = CipherNameReader.read(0xCAFEL, zeroNamePtr);

            assertThat(r1).isSameAs(CipherNameReader.UNKNOWN);
            assertThat(r2).isSameAs(CipherNameReader.UNKNOWN);
            assertThat(r3).isSameAs(CipherNameReader.UNKNOWN);
        }
    }
}
