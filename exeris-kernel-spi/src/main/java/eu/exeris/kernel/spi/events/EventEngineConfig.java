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
 * @param busPublishFailFast  when {@code true}, persistent {@link EventBus#publish} fails fast with
 *                            {@link eu.exeris.kernel.spi.exceptions.events.EventBusException}
 *                            ({@code EX-EVENT-6002}, rawArgs {@code [eventType, queueDepth, queueCapacity]})
 *                            on a full queue instead of blocking the caller. Default {@code false}
 *                            preserves the historical blocking-on-VT semantic. Brokers that map this
 *                            knob onto a producer-side overflow signal (e.g. Kafka producer
 *                            {@code buffer.memory} exhaustion) raise the same code with the same
 *                            rawArgs layout. (since 0.7.0)
 *
 * @since 0.5.0
 */
// 12 fields — flat-record config carrier; nesting would obscure ServiceLoader mapping
@SuppressWarnings("PMD.ExcessiveParameterList")
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
        int     outboxBatchSize,
        boolean busPublishFailFast
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
        validateSlabCounts(slabDescriptorCount, slabPayloadSmall, slabPayloadMedium, slabPayloadLarge);
        validateSlabCoherence(slabDescriptorCount, slabPayloadSmall, slabPayloadMedium, slabPayloadLarge);
        validatePartition(partitionName, partitionBytes);
        if (outboxEnabled && outboxBatchSize <= 0) {
            throw new IllegalArgumentException(
                    "outboxBatchSize must be > 0 when outboxEnabled is true, got: " + outboxBatchSize);
        }
    }

    /**
     * Backward-compatible 11-arg constructor — preserves the v0.6 call shape so existing callers
     * (Community bootstrap, tests) compile unchanged. Defaults the new 0.7 field to:
     * {@code busPublishFailFast = false} (historical blocking-on-VT semantic).
     *
     * @since 0.7.0
     */
    public EventEngineConfig(
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
        this(engineName, queueCapacity, batchSize, partitionName, partitionBytes,
             slabDescriptorCount, slabPayloadSmall, slabPayloadMedium, slabPayloadLarge,
             outboxEnabled, outboxBatchSize, false);
    }

    /**
     * Validates that every slab count is non-negative — extracted to keep each method
     * within PMD's {@code CyclomaticComplexity} and SonarQube's {@code java:S3776} budgets.
     */
    private static void validateSlabCounts(int descriptorCount, int small, int medium, int large) {
        if (descriptorCount < 0) {
            throw new IllegalArgumentException("slabDescriptorCount must be >= 0, got: " + descriptorCount);
        }
        if (small < 0) {
            throw new IllegalArgumentException("slabPayloadSmall must be >= 0, got: " + small);
        }
        if (medium < 0) {
            throw new IllegalArgumentException("slabPayloadMedium must be >= 0, got: " + medium);
        }
        if (large < 0) {
            throw new IllegalArgumentException("slabPayloadLarge must be >= 0, got: " + large);
        }
    }

    /**
     * Validates cross-field slab coherence: when descriptors are requested, at least one
     * payload slab tier must also be non-zero — extracted to isolate the compound boolean check.
     */
    private static void validateSlabCoherence(int descriptorCount, int small, int medium, int large) {
        if (descriptorCount > 0 && small == 0 && medium == 0 && large == 0) {
            throw new IllegalArgumentException(
                    "at least one payload slab count must be > 0 when slabDescriptorCount > 0");
        }
    }

    /**
     * Validates partition name/bytes consistency — extracted to keep the compact constructor
     * within SonarQube's {@code java:S3776} Cognitive Complexity budget of 15.
     */
    private static void validatePartition(String name, long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("partitionBytes must be >= 0, got: " + bytes);
        }
        if (bytes > 0 && (name == null || name.isBlank())) {
            throw new IllegalArgumentException(
                    "partitionName must be non-null and non-blank when partitionBytes > 0");
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
                500,
                false  // busPublishFailFast — preserves v0.6 blocking-on-VT semantic
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
                4_096,
                true   // busPublishFailFast — Enterprise prefers explicit overflow signalling
        );
    }
}

