/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Priority classification for incoming {@link TransportStream} objects.
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <p>This enum is the vocabulary of the Core's Priority-Aware Queue Scheduler (PAQS).
 * It carries business context into the network edge without exposing any knowledge of the
 * underlying protocol (TCP, QUIC, io_uring). The transport driver assigns a {@code StreamPriority}
 * based on protocol headers (e.g. HTTP/3 urgency field per RFC 9218, or a proprietary
 * request-type header). The Core PAQS reads the priority and makes shed/admit decisions.
 *
 * <h2>Ordinal Contract (Stable Binary Layout)</h2>
 * <p>The ordinal of each constant maps directly to the C2-optimised priority switch table
 * in {@code PaqsScheduler}. Do NOT reorder these constants — the ordinal is used in the
 * off-heap {@code AdmissionResultLayout} for binary telemetry.
 * <pre>
 *   Ordinal 0 → CRITICAL (highest — never shed unless SHEDDING watermark)
 *   Ordinal 1 → HIGH     (e.g., payment API, auth, settlement)
 *   Ordinal 2 → NORMAL   (standard business API calls)
 *   Ordinal 3 → LOW      (analytics, non-urgent reporting)
 *   Ordinal 4 → TELEMETRY (internal metrics / heartbeats — first to be shed)
 * </pre>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum constants are JVM singletons. Use {@code ==} for comparisons — they are
 * identity-safe and scalarize correctly under C2 JIT. No boxing occurs on
 * switch-expression dispatch (C2 generates a jump table over ordinals).
 *
 * @since 0.5.0
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
     * every hot-path lookup. Indexed by ordinal from the off-heap
     * {@code AdmissionResultLayout}.
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
