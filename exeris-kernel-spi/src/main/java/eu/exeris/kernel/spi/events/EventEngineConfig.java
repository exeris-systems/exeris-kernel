/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

import java.util.Objects;

/**
 * Immutable configuration for the {@link EventEngine}.
 *
 * <h2>Design</h2>
 * <p>All configuration is expressed via primitives and {@link String} keys —
 * no references to implementation-specific classes. Both Community and Enterprise
 * implementations read only the fields they need; unknown fields are ignored.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Mostly primitive fields. The {@link String} fields ({@code partitionName},
 * {@code engineName}) are not on the hot path. Ready for {@code value record} when JEP 401 is stable.
 *
 * @param engineName          human-readable engine name (for JFR/logging)
 * @param queueCapacity       maximum event queue capacity (power-of-2 recommended for Enterprise)
 * @param batchSize           maximum events drained per loop tick
 * @param partitionName       memory partition name for Enterprise off-heap allocation
 * @param partitionBytes      total bytes to claim for the event memory partition (Enterprise)
 * @param slabDescriptorCount number of pre-allocated event descriptor slab slots (Enterprise)
 * @param slabPayloadSmall    number of small payload slab slots (256 bytes, Enterprise)
 * @param slabPayloadMedium   number of medium payload slab slots (4 KB, Enterprise)
 * @param slabPayloadLarge    number of large payload slab slots (64 KB, Enterprise)
 * @param outboxEnabled       whether the transactional outbox writer is enabled
 * @param outboxBatchSize     maximum events written per outbox flush cycle
 *
 * @since 0.5.0
 */
public record EventEngineConfig(
        String  engineName,
        int     queueCapacity,
        int     batchSize,
        String  partitionName,
        long    partitionBytes,
        int     slabDescriptorCount,
        int     slabPayloadSmall,
        int     slabPayloadMedium,
        int     slabPayloadLarge,
        boolean outboxEnabled,
        int     outboxBatchSize
) {

    /**
     * Compact constructor — validates invariants eagerly (fail-fast bootstrap).
     */
    public EventEngineConfig {
        Objects.requireNonNull(engineName, "engineName must not be null");
        if (engineName.isBlank()) {
            throw new IllegalArgumentException("engineName must not be blank");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be > 0, got: " + queueCapacity);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0, got: " + batchSize);
        }
        if (slabDescriptorCount < 0) {
            throw new IllegalArgumentException("slabDescriptorCount must be >= 0, got: " + slabDescriptorCount);
        }
        if (slabPayloadSmall < 0) {
            throw new IllegalArgumentException("slabPayloadSmall must be >= 0, got: " + slabPayloadSmall);
        }
        if (slabPayloadMedium < 0) {
            throw new IllegalArgumentException("slabPayloadMedium must be >= 0, got: " + slabPayloadMedium);
        }
        if (slabPayloadLarge < 0) {
            throw new IllegalArgumentException("slabPayloadLarge must be >= 0, got: " + slabPayloadLarge);
        }
        if (slabDescriptorCount > 0 && (slabPayloadSmall == 0 && slabPayloadMedium == 0 && slabPayloadLarge == 0)) {
            throw new IllegalArgumentException(
                    "at least one payload slab count must be > 0 when slabDescriptorCount > 0");
        }
        if (outboxEnabled && outboxBatchSize <= 0) {
            throw new IllegalArgumentException(
                    "outboxBatchSize must be > 0 when outboxEnabled is true, got: " + outboxBatchSize);
        }
    }

    /**
     * Community default configuration — heap-based, no off-heap settings.
     *
     * <p>Queue capacity: 65 536 events.
     * Batch size: 512 events per loop tick.
     * No memory partition (Community uses heap).
     */
    public static EventEngineConfig communityDefaults() {
        return new EventEngineConfig(
                "community-events",
                65_536,
                512,
                "events",
                0L,
                0, 0, 0, 0,
                true,
                500
        );
    }

    /**
     * Enterprise default configuration — off-heap, slab-pooled, zero-GC.
     *
     * <p>Queue capacity: 1 048 576 events (1M, power of 2).
     * Batch size: 4 096 events per loop tick.
     * Memory partition: 64 MB.
     * Descriptor slabs: 1 000 000.
     * Payload slabs: 100 000 small / 10 000 medium / 1 000 large.
     */
    public static EventEngineConfig enterpriseDefaults() {
        return new EventEngineConfig(
                "enterprise-events",
                1_048_576,
                4_096,
                "events",
                64L * 1024L * 1024L,  // 64 MB
                1_000_000,
                100_000,
                10_000,
                1_000,
                true,
                4_096
        );
    }
}

