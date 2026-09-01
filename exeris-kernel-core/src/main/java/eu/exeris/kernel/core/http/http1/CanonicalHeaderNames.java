/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * SPIKE (research/http-header-name-table) — Option B of RFC-2026-09-01.
 *
 * <p>Resolves a header field-name straight from the wire bytes to a shared constant, allocating
 * nothing on a hit. Header names are drawn from a small, highly repetitive set; values are not.
 * This is the same observation HPACK's static table is built on.
 *
 * <p><b>Correctness-neutral by construction.</b> The match is byte-wise and case-SENSITIVE, so a hit
 * returns exactly the characters the wire carried — no header changes case. A miss costs a bounded
 * comparison and falls back to materialising the name, which is what happens today for every field.
 * A stale or incomplete table is therefore a performance question, never a correctness one.
 */
final class CanonicalHeaderNames {

    /** Bucketed by length, so a lookup compares only same-length candidates. */
    private static final byte[][][] BY_LENGTH;
    private static final String[][] NAMES_BY_LENGTH;
    private static final int MAX_LENGTH;

    private static final String[] KNOWN = {
        // RFC 9110 core request fields
        "Host", "User-Agent", "Accept", "Accept-Encoding", "Accept-Language", "Accept-Charset",
        "Connection", "Content-Type", "Content-Length", "Content-Encoding", "Authorization",
        "Cookie", "Cache-Control", "Pragma", "Referer", "Origin", "Date", "Expect", "From",
        "If-Match", "If-None-Match", "If-Modified-Since", "If-Unmodified-Since", "Range", "TE",
        "Trailer", "Transfer-Encoding", "Upgrade", "Via", "Warning", "Max-Forwards",
        // ubiquitous in browser traffic
        "Upgrade-Insecure-Requests", "Sec-Fetch-Dest", "Sec-Fetch-Mode", "Sec-Fetch-Site",
        "Sec-Fetch-User", "Sec-CH-UA", "Sec-CH-UA-Mobile", "Sec-CH-UA-Platform", "DNT", "Priority",
        "Accept-Datetime", "Sec-WebSocket-Key", "Sec-WebSocket-Version",
        // ubiquitous in service-to-service traffic
        "X-Requested-With", "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Real-IP",
        "X-Request-Id", "X-Correlation-Id", "X-Api-Key", "X-Idempotency-Key", "Idempotency-Key",
        "Traceparent", "Tracestate", "Baggage", "B3", "HTTP2-Settings",
    };

    /**
     * Both spellings of every entry. HTTP/1 clients send title case, but HTTP/2 REQUIRES lowercase
     * field names (RFC 9113 §8.2.1) and current browsers already send some h1 fields lowercase —
     * the spike fixture's {@code sec-ch-ua} is one, and it missed a title-case-only table.
     */
    private static final String[] SPELLINGS;

    static {
        java.util.LinkedHashSet<String> spellings = new java.util.LinkedHashSet<>();
        for (String name : KNOWN) {
            spellings.add(name);
            spellings.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        SPELLINGS = spellings.toArray(new String[0]);
        int max = 0;
        for (String name : SPELLINGS) {
            max = Math.max(max, name.length());
        }
        MAX_LENGTH = max;
        int[] counts = new int[max + 1];
        for (String name : SPELLINGS) {
            counts[name.length()]++;
        }
        BY_LENGTH = new byte[max + 1][][];
        NAMES_BY_LENGTH = new String[max + 1][];
        for (int len = 0; len <= max; len++) {
            BY_LENGTH[len] = new byte[counts[len]][];
            NAMES_BY_LENGTH[len] = new String[counts[len]];
        }
        int[] next = new int[max + 1];
        for (String name : SPELLINGS) {
            int len = name.length();
            int slot = next[len]++;
            BY_LENGTH[len][slot] = name.getBytes(StandardCharsets.ISO_8859_1);
            NAMES_BY_LENGTH[len][slot] = name;
        }
    }

    private CanonicalHeaderNames() {
    }

    /**
     * @return the shared constant for this field-name, or {@code null} if it is not a known
     *         spelling — in which case the caller materialises it as before
     */
    static String resolve(MemorySegment seg, long start, long end) {
        long span = end - start;
        if (span <= 0 || span > MAX_LENGTH) {
            return null;
        }
        int len = (int) span;
        byte[][] candidates = BY_LENGTH[len];
        if (candidates.length == 0) {
            return null;
        }
        byte first = seg.get(ValueLayout.JAVA_BYTE, start);
        for (int index = 0; index < candidates.length; index++) {
            byte[] candidate = candidates[index];
            if (candidate[0] != first) {
                continue;
            }
            if (matches(seg, start, candidate, len)) {
                return NAMES_BY_LENGTH[len][index];
            }
        }
        return null;
    }

    private static boolean matches(MemorySegment seg, long start, byte[] candidate, int len) {
        for (int offset = 1; offset < len; offset++) {
            if (seg.get(ValueLayout.JAVA_BYTE, start + offset) != candidate[offset]) {
                return false;
            }
        }
        return true;
    }
}
