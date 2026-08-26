/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @param maxSignedUrlTtl   ceiling applied to any signed-URL time-to-live a caller requests; at
 *                          least one second, matching the floor {@code BlobStore.signedUrl} enforces.
 *                          A store that cannot sign ignores it
 * @param properties        opaque key-value options for driver-specific settings; never {@code null}
 * @since 0.11.0
 */
public record BlobStorageConfig(String location, Duration maxSignedUrlTtl,
                                Map<String, String> properties) {

    /** Default ceiling on signed-URL lifetime when a deployment states none. */
    public static final Duration DEFAULT_MAX_SIGNED_URL_TTL = Duration.ofMinutes(15);

    /**
     * Shortest signed-URL validity any store accepts.
     *
     * <p>One second, because that is the granularity the signing schemes express expiry in. Declared
     * here rather than in each driver so the floor a caller must satisfy and the ceiling a deployment
     * configures are read from one place — a driver-local copy is how the two drift apart.
     */
    public static final Duration MIN_SIGNED_URL_TTL = Duration.ofSeconds(1);

    /**
     * Canonical constructor; defensively copies {@code properties}.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if {@code location} is blank or the TTL ceiling is under a second
     */
    public BlobStorageConfig {
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(maxSignedUrlTtl, "maxSignedUrlTtl must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        if (location.isBlank()) {
            throw new IllegalArgumentException("location must not be blank");
        }
        // Not merely positive: a ceiling below the one-second floor BlobStore.signedUrl enforces
        // would make every signing call unsatisfiable, and the failure would surface per call as a
        // caller error rather than here as the misconfiguration it is.
        if (maxSignedUrlTtl.compareTo(MIN_SIGNED_URL_TTL) < 0) {
            throw new IllegalArgumentException("maxSignedUrlTtl must be at least one second");
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
