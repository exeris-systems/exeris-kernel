/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

/**
 * Valhalla-ready event descriptor — routing metadata ONLY.
 *
 * <h2>Design Intent: Separation of Routing and Payload</h2>
 * <p>This record carries <b>only</b> the data required for routing and ordering.
 * Payload bytes are <b>not</b> part of this record — they are delivered separately
 * as an {@link EventPayload} by the {@link EventBus} to each handler.
 *
 * <p>This separation solves the broadcast RAII hazard: when N handlers subscribe
 * to the same event type, the {@code EventBus} calls {@link EventPayload#retain()}
 * {@code (N-1)} times before forking handlers, ensuring the slab stays alive
 * until the last handler calls {@link EventPayload#close()}.
 *
 * <h2>Valhalla Readiness (JEP 401)</h2>
 * <p>All 7 fields are primitives — no object references whatsoever:
 * <ul>
 *   <li>No identity operations ({@code ==}, {@code synchronized}, {@code identityHashCode}).</li>
 *   <li>JIT C2 scalarizes this on the standard heap path via escape analysis.</li>
 *   <li>Native implementations map this to a dense off-heap struct fitting in a single cache line.</li>
 *   <li>When JEP 401 is mainline, adding the {@code value} modifier requires zero
 *       field changes — object headers disappear for free.</li>
 * </ul>
 *
 * <h2>UUID Encoding</h2>
 * <p>UUIDs are split into {@code High}/{@code Low} {@code long} pairs to avoid
 * the 32-byte object header overhead of {@link java.util.UUID}.
 * Use {@link #toEventUuid()} and {@link #toStreamUuid()} for interoperability —
 * both methods allocate a {@link java.util.UUID}, so call them only outside the hot path.
 *
 * <h2>Flags Bitmask</h2>
 * <pre>
 * Bit 0 (0x01): PERSISTENT — must be durably written to the transactional outbox
 * Bit 1 (0x02): ASYNC      — fire-and-forget; no completion acknowledgement
 * Bit 2 (0x04): ORDERED    — strict FIFO ordering required across subscribers
 * Bit 3 (0x08): BROADCAST  — fan-out to ALL subscribers (default: all-match)
 * </pre>
 *
 * @param eventIdHigh      high 64 bits of the event UUID (UUIDv7 for temporal ordering)
 * @param eventIdLow       low 64 bits of the event UUID
 * @param streamIdHigh     high 64 bits of the aggregate stream UUID
 * @param streamIdLow      low 64 bits of the aggregate stream UUID
 * @param eventTypeOrdinal integer ordinal from {@link EventRegistry} — O(1) routing, no String compare
 * @param flags            lifecycle bitmask (PERSISTENT | ASYNC | ORDERED | BROADCAST)
 * @param occurredAtEpochMs wall-clock timestamp in epoch milliseconds (replaces {@code Instant} object)
 *
 * @since 0.5.0
 * @see EventPayload
 * @see EventBus
 * @see EventRegistry
 */
public record EventDescriptor(
        long eventIdHigh,
        long eventIdLow,
        long streamIdHigh,
        long streamIdLow,
        int  eventTypeOrdinal,
        int  flags,
        long occurredAtEpochMs
) {

    // =========================================================================
    // Flag constants
    // =========================================================================

    /** Event must be durably persisted to the transactional outbox. */
    public static final int FLAG_PERSISTENT = 0x01;
    /** Event dispatch is fire-and-forget; no completion acknowledgement. */
    public static final int FLAG_ASYNC      = 0x02;
    /** Strict FIFO ordering required across all subscribers. */
    public static final int FLAG_ORDERED    = 0x04;
    /** Fan-out to ALL matching subscribers (default behaviour for EventBus). */
    public static final int FLAG_BROADCAST  = 0x08;

    // =========================================================================
    // Derived properties — hot-path, zero allocation
    // =========================================================================

    /** @return {@code true} if this event must be durably persisted. */
    public boolean isPersistent() {
        return (flags & FLAG_PERSISTENT) != 0;
    }

    /** @return {@code true} if dispatch is fire-and-forget. */
    public boolean isAsync() {
        return (flags & FLAG_ASYNC) != 0;
    }

    /** @return {@code true} if strict FIFO ordering is required. */
    public boolean isOrdered() {
        return (flags & FLAG_ORDERED) != 0;
    }

    /** @return {@code true} if this event must be broadcast to all subscribers. */
    public boolean isBroadcast() {
        return (flags & FLAG_BROADCAST) != 0;
    }

    // =========================================================================
    // UUID interoperability helpers — allocate, NOT for hot-path
    // =========================================================================

    /**
     * Reconstructs the event {@link java.util.UUID}.
     * <p><b>Allocates</b> — do NOT call in the hot path. Use {@code eventIdHigh}/{@code eventIdLow} directly.
     */
    public java.util.UUID toEventUuid() {
        return new java.util.UUID(eventIdHigh, eventIdLow);
    }

    /**
     * Reconstructs the stream {@link java.util.UUID}.
     * <p><b>Allocates</b> — do NOT call in the hot path.
     */
    public java.util.UUID toStreamUuid() {
        return new java.util.UUID(streamIdHigh, streamIdLow);
    }

    // =========================================================================
    // Factory — all primitives, never a UUID object on the hot path
    // =========================================================================

    /**
     * Creates an {@code EventDescriptor} from primitive fields.
     * Payload is always delivered separately as {@link EventPayload}.
     *
     * @param eventIdHigh      high bits of event UUID
     * @param eventIdLow       low bits of event UUID
     * @param streamIdHigh     high bits of stream UUID
     * @param streamIdLow      low bits of stream UUID
     * @param eventTypeOrdinal ordinal from {@link EventRegistry}
     * @param flags            lifecycle flags bitmask
     * @param occurredAtEpochMs epoch-ms timestamp
     * @return new descriptor
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    public static EventDescriptor of(
            long eventIdHigh, long eventIdLow,
            long streamIdHigh, long streamIdLow,
            int  eventTypeOrdinal,
            int  flags,
            long occurredAtEpochMs) {
        return new EventDescriptor(
                eventIdHigh, eventIdLow,
                streamIdHigh, streamIdLow,
                eventTypeOrdinal, flags,
                occurredAtEpochMs);
    }
}
