/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http1.Http1Codec;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * RESEARCH — what one HTTP/1 request actually costs on the read path.
 *
 * <p>Companion to {@code Http1ParserAllocationResearch}, which measures a SINGLE parser pass. This
 * measures {@code tryParseRequest}, which is what a request really goes through, and the difference
 * between the two is the point: the reader runs the header parse <b>twice</b> — once through the
 * codec for connection state and h2c detection, once again to build the {@code List<HttpHeader>} —
 * and then copies the list a third time.
 *
 * <p>Prints a table; asserts nothing. Lives on {@code research/*}.
 */
@DisplayName("RESEARCH: HTTP/1 read-path allocation")
class CommunityHttp1ReadAllocationResearch {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 50_000;

    @Test
    @DisplayName("bytes per request through tryParseRequest, by header count")
    void readPathAllocation() {
        try (MemoryAllocator allocator =
                     new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())) {
            System.out.println("=== HTTP/1 read path allocation (exact per-thread bytes) ===");
            System.out.printf("%-8s %-16s %-16s%n", "headers", "bytes/request", "bytes/header");

            long previous = 0;
            int previousCount = 0;
            for (int headers : new int[] {0, 4, 8, 16}) {
                long perRequest = measure(allocator, request(headers));
                String slope = headers == 0 ? "—"
                        : String.format("%.1f", (perRequest - previous) / (double) (headers - previousCount));
                System.out.printf("%-8d %-16d %-16s%n", headers, perRequest, slope);
                previous = perRequest;
                previousCount = headers;
            }
        }
    }

    private static long measure(MemoryAllocator allocator, byte[] request) {
        try (LoanedBuffer buffer = allocator.allocateNetwork(Math.max(request.length, 64))) {
            MemorySegment.copy(request, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, request.length);
            buffer.setSize(request.length);

            for (int i = 0; i < WARMUP; i++) {
                readOnce(buffer, request.length);
            }
            long[] samples = new long[3];
            for (int window = 0; window < samples.length; window++) {
                long before = THREADS.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < MEASURED; i++) {
                    readOnce(buffer, request.length);
                }
                samples[window] = (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
            }
            java.util.Arrays.sort(samples);
            return samples[1];
        }
    }

    /**
     * A fresh codec per request, which is what the reader gets per connection-state reset — using
     * one across iterations would let h2c/keep-alive state accumulate and measure something else.
     */
    private static void readOnce(LoanedBuffer buffer, int total) {
        Object result = CommunityHttp1RequestReader.tryParseRequest(
                new Http1Codec(), buffer, total);
        if (result == null) {
            throw new IllegalStateException("request did not parse — the fixture is wrong");
        }
    }

    private static byte[] request(int headers) {
        StringBuilder sb = new StringBuilder("GET /api/v1/orders/12345 HTTP/1.1\r\n");
        for (int i = 0; i < headers; i++) {
            sb.append(i == 0 ? "Host: service.internal:8080\r\n"
                    : "X-Request-Header-" + i + ": value-" + i + "\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
