/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * RESEARCH — how much the HTTP/1 header path allocates, and what the slope per header is.
 *
 * <p>Not a gate and not an assertion: this prints a table. It exists so the T2-10 decision rests on
 * a measured number instead of a counted call site, and it lives on {@code research/*} rather than
 * in the suite.
 *
 * <h2>Instrument</h2>
 * <p>{@code ThreadMXBean.getThreadAllocatedBytes} — the exact per-thread delta, not JFR's
 * {@code ObjectAllocationSample}. That event reports {@code weight}, a sampler extrapolation
 * arriving in a near-constant quantum, and reading it as a byte count is the defect the graph churn
 * TCK was built on for three releases. Exact bytes or nothing.
 *
 * <h2>Reading it</h2>
 * <p>Two request shapes at different header counts give the slope: the difference divided by the
 * header delta is the per-header cost, and the intercept is the request line plus fixed overhead.
 * A single shape would conflate the two.
 */
@DisplayName("RESEARCH: HTTP/1 parser allocation")
class Http1ParserAllocationResearch {

    private static final ThreadMXBean THREADS =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 50_000;

    @Test
    @DisplayName("bytes per parse, by header count")
    void allocationByHeaderCount() {
        System.out.println("=== HTTP/1 parse allocation (exact per-thread bytes) ===");
        System.out.printf("%-8s %-14s %-16s %-14s%n", "headers", "bytes/parse", "bytes/header", "objects/parse*");

        long previousBytes = 0;
        int previousCount = 0;
        for (int headers : new int[] {0, 4, 8, 16}) {
            long perParse = measure(request(headers));
            String slope = previousCount == 0 && headers == 0
                    ? "—"
                    : String.format("%.1f", (perParse - previousBytes) / (double) (headers - previousCount));
            System.out.printf("%-8d %-14d %-16s %-14s%n",
                    headers, perParse, headers == 0 ? "—" : slope, estimateObjects(headers));
            previousBytes = perParse;
            previousCount = headers;
        }
        System.out.println("* analytic: readAscii allocates a byte[] AND a String per token;");
        System.out.println("  3 tokens on the request line, 2 per header, plus trimOws substring when OWS is present.");
    }

    /**
     * Median of three in-process windows after warm-up.
     *
     * <p>Windows inside one JVM are ONE sample for JIT purposes — the process decides its own
     * compilation state. That is why this reports a per-run figure and the research note compares
     * across FRESH JVMs rather than treating these three windows as repetitions.
     */
    private static long measure(byte[] request) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(request.length);
            MemorySegment.copy(request, 0, seg, ValueLayout.JAVA_BYTE, 0, request.length);

            for (int i = 0; i < WARMUP; i++) {
                parseOnce(seg, request.length);
            }

            long[] samples = new long[3];
            for (int window = 0; window < samples.length; window++) {
                long before = THREADS.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < MEASURED; i++) {
                    parseOnce(seg, request.length);
                }
                samples[window] = (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
            }
            java.util.Arrays.sort(samples);
            return samples[1];
        }
    }

    private static void parseOnce(MemorySegment seg, int length) {
        Http1RequestParser.parseRequestLine(seg, 0, length);
        long headerStart = crlf(seg, length) + 2;
        Http1RequestParser.parseHeaders(seg, headerStart, length - headerStart,
                (name, value) -> {
                    // Consume both so nothing is optimised away, without allocating here: the
                    // subject is the parser's allocation, not a visitor's.
                    if (name.isEmpty() && value.isEmpty()) {
                        throw new IllegalStateException("unreachable");
                    }
                });
    }

    /** Where the request line ends — the reader computes this the same way, then adds 2. */
    private static long crlf(MemorySegment seg, long length) {
        for (long i = 0; i + 1 < length; i++) {
            if (seg.get(ValueLayout.JAVA_BYTE, i) == '\r' && seg.get(ValueLayout.JAVA_BYTE, i + 1) == '\n') {
                return i;
            }
        }
        throw new IllegalStateException("no CRLF");
    }

    private static String estimateObjects(int headers) {
        return (3 * 2) + " + " + headers + "x4..5";
    }

    private static byte[] request(int headers) {
        StringBuilder sb = new StringBuilder("GET /api/v1/orders/12345 HTTP/1.1\r\n");
        sb.append("Host: service.internal:8080\r\n");
        for (int i = 1; i < headers; i++) {
            sb.append("X-Request-Header-").append(i).append(": value-").append(i).append("\r\n");
        }
        if (headers == 0) {
            sb.setLength("GET /api/v1/orders/12345 HTTP/1.1\r\n".length());
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
