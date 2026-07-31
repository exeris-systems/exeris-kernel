/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.storage.blob;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * SPI: Immutable configuration for {@link BlobStorageProvider#createStore}.
 *
 * <p>Logical knobs only. {@code location} is driver-interpreted — a filesystem root for one binding, an
 * endpoint URL for another — because a field the SPI could interpret would mean the SPI had picked a
 * storage topology.
 *
 * @param location          driver-interpreted root location; non-blank
 * @param maxSignedUrlTtl   ceiling applied to any signed-URL time-to-live a caller requests; positive.
 *                          A store that cannot sign ignores it
 * @param properties        opaque key-value options for driver-specific settings; never {@code null}
 * @since 0.11.0
 */
public record BlobStorageConfig(String location, Duration maxSignedUrlTtl,
                                Map<String, String> properties) {

    /** Default ceiling on signed-URL lifetime when a deployment states none. */
    public static final Duration DEFAULT_MAX_SIGNED_URL_TTL = Duration.ofMinutes(15);

    /**
     * Canonical constructor; defensively copies {@code properties}.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if {@code location} is blank or the TTL ceiling is not positive
     */
    public BlobStorageConfig {
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(maxSignedUrlTtl, "maxSignedUrlTtl must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        if (location.isBlank()) {
            throw new IllegalArgumentException("location must not be blank");
        }
        if (maxSignedUrlTtl.isZero() || maxSignedUrlTtl.isNegative()) {
            throw new IllegalArgumentException("maxSignedUrlTtl must be positive");
        }
        properties = Map.copyOf(properties);
    }

    /**
     * Creates a configuration with the default signed-URL ceiling and no driver properties.
     *
     * @param location driver-interpreted root location
     * @return the configuration; never {@code null}
     */
    public static BlobStorageConfig atLocation(String location) {
        return new BlobStorageConfig(location, DEFAULT_MAX_SIGNED_URL_TTL, Map.of());
    }
}
