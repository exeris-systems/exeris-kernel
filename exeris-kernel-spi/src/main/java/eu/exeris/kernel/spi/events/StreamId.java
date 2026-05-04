/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Valhalla-ready identifier of an event stream (aggregate root).
 *
 * <h2>Design Intent</h2>
 * <p>{@code StreamId} mirrors the high/low {@code long} pair that {@link EventDescriptor}
 * already carries for stream UUIDs, plus a non-blank {@code streamType} qualifier
 * (e.g. {@code "Order"}, {@code "User"}) that brokers and replay APIs use to scope a
 * query without re-deriving the type from a per-event field.
 *
 * <h2>Valhalla Readiness (JEP 401)</h2>
 * <ul>
 *   <li>Two primitive {@code long} fields plus one {@link String}; no identity-sensitive
 *       collaborators. Adding the {@code value} modifier requires zero field changes.</li>
 *   <li>{@link #toUuid()} allocates a {@link UUID} and is for diagnostics / logging only —
 *       do NOT call from a hot dispatch loop.</li>
 * </ul>
 *
 * <h2>Routing Compatibility</h2>
 * <p>{@code streamIdHigh} and {@code streamIdLow} are wire-compatible with
 * {@link EventDescriptor#streamIdHigh()} and {@link EventDescriptor#streamIdLow()}, so a
 * stream-scoped query can be answered by the same off-heap routing index that the bus uses
 * for descriptor dispatch.
 *
 * @param streamIdHigh high 64 bits of the stream UUID
 * @param streamIdLow  low 64 bits of the stream UUID
 * @param streamType   non-blank stream type qualifier (e.g. {@code "Order"})
 *
 * @since 0.7.0
 * @see EventStreamReader
 * @see EventStreamAppender
 */
public record StreamId(long streamIdHigh, long streamIdLow, String streamType) {

    /**
     * Compact constructor — validates {@code streamType} is non-null and non-blank.
     */
    public StreamId {
        Objects.requireNonNull(streamType, "streamType must not be null");
        if (streamType.isBlank()) {
            throw new IllegalArgumentException("streamType must not be blank");
        }
    }

    /**
     * Convenience factory that splits a {@link UUID} into the primitive high/low pair.
     *
     * <p><b>Allocates</b> nothing beyond the returned record itself — but the {@code UUID}
     * argument is itself heap-allocated, so prefer the canonical primitive constructor on
     * hot paths.
     *
     * @param uuid       the stream UUID; must not be {@code null}
     * @param streamType non-blank stream type qualifier
     * @return new {@code StreamId} carrying {@code uuid}'s high/low bits
     */
    // 'of' is a standard Java factory idiom (cf. List.of, Map.of, EventDescriptor.of)
    @SuppressWarnings("PMD.ShortMethodName")
    public static StreamId of(UUID uuid, String streamType) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return new StreamId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), streamType);
    }

    /**
     * Reconstructs the stream {@link UUID}.
     *
     * <p><b>Allocates</b> — diagnostic / logging path only. Use the high/low primitive
     * accessors directly on the dispatch / replay hot path.
     *
     * @return new {@link UUID} composed of {@code streamIdHigh}/{@code streamIdLow}
     */
    public UUID toUuid() {
        return new UUID(streamIdHigh, streamIdLow);
    }
}
