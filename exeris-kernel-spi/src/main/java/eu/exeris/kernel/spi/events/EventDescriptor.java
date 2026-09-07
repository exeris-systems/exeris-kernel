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
 * @apiNote Routing decisions read {@link #eventTypeOrdinal()} and {@link #flags()} only; the
 *          {@code UUID} accessors allocate and belong on diagnostic paths.
 * @implNote A native binding may map this layout onto a dense off-heap struct that fits in a
 *           single cache line; the field order above is the layout such a binding mirrors.
 * @since 0.5
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

    /**
     * Tests the {@link #FLAG_PERSISTENT} bit — whether the transactional outbox must durably
     * record this event before it is considered delivered.
     *
     * @return {@code true} when {@link #FLAG_PERSISTENT} is set in {@link #flags()}
     */
    public boolean isPersistent() {
        return (flags & FLAG_PERSISTENT) != 0;
    }

    /**
     * Tests the {@link #FLAG_ASYNC} bit — whether dispatch is fire-and-forget, with no
     * completion acknowledgement owed to the publisher.
     *
     * @return {@code true} when {@link #FLAG_ASYNC} is set in {@link #flags()}
     */
    public boolean isAsync() {
        return (flags & FLAG_ASYNC) != 0;
    }

    /**
     * Tests the {@link #FLAG_ORDERED} bit — whether events of this type require strict FIFO
     * ordering across subscribers. The flag is an advisory routing hint: the ordering guarantee
     * itself is owned by the durable-log surface ({@link EventStreamAppender}), not by the
     * transient {@link EventBus}.
     *
     * @return {@code true} when {@link #FLAG_ORDERED} is set in {@link #flags()}
     */
    public boolean isOrdered() {
        return (flags & FLAG_ORDERED) != 0;
    }

    /**
     * Tests the {@link #FLAG_BROADCAST} bit — whether the event fans out to every matching
     * subscriber rather than to a single one.
     *
     * @return {@code true} when {@link #FLAG_BROADCAST} is set in {@link #flags()}
     */
    public boolean isBroadcast() {
        return (flags & FLAG_BROADCAST) != 0;
    }

    // =========================================================================
    // UUID interoperability helpers — allocate, NOT for hot-path
    // =========================================================================

    /**
     * Materialises the event identity as a {@link java.util.UUID} from the
     * {@code eventIdHigh}/{@code eventIdLow} pair, for interoperability with APIs that speak
     * {@code UUID} rather than primitive halves.
     *
     * @return a newly allocated {@link java.util.UUID} whose most/least significant bits are
     *         {@link #eventIdHigh()} and {@link #eventIdLow()}
     * @apiNote Allocates one {@code UUID} per call — keep it off the routing path and compare
     *          the primitive halves directly there.
     */
    public java.util.UUID toEventUuid() {
        return new java.util.UUID(eventIdHigh, eventIdLow);
    }

    /**
     * Materialises the aggregate-stream identity as a {@link java.util.UUID} from the
     * {@code streamIdHigh}/{@code streamIdLow} pair.
     *
     * @return a newly allocated {@link java.util.UUID} whose most/least significant bits are
     *         {@link #streamIdHigh()} and {@link #streamIdLow()}
     * @apiNote Allocates one {@code UUID} per call — keep it off the routing path and compare
     *          the primitive halves directly there.
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
     * @return an immutable descriptor carrying exactly the given primitives; no payload is
     *         attached and none is implied
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
