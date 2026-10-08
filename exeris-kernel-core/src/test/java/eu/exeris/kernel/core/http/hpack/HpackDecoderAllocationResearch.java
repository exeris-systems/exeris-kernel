/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.hpack;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.core.http.hpack.huffman.Huffman;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * RESEARCH — how much {@link HpackDecoder#decode} allocates on the success path of a request.
 *
 * <p>Not a gate and not an assertion: this prints a table. It exists so a change to the decoder is
 * judged against a measured number instead of a counted call site. It is tagged {@code research},
 * so no build runs it unless the {@code research} profile selects it.
 *
 * <h2>Instrument</h2>
 * <p>{@code ThreadMXBean.getThreadAllocatedBytes} — the exact per-thread delta, not JFR's
 * {@code ObjectAllocationSample}, whose {@code weight} is a sampler extrapolation arriving in a
 * near-constant quantum. Exact bytes or nothing.
 *
 * <h2>Reading it</h2>
 * <p>The block is a request as a client sends it: static-table indexed fields, a dynamic-table
 * indexed field, and literals without indexing — Huffman-coded and raw, indexed name and literal
 * name, one never-indexed. Nothing in it exceeds a bound, so the figure is the cost of the path
 * every request takes. Literals without indexing keep the dynamic table at a steady state, so each
 * decode does the same work.
 */
@DisplayName("RESEARCH: HPACK decoder allocation")
@Tag("research")
class HpackDecoderAllocationResearch {

    private static final ThreadMXBean THREADS =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 50_000;

    @Test
    @DisplayName("bytes per decode, by block shape")
    void allocationByBlockShape() {
        System.out.println("=== HPACK decode allocation, success path (exact per-thread bytes) ===");
        System.out.printf("%-34s %-8s %-14s%n", "block", "fields", "bytes/decode");
        try (Arena arena = Arena.ofConfined()) {
            report(arena, "indexed only", 3, indexedOnly());
            report(arena, "raw literals", 4, rawLiterals());
            report(arena, "Huffman literals", 4, huffmanLiterals(arena));
            report(arena, "realistic request (mixed)", 9, realisticRequest(arena));
        }
    }

    private static void report(Arena arena, String label, int fields, byte[] block) {
        System.out.printf("%-34s %-8d %-14d%n", label, fields, measure(arena, block));
    }

    /**
     * Median of three in-process windows after warm-up.
     *
     * <p>Windows inside one JVM are ONE sample for JIT purposes — the process decides its own
     * compilation state. That is why this reports a per-run figure and the comparison is made
     * across FRESH JVMs rather than treating these three windows as repetitions.
     */
    private static long measure(Arena arena, byte[] block) {
        MemorySegment seg = arena.allocate(block.length);
        MemorySegment.copy(block, 0, seg, ValueLayout.JAVA_BYTE, 0, block.length);
        HpackDecoder decoder = new HpackDecoder(
                new HpackDynamicTable(4096), new TestAllocator(arena), 65_536, 8_192);
        // Prime dynamic entry 62, referenced by the realistic block.
        byte[] prime = primingBlock();
        MemorySegment primeSeg = arena.allocate(prime.length);
        MemorySegment.copy(prime, 0, primeSeg, ValueLayout.JAVA_BYTE, 0, prime.length);
        decoder.decode(primeSeg, 0, prime.length, (n, v, s) -> { });

        for (int i = 0; i < WARMUP; i++) {
            decodeOnce(decoder, seg, block.length);
        }

        long[] samples = new long[3];
        for (int window = 0; window < samples.length; window++) {
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < MEASURED; i++) {
                decodeOnce(decoder, seg, block.length);
            }
            samples[window] = (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
        }
        java.util.Arrays.sort(samples);
        return samples[1];
    }

    private static void decodeOnce(HpackDecoder decoder, MemorySegment seg, int length) {
        decoder.decode(seg, 0, length, (name, value, sensitive) -> {
            // Consume the fields so nothing is optimised away, without allocating here: the
            // subject is the decoder's allocation, not a listener's.
            if (name.isEmpty() && value.isEmpty() && sensitive) {
                throw new IllegalStateException("unreachable");
            }
        });
    }

    private static byte[] primingBlock() {
        BlockWriter out = new BlockWriter();
        out.literalIncremental("x-session", "abcdef0123456789");
        return out.bytes();
    }

    private static byte[] indexedOnly() {
        BlockWriter out = new BlockWriter();
        out.indexed(2);
        out.indexed(6);
        out.indexed(4);
        return out.bytes();
    }

    private static byte[] rawLiterals() {
        BlockWriter out = new BlockWriter();
        out.literalIndexedName(4, 4, "/api/v1/orders/12345", false, null);
        out.literalIndexedName(4, 1, "service.internal:8080", false, null);
        out.literalNewName("x-request-id", "7f1e2d3c-4b5a-6978-8897-a6b5c4d3e2f1", false, null);
        out.literalIndexedName(4, 19, "application/json", false, null);
        return out.bytes();
    }

    private static byte[] huffmanLiterals(Arena arena) {
        BlockWriter out = new BlockWriter();
        out.literalIndexedName(4, 4, "/api/v1/orders/12345", true, arena);
        out.literalIndexedName(4, 1, "service.internal:8080", true, arena);
        out.literalNewName("x-request-id", "7f1e2d3c-4b5a-6978-8897-a6b5c4d3e2f1", true, arena);
        out.literalIndexedName(4, 19, "application/json", true, arena);
        return out.bytes();
    }

    private static byte[] realisticRequest(Arena arena) {
        BlockWriter out = new BlockWriter();
        out.indexed(2);   // :method GET
        out.indexed(6);   // :scheme http
        out.indexed(62);  // dynamic entry primed above
        out.literalIndexedName(4, 4, "/api/v1/orders/12345", true, arena);
        out.literalIndexedName(4, 1, "service.internal:8080", false, null);
        out.literalIndexedName(4, 58, "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/130.0", true, arena);
        out.literalIndexedName(4, 19, "application/json", true, arena);
        out.literalNewName("x-request-id", "7f1e2d3c-4b5a-6978-8897-a6b5c4d3e2f1", false, null);
        out.neverIndexedNewName("authorization", "Bearer eyJhbGciOiJFZERTQSJ9.e30.c2lnbmF0dXJl");
        return out.bytes();
    }

    /** Writes HPACK representations (RFC 7541 §6) with full prefix-integer rules. */
    private static final class BlockWriter {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] bytes() {
            return out.toByteArray();
        }

        void indexed(int index) {
            integer(0x80, 7, index);
        }

        void literalIncremental(String name, String value) {
            out.write(0x40);
            string(name, false, null);
            string(value, false, null);
        }

        void literalIndexedName(int prefixBits, int nameIndex, String value, boolean huffman, Arena arena) {
            integer(0x00, prefixBits, nameIndex);
            string(value, huffman, arena);
        }

        void literalNewName(String name, String value, boolean huffman, Arena arena) {
            out.write(0x00);
            string(name, false, null);
            string(value, huffman, arena);
        }

        void neverIndexedNewName(String name, String value) {
            out.write(0x10);
            string(name, false, null);
            string(value, false, null);
        }

        private void string(String text, boolean huffman, Arena arena) {
            byte[] raw = text.getBytes(StandardCharsets.UTF_8);
            if (!huffman) {
                integer(0x00, 7, raw.length);
                out.writeBytes(raw);
                return;
            }
            MemorySegment input = arena.allocate(raw.length);
            MemorySegment.copy(raw, 0, input, ValueLayout.JAVA_BYTE, 0, raw.length);
            MemorySegment output = arena.allocate(raw.length * 2L + 8);
            long length = Huffman.encode(input, output);
            integer(0x80, 7, (int) length);
            out.writeBytes(output.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE));
        }

        private void integer(int highBits, int prefixBits, int value) {
            int max = (1 << prefixBits) - 1;
            if (value < max) {
                out.write(highBits | value);
                return;
            }
            out.write(highBits | max);
            int remaining = value - max;
            while (remaining >= 0x80) {
                out.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            out.write(remaining);
        }
    }
}
