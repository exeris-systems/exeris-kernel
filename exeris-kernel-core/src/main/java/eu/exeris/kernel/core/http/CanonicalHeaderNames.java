/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves an HTTP field-name straight from wire bytes to a shared constant, allocating nothing when
 * the spelling is one it knows.
 *
 * <h2>Why this exists</h2>
 * <p>Field names are drawn from a small, highly repetitive set; field values are not. That asymmetry
 * is what HPACK's static table is built on, and it is what makes a name table worth having here:
 * materialising a name costs a temporary {@code byte[]} plus a {@code String} that copies it again,
 * and there is no public API that builds a {@code String} from a {@link MemorySegment} range without
 * both. The only way below that floor is to not build one.
 *
 * <p>Measured on the HTTP/1 read path (RFC-2026-09-01): <b>~90 B saved per resolved name</b>, which
 * is 21–25% of the whole request across browser, service and health-probe shapes.
 *
 * <h2>Correctness-neutral by construction</h2>
 * <p>Matching is byte-wise and <b>case-sensitive</b>, so a hit returns exactly the characters the
 * wire carried — no field silently changes case. A miss costs a bounded comparison and the caller
 * materialises the name as before. A table that is stale, incomplete, or wrong about which spellings
 * are common is therefore a performance question and never a correctness one.
 *
 * <p>A hit also means the bytes equal a known field-name, so the caller may skip token validation:
 * every entry is a valid RFC 9110 token by construction, pinned by {@code CanonicalHeaderNamesTest}.
 *
 * <h2>Both spellings, deliberately</h2>
 * <p>Every entry is registered in its conventional spelling <em>and</em> in lowercase. HTTP/1 clients
 * conventionally send title case, but HTTP/2 <b>requires</b> lowercase field names (RFC 9113 §8.2.1)
 * and current browsers already send some HTTP/1 fields lowercase — {@code sec-ch-ua} is one, and it
 * missed a title-case-only table during the spike that justified this class. Carrying both costs
 * nothing at lookup time, because entries are bucketed by length.
 *
 * <h2>Thread safety</h2>
 * <p>Thread-safe. Immutable state, pure lookup.
 *
 * @since 0.12
 */
public final class CanonicalHeaderNames {

    /**
     * Conventional spellings only — the lowercase form of each is derived below. Ordering is
     * irrelevant to lookup; entries are grouped by length at class initialisation.
     */
    private static final String[] CONVENTIONAL = {
        // RFC 9110 request fields
        "Host", "User-Agent", "Accept", "Accept-Encoding", "Accept-Language", "Accept-Charset",
        "Connection", "Content-Type", "Content-Length", "Content-Encoding", "Authorization",
        "Cookie", "Cache-Control", "Pragma", "Referer", "Origin", "Expect", "From",
        // The CHECKSTYLE pause covers the banned-`Date` rule, which fires on HTTP's own field name
        // (RFC 9110 §6.6.1); no java.util type is involved. Same reason CommunityS3Signer pauses it.
        //CHECKSTYLE:OFF
        "Date",
        //CHECKSTYLE:ON
        "If-Match", "If-None-Match", "If-Modified-Since", "If-Unmodified-Since", "If-Range",
        "Range", "TE", "Trailer", "Transfer-Encoding", "Upgrade", "Via", "Max-Forwards",
        "Proxy-Authorization",
        // RFC 9110 response fields — the client decoder reads these
        "Server", "Location", "ETag", "Last-Modified", "Expires", "Age", "Vary", "Allow",
        "Retry-After", "Set-Cookie", "WWW-Authenticate", "Content-Disposition", "Content-Language",
        "Content-Range", "Accept-Ranges",
        // ubiquitous in browser traffic
        "Upgrade-Insecure-Requests", "Sec-Fetch-Dest", "Sec-Fetch-Mode", "Sec-Fetch-Site",
        "Sec-Fetch-User", "Sec-CH-UA", "Sec-CH-UA-Mobile", "Sec-CH-UA-Platform", "DNT", "Priority",
        "Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Accept",
        // ubiquitous in service-to-service traffic
        "X-Requested-With", "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Real-IP",
        "X-Request-Id", "X-Correlation-Id", "X-Api-Key", "X-Idempotency-Key", "Idempotency-Key",
        "Traceparent", "Tracestate", "Baggage", "HTTP2-Settings",
    };

    /** Candidate name bytes, bucketed by length so a lookup only sees same-length entries. */
    private static final byte[][][] BYTES_BY_LENGTH;

    /** The constant to return, parallel to {@link #BYTES_BY_LENGTH}. */
    private static final String[][] NAMES_BY_LENGTH;

    private static final int MAX_LENGTH;

    /**
     * RFC 9110 §5.6.2 tchar, as a lookup rather than a chain of range tests — the set is a constant,
     * so the table is the honest way to write it and the branch-free way to evaluate it. Tokens are
     * ASCII by definition, so anything at or above {@code 0x80} is not one.
     */
    private static final boolean[] TOKEN_CHARS = new boolean[128];

    static {
        for (char letter = 'a'; letter <= 'z'; letter++) {
            TOKEN_CHARS[letter] = true;
        }
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            TOKEN_CHARS[letter] = true;
        }
        for (char digit = '0'; digit <= '9'; digit++) {
            TOKEN_CHARS[digit] = true;
        }
        for (char symbol : "!#$%&'*+-.^_`|~".toCharArray()) {
            TOKEN_CHARS[symbol] = true;
        }
    }

    static {
        Set<String> spellings = new LinkedHashSet<>();
        for (String name : CONVENTIONAL) {
            spellings.add(name);
            spellings.add(name.toLowerCase(Locale.ROOT));
        }

        int longest = 0;
        for (String name : spellings) {
            longest = Math.max(longest, name.length());
        }
        MAX_LENGTH = longest;

        int[] counts = new int[longest + 1];
        for (String name : spellings) {
            counts[name.length()]++;
        }
        BYTES_BY_LENGTH = new byte[longest + 1][][];
        NAMES_BY_LENGTH = new String[longest + 1][];
        for (int length = 0; length <= longest; length++) {
            BYTES_BY_LENGTH[length] = new byte[counts[length]][];
            NAMES_BY_LENGTH[length] = new String[counts[length]];
        }

        int[] filled = new int[longest + 1];
        for (String name : spellings) {
            int length = name.length();
            int slot = filled[length];
            filled[length] = slot + 1;
            BYTES_BY_LENGTH[length][slot] = name.getBytes(StandardCharsets.ISO_8859_1);
            NAMES_BY_LENGTH[length][slot] = name;
        }
    }

    private CanonicalHeaderNames() {
    }

    /**
     * Resolves the field-name occupying {@code [start, end)} to a shared constant.
     *
     * @param seg   segment holding the field-name bytes
     * @param start inclusive start offset of the name
     * @param end   exclusive end offset of the name
     * @return the shared constant equal to those bytes, or {@code null} if this is not a spelling
     *         the table knows — in which case the caller materialises the name itself
     */
    public static String resolve(MemorySegment seg, long start, long end) {
        long span = end - start;
        if (span <= 0 || span > MAX_LENGTH) {
            return null;
        }
        int length = (int) span;
        byte[][] candidates = BYTES_BY_LENGTH[length];
        byte first = seg.get(ValueLayout.JAVA_BYTE, start);
        for (int index = 0; index < candidates.length; index++) {
            byte[] candidate = candidates[index];
            if (candidate[0] == first && tailMatches(seg, start, candidate, length)) {
                return NAMES_BY_LENGTH[length][index];
            }
        }
        return null;
    }

    /**
     * Whether {@code candidate} is a valid field-name — an RFC 9110 §5.6.2 token, non-empty.
     *
     * <p>Lives here rather than in a protocol parser because a field name is a field name on every
     * HTTP version, and because the table's entries have to satisfy it: a caller may skip this check
     * on a {@link #resolve} hit only if every entry passes, which {@code CanonicalHeaderNamesTest}
     * asserts against this method rather than against a copy of it.
     *
     * @param candidate the materialised field-name
     * @return {@code true} if every character is a token character and the name is non-empty
     */
    public static boolean isValidFieldName(String candidate) {
        int length = candidate.length();
        if (length == 0) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (!isTchar(candidate.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTchar(char candidate) {
        return candidate < TOKEN_CHARS.length && TOKEN_CHARS[candidate];
    }

    private static boolean tailMatches(MemorySegment seg, long start, byte[] candidate, int length) {
        for (int offset = 1; offset < length; offset++) {
            if (seg.get(ValueLayout.JAVA_BYTE, start + offset) != candidate[offset]) {
                return false;
            }
        }
        return true;
    }
}
