/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Core: Stateless utility that reads the active TLS cipher suite name from a completed
 * {@code SSL*} session via {@code SSL_get_current_cipher} + {@code SSL_CIPHER_get_name}.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>The C string returned by {@code SSL_CIPHER_get_name} is owned by OpenSSL for the
 * library lifetime. This reader reinterprets the native pointer as a bounded
 * {@link MemorySegment} and calls {@link MemorySegment#getString(long, java.nio.charset.Charset)}
 * to decode the null-terminated name directly into a Java {@link String} — no intermediate
 * {@code byte[]} allocation, no {@link java.nio.ByteBuffer} wrapper. The single
 * {@link String} allocation is unavoidable on the cold path (once per completed handshake).
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
     * <p>Creates a bounded {@link MemorySegment} of {@link #MAX_SCAN_BYTES} bytes and
     * delegates to {@link MemorySegment#getString(long, java.nio.charset.Charset)} which
     * scans for the {@code NUL} terminator and constructs the {@link String} in one step,
     * without an intermediate {@code byte[]}.
     *
     * @param nativeAddr native address of the null-terminated C string (OpenSSL-owned)
     * @return decoded UTF-8 string, or {@link #UNKNOWN} if scanning fails or name is empty
     */
    private static String readCString(long nativeAddr) {
        MemorySegment raw = MemorySegment.ofAddress(nativeAddr).reinterpret(MAX_SCAN_BYTES);
        String name = raw.getString(0, StandardCharsets.UTF_8);
        return name.isEmpty() ? UNKNOWN : name;
    }
}
