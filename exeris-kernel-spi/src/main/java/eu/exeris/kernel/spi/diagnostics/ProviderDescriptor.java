/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable descriptor of a single discovered kernel provider.
 *
 * @param providerName the provider's stable name (e.g. {@code "ExerisCommunity/TextTelemetry"})
 * @param spiType      the SPI domain this provider serves (e.g. {@code "telemetry"}, {@code "memory"})
 * @param priority     the provider's discovery priority (Community = 0, Enterprise = 100)
 * @param displayName  optional human-friendly name, when distinct from {@code providerName}
 * @since 0.9
 */
public record ProviderDescriptor(
        String providerName,
        String spiType,
        int priority,
        Optional<String> displayName) {

    /**
     * Rejects a {@code null} component: an absent {@code displayName} is carried as
     * {@link Optional#empty()}, never as {@code null}.
     *
     * @throws NullPointerException if {@code providerName}, {@code spiType} or {@code displayName}
     *                              is {@code null}
     */
    public ProviderDescriptor {
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(spiType, "spiType");
        Objects.requireNonNull(displayName, "displayName");
    }
}
