/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

/**
 * The vocabulary for stream priority across the transport layer.
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <p>This enum carries business context into the network edge without exposing knowledge
 * of the underlying protocol. Transport drivers assign a {@code StreamPriority} based on
 * incoming connection criteria, allowing the kernel to make deterministic admission and
 * load-shedding decisions.
 *
 * <h2>Ordinal Contract (Stable Binary Layout)</h2>
 * <p>The ordinal of each constant is stable and guaranteed for binary serialization
 * and fast array-based routing. Do NOT reorder these constants.
 *
 * @since 0.5
 */
public enum StreamPriority {

    /**
     * Highest priority — critical payment, settlement, or auth streams.
     * Admitted even under memory-pressure REJECT action.
     * Only shed when the system reaches full load-shedding mode.
     */
    CRITICAL,

    /**
     * High-priority business streams (e.g., payment API, order processing).
     * Admitted under ALLOW and THROTTLE; shed under REJECT and SHED_LOAD.
     */
    HIGH,

    /**
     * Standard business API calls. Admitted under ALLOW; throttled or shed under pressure.
     */
    NORMAL,

    /**
     * Low-priority streams (analytics queries, non-urgent reporting).
     * Shed aggressively when memory pressure is WARNING or above.
     */
    LOW,

    /**
     * Internal telemetry / heartbeat streams. Shed first when the queue exceeds the
     * warning threshold. These streams carry no revenue impact.
     */
    TELEMETRY;

    /**
     * Pre-computed values array — avoids defensive {@code values()} copy allocation on
     * every hot-path lookup. Indexed by ordinal.
     */
    private static final StreamPriority[] INDEXED = values();

    /**
     * Returns the {@link StreamPriority} constant for the given ordinal.
     * Equivalent to {@code values()[ordinal]} but without the defensive array copy.
     *
     * @param ordinal the ordinal of the constant (0 = CRITICAL, 4 = TELEMETRY)
     * @return the corresponding {@code StreamPriority}
     * @throws ArrayIndexOutOfBoundsException if the ordinal is out of range
     */
    public static StreamPriority byOrdinal(int ordinal) {
        return INDEXED[ordinal];
    }
}
