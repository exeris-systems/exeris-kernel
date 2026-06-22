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
import java.nio.charset.StandardCharsets;

/**
 * Core: serializes a {@link StreamEvent} to its Server-Sent Events wire representation
 * (WHATWG HTML §9.2 — {@code text/event-stream}).
 *
 * <h2>The Wall</h2>
 * <p>This is the tier-blind framing layer the streaming SPI relies on (ADR-043): it turns the
 * implementation-blind {@link StreamEvent} carrier into the {@code field: value\n …\n\n} byte block.
 * It owns no transport mechanics — the resulting bytes are wrapped by the HTTP/1.1 chunked encoder
 * ({@link eu.exeris.kernel.core.http.http1.Http1ChunkedEncoder}) or an HTTP/2 {@code DATA} frame by the
 * transport tier, which copies them once into the egress
 * {@link eu.exeris.kernel.spi.memory.LoanedBuffer}.
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
 * fields are stripped to prevent field-injection. The {@code data} payload is split on CR, LF, or CRLF
 * into one {@code data:} line each, faithfully reproducing multi-line payloads on the client.
 *
 * @since 0.10.0
 */
public final class SseEventEncoder {

    private static final long NO_RETRY = 0L;
    private static final int INITIAL_CAPACITY = 64;

    private SseEventEncoder() {
    }

    /**
     * Encodes a single event to its UTF-8 SSE wire bytes, including the terminating blank line.
     *
     * @param event the event to encode; must not be {@code null}
     * @return the SSE field block as UTF-8 bytes; the transport copies this once into the egress buffer
     */
    public static byte[] encode(StreamEvent event) {
        StringBuilder out = new StringBuilder(INITIAL_CAPACITY);
        appendSingleLineField(out, "event", event.event());
        appendSingleLineField(out, "id", event.id());
        if (event.retryMillis() > NO_RETRY) {
            out.append("retry: ").append(event.retryMillis()).append('\n');
        }
        appendData(out, event.data());
        out.append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendSingleLineField(StringBuilder out, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.append(field).append(": ");
        for (int idx = 0; idx < value.length(); idx++) {
            char chr = value.charAt(idx);
            if (chr != '\r' && chr != '\n') {
                out.append(chr);
            }
        }
        out.append('\n');
    }

    private static void appendData(StringBuilder out, String data) {
        // SSE splits the payload on CR, LF, or CRLF into one data: line each.
        int len = data.length();
        int lineStart = 0;
        int idx = 0;
        while (idx < len) {
            char chr = data.charAt(idx);
            if (chr == '\r' || chr == '\n') {
                out.append("data: ").append(data, lineStart, idx).append('\n');
                if (chr == '\r' && idx + 1 < len && data.charAt(idx + 1) == '\n') {
                    idx++;
                }
                idx++;
                lineStart = idx;
            } else {
                idx++;
            }
        }
        out.append("data: ").append(data, lineStart, len).append('\n');
    }
}
