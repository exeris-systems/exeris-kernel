/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Core: Stateless utility that reads the ALPN protocol string negotiated by OpenSSL
 * via {@code SSL_get0_alpn_selected} and decodes it to a Java {@link String}.
 *
 * <h2>Extraction Rationale</h2>
 * <p>This logic was extracted from {@link OffHeapTlsEngine} to
 * reduce the parent class's {@code CyclomaticComplexity} and {@code TooManyMethods}
 * PMD scores. The reader is stateless — it carries no lifecycle and allocates only
 * the single {@link LoanedBuffer} scratch slot supplied by the caller.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>The 12-byte scratch buffer is allocated from the provided {@link MemoryAllocator}
 * (backed by an off-heap ScopedValue slab) and released before this method returns.
 * The only heap allocation on this path is the final {@link String} result — which is
 * cached by the caller for the engine's lifetime, so it occurs at most once per session.
 *
 * <h2>RFC 7301 §3.1</h2>
 * <p>ALPN protocol names are 1–255 bytes of UTF-8. A {@code data} pointer of zero or
 * a length outside {@code [1, 255]} means no ALPN was negotiated; the method returns
 * an empty string in that case.
 *
 * @since 0.5
 */
final class AlpnReader {
    /**
     * Empty-string sentinel: "no ALPN negotiated".
     */
    /* default */ static final String NONE = "";

    // =========================================================================
    // Zero-Allocation Flyweight Dictionary for standard ALPN protocols
    // =========================================================================
    // Pre-allocated String constants and their off-heap MemorySegment mirrors.
    // On the hot path, decodeAlpn() compares the native ALPN bytes against these
    // segments using MemorySegment.mismatch() — C2 compiles this to SIMD vector
    // instructions (VPCMPEQB / VMOVDQU on x86, NEON on AArch64). For any of the
    // three standard protocols the comparison is O(1) and produces zero heap
    // allocations: the pre-interned String constant is returned directly.
    //
    // Slow path (unknown protocol) still allocates — but that path is never
    // exercised by the TCK zero-alloc workload (h2 / http/1.1 / h3 only).

    /* default */ static final String ALPN_H2       = "h2";
    /* default */ static final String ALPN_HTTP_1_1 = "http/1.1";
    /* default */ static final String ALPN_H3       = "h3";

    /**
     * Maximum ALPN protocol string length per RFC 7301 §3.1.
     */
    private static final int MAX_LEN = 255;


    // MemorySegment.ofArray() wraps the heap byte[] — no native allocation.
    // These segments live for the JVM lifetime alongside their String twins.
    private static final MemorySegment SEG_H2 =
            MemorySegment.ofArray(ALPN_H2.getBytes(StandardCharsets.UTF_8));
    private static final MemorySegment SEG_HTTP_1_1 =
            MemorySegment.ofArray(ALPN_HTTP_1_1.getBytes(StandardCharsets.UTF_8));
    private static final MemorySegment SEG_H3 =
            MemorySegment.ofArray(ALPN_H3.getBytes(StandardCharsets.UTF_8));

    private AlpnReader() {
        // static utility — not instantiated
    }

    /**
     * Reads the ALPN protocol string for the given {@code SSL*} session.
     *
     * <p>Calls {@code SSL_get0_alpn_selected(ssl, &data, &len)} via the pre-resolved
     * {@link CoreSslHandles.IoHandles} handle. The {@code data} pointer is owned by
     * OpenSSL and must not be freed by the caller.
     *
     * @param sslPtr    raw {@code SSL*} address (caller must hold a retain)
     * @param ioHandles pre-resolved FFM method handles for I/O operations
     * @param allocator {@link MemoryAllocator} used for the 12-byte scratch segment;
     *                  typically resolved from {@link eu.exeris.kernel.spi.context.KernelProviders#MEMORY_ALLOCATOR}
     * @return negotiated protocol string (e.g. {@code "h2"}), or {@link #NONE} if not negotiated
     */
    /* default */ static String read(long sslPtr,
                                     CoreSslHandles.IoHandles ioHandles,
                                     MemoryAllocator allocator) {
        try (LoanedBuffer scratch = allocator.allocate(AllocationHint.MICRO)) {
            MemorySegment seg = scratch.segment();

            // Scratch layout (12 bytes):
            //   [0..7]  → *data  (pointer written by SSL_get0_alpn_selected)
            //   [8..11] → *len   (int    written by SSL_get0_alpn_selected)
            //
            // MemoryAllocator may use deferred-zeroing; explicit zeroing here
            // guarantees deterministic NONE return when the symbol is absent
            // (invokeGetAlpnSelected is a no-op when sslGet0AlpnSelected == null).
            seg.set(JAVA_LONG, 0, 0L);
            seg.set(JAVA_INT, Long.BYTES, 0);

            long dataPtrAddr = seg.address();
            long lenPtrAddr = seg.address() + Long.BYTES;

            ioHandles.invokeGetAlpnSelected(sslPtr, dataPtrAddr, lenPtrAddr);

            long dataAddr = seg.get(JAVA_LONG, 0);
            int alpnLen = seg.get(JAVA_INT, Long.BYTES);

            if (dataAddr == 0L || alpnLen <= 0 || alpnLen > MAX_LEN) {
                return NONE;
            }

            return decodeAlpn(dataAddr, alpnLen);

        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — ALPN read must never abort a completed handshake
            return NONE;
        }
    }

    /**
     * Decodes the ALPN protocol bytes at {@code dataAddr} into a Java {@link String}.
     *
     * <h2>Flyweight Dictionary — Zero-Allocation Fast Path</h2>
     * <p>ALPN in production is a closed set of at most a handful of well-known identifiers
     * ({@code "h2"}, {@code "http/1.1"}, {@code "h3"}). For each, a pre-allocated
     * {@link MemorySegment} mirror and a pre-interned {@link String} constant are held
     * as {@code private static final} fields. On the hot path:
     * <ol>
     *   <li>A length guard eliminates mismatches in O(1) with no memory access.</li>
     *   <li>{@link MemorySegment#mismatch(MemorySegment)} performs the byte comparison.
     *       C2 compiles this to SIMD vector instructions (VPCMPEQB on x86-64,
     *       NEON on AArch64) — effectively a single hardware instruction for strings
     *       ≤ 16 bytes. Returns {@code -1L} on full match.</li>
     *   <li>The pre-interned {@link String} constant is returned directly —
     *       <strong>zero heap allocations</strong>.</li>
     * </ol>
     *
     * <h2>Why NOT {@code getString()}</h2>
     * <p>{@link MemorySegment#getString(long, java.nio.charset.Charset)} scans for a
     * C-string null-terminator ({@code \0}). ALPN names per RFC 7301 §3.1 are
     * <em>length-prefixed</em> and not null-terminated; calling {@code getString()}
     * walks past the segment boundary and throws {@code IndexOutOfBoundsException}.
     *
     * <h2>Slow Path</h2>
     * <p>Unknown protocols (non-standard extensions, test probes) fall through to a
     * {@code toArray} + {@code new String} allocation. This path is never reached by the
     * TCK zero-alloc workload (which uses {@code h2} / {@code http/1.1} exclusively).
     *
     * @param dataAddr native address of the ALPN bytes (OpenSSL-owned, do not free)
     * @param alpnLen  number of bytes (validated to be in {@code [1, 255]})
     * @return pre-interned protocol constant, or a freshly allocated {@link String}
     *         for unknown protocols
     */
    private static String decodeAlpn(long dataAddr, int alpnLen) {
        MemorySegment alpnSeg = MemorySegment.ofAddress(dataAddr).reinterpret(alpnLen);

        // ── FAST PATH: Zero-Allocation dictionary lookup ──────────────────────
        // Length guard is a single integer comparison — no memory access.
        // mismatch() → -1L means full byte-for-byte equality.
        if (alpnLen == (int) SEG_H2.byteSize() && alpnSeg.mismatch(SEG_H2) == -1L) {
            return ALPN_H2;
        }
        if (alpnLen == (int) SEG_HTTP_1_1.byteSize() && alpnSeg.mismatch(SEG_HTTP_1_1) == -1L) {
            return ALPN_HTTP_1_1;
        }
        if (alpnLen == (int) SEG_H3.byteSize() && alpnSeg.mismatch(SEG_H3) == -1L) {
            return ALPN_H3;
        }

        // ── SLOW PATH: Unknown / non-standard protocol ────────────────────────
        // Allocates byte[] and String. Safe: TCK zero-alloc workload never reaches here.
        byte[] bytes = alpnSeg.toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
