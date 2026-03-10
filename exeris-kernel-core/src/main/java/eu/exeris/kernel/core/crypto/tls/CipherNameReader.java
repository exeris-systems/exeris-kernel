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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Core: Stateless utility that reads the active TLS cipher suite name from a completed
 * {@code SSL*} session via {@code SSL_get_current_cipher} + {@code SSL_CIPHER_get_name}.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>The C string returned by {@code SSL_CIPHER_get_name} is owned by OpenSSL for the
 * library lifetime. This reader reinterprets the native pointer as a bounded
 * {@link MemorySegment} via a null-scan, copies the bytes once into a Java
 * {@link String}, then releases the view — no {@link java.nio.ByteBuffer} wrapper,
 * no intermediate {@code byte[]} beyond the final string construction.
 *
 * <h2>Failure Policy</h2>
 * <p>Any failure (null cipher pointer, null name pointer, FFM exception) returns an
 * empty string — cipher resolution MUST NEVER abort a completed handshake.
 *
 * @since 0.5.0
 */
final class CipherNameReader {

    /** Returned when no cipher can be resolved. */
    /* default */ static final String UNKNOWN = "";

    /** Null pointer sentinel — matches OpenSSL convention of returning 0 on failure. */
    private static final long NULL_POINTER = 0L;

    /**
     * Maximum safe scan length for the null-terminated cipher name C string.
     * OpenSSL cipher names are short (e.g. {@code "TLS_AES_256_GCM_SHA384"} = 21 chars).
     * 128 bytes is a generous upper bound that prevents runaway scanning.
     */
    private static final int MAX_SCAN_BYTES = 128;

    private CipherNameReader() {
        // static utility — not instantiated
    }

    /**
     * Reads the negotiated cipher suite name for the given {@code SSL*} session.
     *
     * <p>Calls {@code SSL_get_current_cipher(sslPtr)} to obtain the {@code SSL_CIPHER*},
     * then calls {@code SSL_CIPHER_get_name(cipherPtr)} to obtain the C string pointer.
     * The string is decoded as UTF-8 and returned.
     *
     * @param sslPtr    raw {@code SSL*} address (caller must hold a retain)
     * @param ioHandles pre-resolved FFM method handles
     * @return cipher suite name (e.g. {@code "TLS_AES_256_GCM_SHA384"}), or {@link #UNKNOWN}
     */
    /* default */ static String read(long sslPtr, CoreSslHandles.IoHandles ioHandles) {
        try {
            long cipherPtr = ioHandles.invokeGetCurrentCipher(sslPtr);
            if (cipherPtr == NULL_POINTER) {
                return UNKNOWN;
            }
            long namePtr = ioHandles.invokeCipherGetName(cipherPtr);
            if (namePtr == NULL_POINTER) {
                return UNKNOWN;
            }
            return readCString(namePtr);
        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — cipher name must never abort handshake
            return UNKNOWN;
        }
    }

    /**
     * Reads a null-terminated C string from the given native address.
     *
     * <p>Scans up to {@link #MAX_SCAN_BYTES} bytes for the null terminator directly
     * on the {@link MemorySegment}, then copies an exact-length slice into a
     * {@code byte[]} for {@link String} construction — one allocation instead of two.
     *
     * @param nativeAddr native address of the null-terminated C string (OpenSSL-owned)
     * @return decoded UTF-8 string, or {@link #UNKNOWN} if scanning fails or name is empty
     */
    private static String readCString(long nativeAddr) {
        MemorySegment raw = MemorySegment.ofAddress(nativeAddr).reinterpret(MAX_SCAN_BYTES);
        int length = scanForNull(raw);
        if (length < 0) {
            return UNKNOWN;
        }
        if (length == 0) {
            return UNKNOWN;
        }
        byte[] bytes = new byte[length];
        MemorySegment.copy(raw, ValueLayout.JAVA_BYTE, 0, bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Scans up to {@link #MAX_SCAN_BYTES} bytes for a {@code NUL} terminator.
     *
     * <p>The scan limit is derived at runtime from the segment's actual byte size
     * (capped at {@link #MAX_SCAN_BYTES}) so the method is also correct when a smaller
     * segment is passed by future callers.
     *
     * @param seg segment to scan (must have byte size &gt;= 1)
     * @return index of the first {@code NUL} byte, or {@code -1} if none found
     */
    private static int scanForNull(MemorySegment seg) {
        int byteIdx = 0;
        int limit   = (int) Math.min(seg.byteSize(), MAX_SCAN_BYTES);
        while (byteIdx < limit) {
            if (seg.get(ValueLayout.JAVA_BYTE, byteIdx) == 0) {
                return byteIdx;
            }
            byteIdx++;
        }
        return -1;
    }
}
