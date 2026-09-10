/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.Objects;

/**
 * SPI: A single server-push event, mapping directly to the Server-Sent Events wire format
 * (WHATWG HTML §9.2 / {@code text/event-stream}).
 *
 * <h2>Event-Shaped, Not Byte-Shaped (The Wall)</h2>
 * <p>This carrier is implementation-blind: it models the SSE field structure ({@code event:} /
 * {@code data:} / {@code id:} / {@code retry:}), never a raw transport buffer. Serialization to the
 * wire belongs to the engine behind {@link HttpStreamExchange#emit(StreamEvent)}; the SPI never sees
 * chunk boundaries, HPACK tables, or io_uring SQEs (ADR-043).
 *
 * <h2>Field Semantics</h2>
 * <ul>
 *   <li>{@code event} — the event type name (SSE {@code event:} field). {@code null} or blank omits the
 *       field, producing a default {@code message} event on the client.</li>
 *   <li>{@code data} — the event payload (SSE {@code data:} field). Required; embedded newlines are
 *       serialized as multiple {@code data:} lines per the SSE spec.</li>
 *   <li>{@code id} — the event id (SSE {@code id:} field). {@code null} or blank omits the field;
 *       when present the client echoes it as {@code Last-Event-ID} on reconnect.</li>
 *   <li>{@code retryMillis} — the client's reconnection delay (SSE {@code retry:} field), in
 *       milliseconds. A value {@code <= 0} omits the field.</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard immutable {@code record}. No {@code synchronized}, no identity {@code ==}. Will migrate to
 * {@code value record} (JEP 401) once mainline GA is reached.
 *
 * @param event       event type name; {@code null}/blank omits the SSE {@code event:} field
 * @param data        event payload; must not be {@code null} (empty string is a valid SSE {@code data:})
 * @param id          event id; {@code null}/blank omits the SSE {@code id:} field
 * @param retryMillis client reconnection delay in ms; {@code <= 0} omits the SSE {@code retry:} field
 * @implNote The Core SSE engine frames an event in two passes over one traversal — a measuring pass
 *           and a write pass that lays the fields straight into the egress off-heap buffer — so an
 *           emit produces no intermediate {@code String} or {@code byte[]}.
 * @since 0.10
 */
@SuppressWarnings({"PMD.ShortMethodName", "PMD.ShortVariable"})
// 'of' is the standard Java factory idiom (cf. List.of, Map.of); 'id' is the canonical SSE field name.
public record StreamEvent(String event, String data, String id, long retryMillis) {

    /**
     * Rejects a {@code null} payload — {@code data} is the one SSE field an event must carry.
     *
     * <p>No other component is validated: {@code event} and {@code id} are optional fields that are
     * simply omitted from the wire when {@code null} or blank, and a {@code retryMillis} of
     * {@code <= 0} omits {@code retry:}. An empty {@code data} is accepted and emits an empty
     * {@code data:} line.
     *
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public StreamEvent {
        Objects.requireNonNull(data, "StreamEvent data must not be null");
    }

    /**
     * Creates a default {@code message} event carrying only a payload (no type, id, or retry).
     *
     * @param data event payload; must not be {@code null}
     * @return an event carrying only {@code data}, which the client delivers as a {@code message}
     *         event with no id and no reconnection hint
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public static StreamEvent of(String data) {
        return new StreamEvent(null, data, null, 0L);
    }

    /**
     * Creates a typed event carrying a payload (no id or retry).
     *
     * @param event event type name; {@code null}/blank omits the field
     * @param data  event payload; must not be {@code null}
     * @return an event the client dispatches to listeners of {@code event}, with no id and no
     *         reconnection hint
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public static StreamEvent of(String event, String data) {
        return new StreamEvent(event, data, null, 0L);
    }

    /**
     * Creates a typed, identified event (no retry) — the natural shape for replayable streams whose
     * id the client echoes as {@code Last-Event-ID} on reconnect.
     *
     * @param event event type name; {@code null}/blank omits the field
     * @param data  event payload; must not be {@code null}
     * @param id    event id; {@code null}/blank omits the field
     * @return an event the client dispatches to listeners of {@code event} and echoes as
     *         {@code Last-Event-ID} on reconnect, with no reconnection hint
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public static StreamEvent of(String event, String data, String id) {
        return new StreamEvent(event, data, id, 0L);
    }
}
