/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.util;

import java.util.Objects;
import java.util.UUID;

/**
 * SPI-internal diagnostic utilities — safe formatting for log output, JFR events, and exception
 * messages.
 *
 * <p>Pure static utility: no instances, no state, no dependency injection.
 *
 * @apiNote Every UUID-bearing SPI type ({@code ImmutablePrincipal}, {@code PathResult}, etc.) must
 *          use {@link #maskUuid(UUID)} for its {@code toString()} output, to keep node and identity
 *          UUIDs out of logs and telemetry.
 * @since 0.5
 */
public final class SpiDiagnostics {

    /**
     * Number of leading hex characters exposed by {@link #maskUuid(UUID)}.
     *
     * <p>Exposes the first 8 hex digits of the canonical {@code toString()} form
     * ({@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}), which corresponds to the
     * first 32 bits of the most-significant long.
     *
     * <p><b>UUIDv7 convention (not enforced):</b> when the input is a UUIDv7
     * (RFC 9562), these 8 digits encode the high 32 bits of the millisecond
     * timestamp, making them sufficient for log-time correlation (±1 second
     * granularity) while carrying no personal identity. For other UUID versions
     * (v4, v1, etc.) the same 8 digits are exposed without any semantic
     * guarantee — callers should not rely on timestamp semantics unless the UUID
     * version is known to be v7.
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
     * of the canonical UUID string form, followed by {@value #UUID_MASK_SUFFIX}.
     * All remaining segments are replaced. This is sufficient for basic
     * log-correlation purposes while limiting unintentional exposure of
     * identifiers in diagnostic output.
     *
     * <p>When the input is a UUIDv7, the exposed prefix corresponds to the
     * high 32 bits of the millisecond timestamp (log-time correlation with
     * ±1 s granularity, no personal identity). For other UUID versions the
     * same prefix is exposed without any semantic guarantee.
     *
     * <p>Example: {@code 01945a3b-f2c1-7abc-...} → {@code 01945a3b~***}
     *
     * @param uuid the UUID to mask; must not be {@code null}
     * @return masked string; never {@code null}
     * @throws NullPointerException if {@code uuid} is {@code null}
     */
    public static String maskUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return uuid.toString().substring(0, UUID_MASK_PREFIX_LEN) + UUID_MASK_SUFFIX;
    }
}

