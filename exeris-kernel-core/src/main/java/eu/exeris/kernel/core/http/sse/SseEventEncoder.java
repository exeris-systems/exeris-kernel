/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.sse;

import eu.exeris.kernel.spi.http.StreamEvent;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Core: serializes a {@link StreamEvent} to its Server-Sent Events wire representation
 * (WHATWG HTML §9.2 — {@code text/event-stream}).
 *
 * <h2>The Wall</h2>
 * <p>This is the tier-blind framing layer the streaming SPI relies on (ADR-043): it turns the
 * implementation-blind {@link StreamEvent} carrier into the {@code field: value\n …\n\n} byte block.
 * It owns no transport mechanics.
 *
 * <h2>Zero-copy emit path (No Waste Compute)</h2>
 * <p>The framing runs as two passes over the immutable event that share one traversal
 * ({@link #traverse}): {@link #encodedLength(StreamEvent)} measures the UTF-8 byte block and
 * {@link #encodeInto(StreamEvent, MemorySegment, long)} writes it straight into the egress
 * {@link eu.exeris.kernel.spi.memory.LoanedBuffer} — no intermediate {@code StringBuilder},
 * {@code String}, or {@code byte[]}, and no array→segment copy on the streaming hot path (the earlier
 * {@code StringBuilder → getBytes → copy} shape deviated from the {@code docs/performance-contract.md}
 * §2.2.1 0 B/req target). Because both passes drive the same {@link ByteSink} traversal, the measured
 * length and the written bytes are byte-exact by construction. Unpaired UTF-16 surrogates are emitted
 * as {@code '?'} — identical to {@link String#getBytes(java.nio.charset.Charset)} with UTF-8. The heap
 * {@link #encode(StreamEvent)} wrapper is retained for tests and non-hot-path callers.
 *
 * <p><b>v0.10 transport framing:</b> the bytes are written as a <em>raw, close-delimited</em>
 * HTTP/1.1 body (no {@code Content-Length}, no chunk framing; the response carries {@code Connection:
 * close} and the SSE session ends with the connection — RFC 9112 §6.3). {@code Transfer-Encoding: chunked}
 * per-event framing (for SSE through buffering reverse proxies) and an HTTP/2 {@code DATA}-frame path are
 * documented follow-ups; this encoder's field-block output is unchanged by either.
 *
 * <h2>Field Order &amp; Rules</h2>
 * <pre>
 *   event: &lt;event&gt;\n     (omitted when event is null/blank)
 *   id: &lt;id&gt;\n           (omitted when id is null/blank)
 *   retry: &lt;retryMillis&gt;\n (omitted when retryMillis &lt;= 0)
 *   data: &lt;line&gt;\n        (one data: line per embedded newline in the payload)
 *   \n                       (blank line terminates the event)
 * </pre>
 *
 * <p>Per the SSE spec, {@code event}/{@code id} fields cannot span lines; embedded CR/LF in those
 * fields are stripped to prevent field-injection, and U+0000 NUL is stripped (a NUL in the {@code id}
 * field makes the client ignore it — WHATWG HTML §9.2). The {@code data} payload is split on CR, LF, or
 * CRLF into one {@code data:} line each, faithfully reproducing multi-line payloads on the client.
 *
 * @since 0.10.0
 */
// The class-level cyclomatic sum reflects a UTF-8 encoder split into small, single-purpose methods
// (1/2/3/4-byte code-point branches + the field structure); it is inherent to correct encoding, not
// inflation — the same justified-noise posture HttpStreamEngine documents.
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class SseEventEncoder {

    private static final long NO_RETRY = 0L;
    private static final byte NEWLINE = (byte) '\n';
    // UTF-8's standard single-byte replacement for malformed input (an unpaired surrogate) — matches
    // the JDK UTF-8 CharsetEncoder, so this path stays byte-for-byte identical to getBytes(UTF_8).
    private static final byte REPLACEMENT = (byte) '?';
    // UTF-8 code-point band boundaries (named so the encoding branches read as intent, not magic).
    private static final int MAX_ONE_BYTE = 0x80;   // code points below this encode as 1 byte (ASCII)
    private static final int MAX_TWO_BYTE = 0x800;  // below this (and >= 0x80) encode as 2 bytes
    private static final int CONT_MASK = 0x3F;      // 6-bit payload of a UTF-8 continuation byte
    private static final int CONT_TAG = 0x80;       // 10xxxxxx continuation-byte tag
    private static final int LEAD_TAG_2 = 0xC0;     // 110xxxxx 2-byte leading-byte tag
    private static final int LEAD_TAG_3 = 0xE0;     // 1110xxxx 3-byte leading-byte tag
    private static final int LEAD_TAG_4 = 0xF0;     // 11110xxx 4-byte leading-byte tag
    private static final int SHIFT_6 = 6;
    private static final int SHIFT_12 = 12;
    private static final int SHIFT_18 = 18;
    private static final int DECIMAL_RADIX = 10;

    private SseEventEncoder() {
    }

    /**
     * Number of UTF-8 bytes {@link #encodeInto} will write for {@code event} — the measure pass the
     * streaming engine uses to size the egress buffer and charge backpressure credit before allocating.
     *
     * @param event the event to measure; must not be {@code null}
     * @return the exact SSE field-block length in bytes
     */
    public static int encodedLength(StreamEvent event) {
        CountingSink sink = new CountingSink();
        traverse(event, sink);
        return sink.count;
    }

    /**
     * Encodes {@code event}'s SSE field block straight into {@code dest} at {@code offset} (zero-copy),
     * returning the number of bytes written — always equal to {@link #encodedLength(StreamEvent)}.
     *
     * @param event  the event to encode; must not be {@code null}
     * @param dest   destination segment; needs {@link #encodedLength(StreamEvent)} bytes free at {@code offset}
     * @param offset the byte offset in {@code dest} to start writing at
     * @return the number of bytes written
     */
    public static int encodeInto(StreamEvent event, MemorySegment dest, long offset) {
        SegmentSink sink = new SegmentSink(dest, offset);
        traverse(event, sink);
        return (int) (sink.pos - offset);
    }

    /**
     * Encodes a single event to a fresh UTF-8 {@code byte[]} — a heap convenience for tests and
     * non-hot-path callers. The streaming engine uses {@link #encodedLength}/{@link #encodeInto} instead.
     *
     * @param event the event to encode; must not be {@code null}
     * @return the SSE field block as UTF-8 bytes, including the terminating blank line
     */
    public static byte[] encode(StreamEvent event) {
        byte[] out = new byte[encodedLength(event)];
        encodeInto(event, MemorySegment.ofArray(out), 0L);
        return out;
    }

    // One traversal shared by the measure and the write passes — the sole guarantee that the two agree
    // on the byte count. Only the ByteSink differs; the field structure is defined exactly once, here.
    private static void traverse(StreamEvent event, ByteSink sink) {
        singleLineField(sink, "event: ", event.event());
        singleLineField(sink, "id: ", event.id());
        if (event.retryMillis() > NO_RETRY) {
            sink.ascii("retry: ");
            sink.digits(event.retryMillis());
            sink.newline();
        }
        dataField(sink, event.data());
        sink.newline();
    }

    private static void singleLineField(ByteSink sink, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sink.ascii(label);
        // Emit maximal runs of retained chars, dropping CR/LF (cannot span lines — field-injection
        // guard) and U+0000 NUL (WHATWG HTML §9.2). A surrogate pair is never split by these ASCII
        // separators, so it always falls inside a single run and is encoded intact by ByteSink.region.
        int len = value.length();
        int runStart = 0;
        for (int idx = 0; idx < len; idx++) {
            char chr = value.charAt(idx);
            if (chr == '\r' || chr == '\n' || chr == '\0') {
                if (idx > runStart) {
                    sink.region(value, runStart, idx);
                }
                runStart = idx + 1;
            }
        }
        if (len > runStart) {
            sink.region(value, runStart, len);
        }
        sink.newline();
    }

    private static void dataField(ByteSink sink, String data) {
        // SSE splits the payload on CR, LF, or CRLF into one data: line each.
        int len = data.length();
        int lineStart = 0;
        int idx = 0;
        while (idx < len) {
            char chr = data.charAt(idx);
            if (chr == '\r' || chr == '\n') {
                sink.ascii("data: ");
                if (idx > lineStart) {
                    sink.region(data, lineStart, idx);
                }
                sink.newline();
                if (chr == '\r' && idx + 1 < len && data.charAt(idx + 1) == '\n') {
                    idx++;
                }
                idx++;
                lineStart = idx;
            } else {
                idx++;
            }
        }
        sink.ascii("data: ");
        if (len > lineStart) {
            sink.region(data, lineStart, len);
        }
        sink.newline();
    }

    private static int utf8ByteCount(int codePoint) {
        if (codePoint < MAX_ONE_BYTE) {
            return 1;
        }
        if (codePoint < MAX_TWO_BYTE) {
            return 2;
        }
        if (Character.isSurrogate((char) codePoint)) {
            return 1; // unpaired surrogate → single '?' replacement byte
        }
        if (codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
            return 3;
        }
        return 4;
    }

    // -------------------------------------------------------------------------
    // ByteSink — the measure pass counts bytes; the write pass emits them. Both drive the identical
    // traverse() above, so encodedLength() and encodeInto() can never disagree on the byte count.
    // -------------------------------------------------------------------------

    private interface ByteSink {
        /** A pure-ASCII structural literal (field label, "data: ", …) — one byte per char. */
        void ascii(String asciiLiteral);

        /** A value substring {@code [start, end)} — UTF-8 encoded (unpaired surrogate → {@code '?'}). */
        void region(String src, int start, int end);

        /** A positive decimal number (retry millis). */
        void digits(long value);

        /** The single {@code '\n'} separator/terminator byte. */
        void newline();
    }

    private static final class CountingSink implements ByteSink {
        private int count;

        @Override
        public void ascii(String asciiLiteral) {
            count += asciiLiteral.length();
        }

        @Override
        public void region(String src, int start, int end) {
            int idx = start;
            while (idx < end) {
                int codePoint = src.codePointAt(idx);
                idx += Character.charCount(codePoint);
                count += utf8ByteCount(codePoint);
            }
        }

        @Override
        public void digits(long value) {
            count += decimalDigits(value);
        }

        @Override
        public void newline() {
            count++;
        }
    }

    private static final class SegmentSink implements ByteSink {
        private final MemorySegment dest;
        private long pos;

        private SegmentSink(MemorySegment dest, long offset) {
            this.dest = dest;
            this.pos = offset;
        }

        @Override
        public void ascii(String asciiLiteral) {
            for (int idx = 0; idx < asciiLiteral.length(); idx++) {
                put((byte) asciiLiteral.charAt(idx));
            }
        }

        @Override
        public void region(String src, int start, int end) {
            int idx = start;
            while (idx < end) {
                int codePoint = src.codePointAt(idx);
                idx += Character.charCount(codePoint);
                writeCodePoint(codePoint);
            }
        }

        @Override
        public void digits(long value) {
            int width = decimalDigits(value);
            long remaining = value;
            for (int offset = width - 1; offset >= 0; offset--) {
                dest.set(ValueLayout.JAVA_BYTE, pos + offset, (byte) ('0' + (int) (remaining % DECIMAL_RADIX)));
                remaining /= DECIMAL_RADIX;
            }
            pos += width;
        }

        @Override
        public void newline() {
            put(NEWLINE);
        }

        private void writeCodePoint(int codePoint) {
            if (codePoint < MAX_ONE_BYTE) {
                put((byte) codePoint);
            } else if (codePoint < MAX_TWO_BYTE) {
                put((byte) (LEAD_TAG_2 | (codePoint >> SHIFT_6)));
                put((byte) (CONT_TAG | (codePoint & CONT_MASK)));
            } else if (Character.isSurrogate((char) codePoint)) {
                put(REPLACEMENT);
            } else if (codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
                put((byte) (LEAD_TAG_3 | (codePoint >> SHIFT_12)));
                put((byte) (CONT_TAG | ((codePoint >> SHIFT_6) & CONT_MASK)));
                put((byte) (CONT_TAG | (codePoint & CONT_MASK)));
            } else {
                put((byte) (LEAD_TAG_4 | (codePoint >> SHIFT_18)));
                put((byte) (CONT_TAG | ((codePoint >> SHIFT_12) & CONT_MASK)));
                put((byte) (CONT_TAG | ((codePoint >> SHIFT_6) & CONT_MASK)));
                put((byte) (CONT_TAG | (codePoint & CONT_MASK)));
            }
        }

        private void put(byte value) {
            dest.set(ValueLayout.JAVA_BYTE, pos, value);
            pos++;
        }
    }

    private static int decimalDigits(long value) {
        int digits = 1;
        long remaining = value;
        while (remaining >= DECIMAL_RADIX) {
            remaining /= DECIMAL_RADIX;
            digits++;
        }
        return digits;
    }
}
