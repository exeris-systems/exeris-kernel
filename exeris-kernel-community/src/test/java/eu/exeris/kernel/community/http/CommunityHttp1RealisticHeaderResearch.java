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
import java.util.Arrays;

/**
 * SPIKE (research/http-header-name-table) — the measurement RFC-2026-09-01 gates Option B on.
 *
 * <p>The earlier research fixture used synthetic {@code X-Request-Header-N} names, which miss a name
 * table on every field and would report Option B as worth nothing. These fixtures carry the names
 * real traffic carries, with the values real traffic carries — which cuts the other way, because a
 * long {@code Cookie} or {@code Authorization} value dilutes whatever the name half is worth.
 * Both effects are present here on purpose; only the measurement settles it.
 *
 * <p>Run this on the branch WITHOUT the table and again WITH it. The difference is the name half of
 * the per-request cost, so one experiment answers both of the RFC's open questions.
 *
 * <p>Prints a table; asserts nothing.
 */
@DisplayName("SPIKE: realistic HTTP/1 header allocation")
class CommunityHttp1RealisticHeaderResearch {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 50_000;

    @Test
    @DisplayName("bytes per request for real request shapes")
    void realisticAllocation() {
        try (MemoryAllocator allocator =
                     new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())) {
            System.out.println("=== realistic HTTP/1 read-path allocation (exact per-thread bytes) ===");
            System.out.printf("%-22s %-8s %-10s %-14s %-14s%n",
                    "fixture", "fields", "wire B", "bytes/request", "bytes/header");

            report(allocator, "browser GET", browserGet());
            report(allocator, "service POST", servicePost());
            report(allocator, "minimal GET", minimalGet());
            report(allocator, "synthetic (old)", syntheticSixteen());
        }
    }

    private static void report(MemoryAllocator allocator, String label, String request) {
        byte[] bytes = request.getBytes(StandardCharsets.ISO_8859_1);
        int fields = countFields(request);
        long perRequest = measure(allocator, bytes);
        System.out.printf("%-22s %-8d %-10d %-14d %-14.1f%n",
                label, fields, bytes.length, perRequest, perRequest / (double) fields);
    }

    private static int countFields(String request) {
        int fields = 0;
        for (String line : request.split("\r\n")) {
            if (line.isEmpty()) {
                break;
            }
            fields++;
        }
        return fields - 1; // the request-line is not a field
    }

    private static long measure(MemoryAllocator allocator, byte[] request) {
        try (LoanedBuffer buffer = allocator.allocateNetwork(Math.max(request.length, 64))) {
            MemorySegment.copy(request, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, request.length);
            buffer.setSize(request.length);
            // One codec per connection, reset per read - the production shape.
            Http1Codec codec = new Http1Codec();

            for (int i = 0; i < WARMUP; i++) {
                readOnce(codec, buffer, request.length);
            }
            long[] samples = new long[3];
            for (int window = 0; window < samples.length; window++) {
                long before = THREADS.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < MEASURED; i++) {
                    readOnce(codec, buffer, request.length);
                }
                samples[window] = (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
            }
            Arrays.sort(samples);
            return samples[1];
        }
    }

    private static void readOnce(Http1Codec codec, LoanedBuffer buffer, int total) {
        codec.reset();
        Object result = CommunityHttp1RequestReader.tryParseRequest(codec, buffer, total);
        if (result == null) {
            throw new IllegalStateException("request did not parse - the fixture is wrong");
        }
    }

    /** Chrome on Linux, first navigation to an https origin. Long values, all-known names. */
    private static String browserGet() {
        return "GET /shop/orders?page=2 HTTP/1.1\r\n"
                + "Host: shop.example.com\r\n"
                + "Connection: keep-alive\r\n"
                + "sec-ch-ua: \"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\"\r\n"
                + "Sec-CH-UA-Mobile: ?0\r\n"
                + "Sec-CH-UA-Platform: \"Linux\"\r\n"
                + "Upgrade-Insecure-Requests: 1\r\n"
                + "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/128.0.0.0 Safari/537.36\r\n"
                + "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                + "image/webp,image/apng,*/*;q=0.8\r\n"
                + "Sec-Fetch-Site: same-origin\r\n"
                + "Sec-Fetch-Mode: navigate\r\n"
                + "Sec-Fetch-User: ?1\r\n"
                + "Sec-Fetch-Dest: document\r\n"
                + "Accept-Encoding: gzip, deflate, br, zstd\r\n"
                + "Accept-Language: en-GB,en;q=0.9,pl;q=0.8\r\n"
                + "Cookie: session=8f14e45fceea167a5a36dedd4bea2543; consent=1; cart=17ab3f\r\n"
                + "\r\n";
    }

    /** Service-to-service call with a bearer token and tracing. */
    private static String servicePost() {
        return "POST /api/v1/orders HTTP/1.1\r\n"
                + "Host: orders.internal:8080\r\n"
                + "User-Agent: exeris-kernel-client/0.12.0\r\n"
                + "Accept: application/json\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: 0\r\n"
                + "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImE0In0."
                + "eyJzdWIiOiJzdmMtb3JkZXJzIiwiZXhwIjoxNzg4ODg4ODg4LCJzY29wZSI6Im9yZGVyczp3cml0ZSJ9."
                + "c2lnbmF0dXJlLXBsYWNlaG9sZGVy\r\n"
                + "Traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\n"
                + "X-Request-Id: 7c9f1e2a-0b44-4d1e-9a1f-5b2c3d4e5f60\r\n"
                + "Idempotency-Key: 9d8c7b6a-5f4e-3d2c-1b0a-9f8e7d6c5b4a\r\n"
                + "\r\n";
    }

    /** A health probe or load-balancer check — the shape a name table should flatter most. */
    private static String minimalGet() {
        return "GET /healthz/readiness HTTP/1.1\r\n"
                + "Host: orders.internal:8080\r\n"
                + "User-Agent: kube-probe/1.31\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n"
                + "\r\n";
    }

    /** The old research fixture, kept so the two measurements can be compared directly. */
    private static String syntheticSixteen() {
        StringBuilder sb = new StringBuilder("GET /api/v1/orders/12345 HTTP/1.1\r\n");
        for (int i = 0; i < 16; i++) {
            sb.append(i == 0 ? "Host: service.internal:8080\r\n"
                    : "X-Request-Header-" + i + ": value-" + i + "\r\n");
        }
        return sb.append("\r\n").toString();
    }
}
