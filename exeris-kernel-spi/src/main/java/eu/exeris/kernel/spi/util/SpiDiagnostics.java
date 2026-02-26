/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.util;

import java.util.Objects;
import java.util.UUID;

/**
 * SPI-internal diagnostic utilities — safe formatting for log output, JFR events,
 * and exception messages.
 *
 * <h2>UUID Masking</h2>
 * <p>All UUID-bearing SPI types ({@code ImmutablePrincipal}, {@code PathResult}, etc.)
 * MUST use {@link #maskUuid(UUID)} for {@code toString()} output to prevent accidental
 * exposure of node/identity UUIDs in logs or telemetry.
 *
 * <h2>Non-Instantiable</h2>
 * <p>Pure static utility — no instances, no state, no DI.
 *
 * @since 0.5.0
 */
public final class SpiDiagnostics {

    /**
     * Number of leading hex characters exposed by {@link #maskUuid(UUID)}.
     *
     * <p>UUIDv7 bit layout (RFC 9562):
     * <pre>
     *  0        8   12   16   20                  36
     *  xxxxxxxx-xxxx-7xxx-yxxx-xxxxxxxxxxxx
     *  |______| |__|
     *  ts_ms_be (32b of 48ms bits)
     * </pre>
     * The first 8 hex digits encode the high 32 bits of the millisecond timestamp —
     * sufficient for log-time correlation (±1 second granularity) but carry no
     * personal identity. All remaining segments are masked.
     */
    public static final int UUID_MASK_PREFIX_LEN = 8;

    /** Suffix appended after the visible UUID prefix to signal masking. */
    public static final String UUID_MASK_SUFFIX = "~***";

    private SpiDiagnostics() {
        // Non-instantiable utility
    }

    /**
     * Masks a UUID for safe log, JFR, and exception output.
     *
     * <p>Exposes only the first {@value #UUID_MASK_PREFIX_LEN} hex characters
     * (the {@code ts_ms_be} segment of UUIDv7) followed by {@value #UUID_MASK_SUFFIX}.
     * This is sufficient for log-time correlation while carrying no personal identity.
     *
     * <p>Example: {@code 01945a3b-f2c1-7abc-...} → {@code 01945a3b~***}
     *
     * @param uuid the UUID to mask; must not be {@code null}
     * @return masked string; never {@code null}
     */
    public static String maskUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return uuid.toString().substring(0, UUID_MASK_PREFIX_LEN) + UUID_MASK_SUFFIX;
    }
}

