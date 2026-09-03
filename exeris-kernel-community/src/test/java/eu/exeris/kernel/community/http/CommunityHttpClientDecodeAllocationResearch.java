/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * RESEARCH — what one HTTP/1 response costs the client on the way in.
 *
 * <p>Measures the pair the engine's read loop actually runs per response: {@code
 * resolveExpectedTotal}, which the loop consults to learn when to stop reading, and {@code
 * decodeResponse}, which turns the aggregate into an {@link HttpResponse}. Until v0.12 both built a
 * full header list, so every name and value of every response was materialised twice.
 *
 * <p>Prints a table; asserts nothing.
 */
@DisplayName("RESEARCH: HTTP/1 client response-decode allocation")
class CommunityHttpClientDecodeAllocationResearch {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 50_000;

    @Test
    @DisplayName("bytes per response for real response shapes")
    void decodeAllocation() {
        try (MemoryAllocator allocator =
                     new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())) {
            System.out.println("=== HTTP/1 client decode allocation (exact per-thread bytes) ===");
            System.out.printf("%-22s %-8s %-10s %-14s %-14s%n",
                    "fixture", "fields", "wire B", "bytes/response", "bytes/header");

            report(allocator, "API JSON 200", apiJson());
            report(allocator, "page 200 + cookies", pageWithCookies());
            report(allocator, "204 No Content", noContent());
        }
    }

    private static void report(MemoryAllocator allocator, String label, String response) {
        byte[] bytes = response.getBytes(StandardCharsets.ISO_8859_1);
        int fields = countFields(response);
        long perResponse = measure(allocator, bytes);
        System.out.printf("%-22s %-8d %-10d %-14d %-14.1f%n",
                label, fields, bytes.length, perResponse, perResponse / (double) fields);
    }

    private static int countFields(String response) {
        int fields = 0;
        for (String line : response.split("\r\n")) {
            if (line.isEmpty()) {
                break;
            }
            fields++;
        }
        return fields - 1; // the status line is not a field
    }

    private static long measure(MemoryAllocator allocator, byte[] response) {
        try (LoanedBuffer buffer = allocator.allocateNetwork(Math.max(response.length, 64))) {
            MemorySegment.copy(response, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, response.length);
            buffer.setSize(response.length);

            for (int i = 0; i < WARMUP; i++) {
                decodeOnce(allocator, buffer, response.length);
            }
            long[] samples = new long[3];
            for (int window = 0; window < samples.length; window++) {
                long before = THREADS.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < MEASURED; i++) {
                    decodeOnce(allocator, buffer, response.length);
                }
                samples[window] = (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
            }
            Arrays.sort(samples);
            return samples[1];
        }
    }

    /**
     * Both halves, in the order the read loop runs them. {@code resolveExpectedTotal} is handed
     * {@code -1} because that is its state on the read that first completes the header block — the
     * one read on which it does its work.
     */
    private static void decodeOnce(MemoryAllocator allocator, LoanedBuffer buffer, int total) {
        long terminator = CommunityHttpClientResponseDecoder.resolveHeaderTerminator(
                -1, buffer.segment(), total);
        long expected = CommunityHttpClientResponseDecoder.resolveExpectedTotal(
                -1, buffer.segment(), total, terminator, false);
        if (expected < 0) {
            throw new IllegalStateException("fixture did not frame - expected total unresolved");
        }
        HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                allocator, buffer, total, HttpVersion.HTTP_1_1, false);
        if (response.body() != null) {
            response.body().close();
        }
    }

    private static String apiJson() {
        String body = "{\"id\":12345,\"status\":\"CONFIRMED\",\"total\":\"49.90\"}";
        return "HTTP/1.1 200 OK\r\n"
                + "Date: Tue, 01 Sep 2026 18:22:31 GMT\r\n"
                + "Server: exeris-kernel/0.12.0\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Request-Id: 7c9f1e2a-0b44-4d1e-9a1f-5b2c3d4e5f60\r\n"
                + "Traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\n"
                + "\r\n"
                + body;
    }

    private static String pageWithCookies() {
        String body = "<!doctype html><title>Orders</title>";
        return "HTTP/1.1 200 OK\r\n"
                + "Date: Tue, 01 Sep 2026 18:22:31 GMT\r\n"
                + "Server: exeris-kernel/0.12.0\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Set-Cookie: session=8f14e45fceea167a5a36dedd4bea2543; Path=/; HttpOnly; Secure\r\n"
                + "Cache-Control: private, max-age=0, must-revalidate\r\n"
                + "ETag: \"a3f1c9e2b4\"\r\n"
                + "Vary: Accept-Encoding, Cookie\r\n"
                + "Content-Encoding: gzip\r\n"
                + "Last-Modified: Mon, 31 Aug 2026 09:14:02 GMT\r\n"
                + "\r\n"
                + body;
    }

    private static String noContent() {
        return "HTTP/1.1 204 No Content\r\n"
                + "Date: Tue, 01 Sep 2026 18:22:31 GMT\r\n"
                + "Server: exeris-kernel/0.12.0\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n";
    }
}
