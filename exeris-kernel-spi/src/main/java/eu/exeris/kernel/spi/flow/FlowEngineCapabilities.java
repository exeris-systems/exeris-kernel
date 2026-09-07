/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

/**
 * Immutable descriptor of the capabilities of a specific {@link FlowEngine} implementation.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All fields are {@code boolean} primitives or {@link String} — ideal for future
 * {@code value record} scalarisation. No identity operations used.
 *
 * @param deterministicExecution {@code true} if the engine guarantees deterministic step
 *                               ordering (Enterprise only)
 * @param offHeapDescriptors     {@code true} if step and transition descriptors are stored
 *                               off-heap in slab pools (Enterprise only)
 * @param lockFreeScheduler      {@code true} if the scheduler uses a lock-free ring buffer
 *                               whose {@code head} and {@code tail} counters are physically
 *                               isolated on separate cache lines (≥ 128 bytes apart) to
 *                               prevent false sharing (Enterprise only)
 * @param zeroGcAfterStart       {@code true} if zero heap allocations occur after
 *                               {@link FlowEngine#start()} (Enterprise only)
 * @param persistenceBacked      {@code true} if flow snapshot persistence is available
 * @param compensationSupport    {@code true} if backward compensation (saga rollback) is supported
 * @param choreographySupport    {@code true} if event-driven saga choreography via
 *                               {@link FlowEngine#registerChoreographyMapper} is supported
 * @param providerId             stable identifier of the provider that created this engine
 *                               (e.g. {@code "community"}, {@code "enterprise"})
 *
 * @implSpec {@link FlowEngine#capabilities()} must hand back a pre-built constant, never an
 *           instance constructed per call — the descriptor is read on diagnostic and bootstrap
 *           paths that must not allocate.
 * @apiNote The bootstrapper reads this to populate JFR bootstrap events and to warn an operator
 *          about a guarantee the selected binding does not offer — deterministic execution, say.
 *          The TCK gates its zero-allocation assertions on it for the same reason: the descriptor,
 *          not the tier name, says what may be asserted.
 * @since 0.5
 */
public record FlowEngineCapabilities(
        boolean deterministicExecution,
        boolean offHeapDescriptors,
        boolean lockFreeScheduler,
        boolean zeroGcAfterStart,
        boolean persistenceBacked,
        boolean compensationSupport,
        boolean choreographySupport,
        String providerId
) {

    // CHECKSTYLE.OFF: DeclarationOrder — static constants in records must follow components list

    /**
     * The capability set of a heap-based binding: persistence, compensation and choreography
     * supported; no deterministic ordering, no off-heap descriptors, no lock-free scheduler, no
     * zero-GC guarantee after start.
     *
     * @apiNote A driver should not return this constant from {@link FlowEngine#capabilities()} as
     *          it stands — its {@link #providerId()} is {@code "community"}, which would mislabel
     *          the driver in JFR and diagnostic output. Brand it once at class-load time and cache
     *          the result:
     *          {@snippet lang="java" :
     *          private static final FlowEngineCapabilities CAPS =
     *                  FlowEngineCapabilities.COMMUNITY.withProvider("my-flow-community");
     *
     *          public FlowEngineCapabilities capabilities() { return CAPS; }
     *          }
     */
    public static final FlowEngineCapabilities COMMUNITY = new FlowEngineCapabilities(
            false, false, false, false, true, true, true, "community"
    );

    /**
     * The capability set of an off-heap binding: every flag set — deterministic ordering, off-heap
     * descriptors, a lock-free scheduler, zero-GC after start, persistence, compensation and
     * choreography.
     *
     * @apiNote A driver should not return this constant from {@link FlowEngine#capabilities()} as
     *          it stands — its {@link #providerId()} is {@code "enterprise"}, which would mislabel
     *          the driver in JFR and diagnostic output. Brand it once at class-load time and cache
     *          the result:
     *          {@snippet lang="java" :
     *          private static final FlowEngineCapabilities CAPS =
     *                  FlowEngineCapabilities.ENTERPRISE.withProvider("my-flow-enterprise");
     *
     *          public FlowEngineCapabilities capabilities() { return CAPS; }
     *          }
     */
    public static final FlowEngineCapabilities ENTERPRISE = new FlowEngineCapabilities(
            true, true, true, true, true, true, true, "enterprise"
    );

    // CHECKSTYLE.ON: DeclarationOrder

    /**
     * Validates the descriptor at construction, so a provider that cannot name itself fails during
     * bootstrap rather than producing unattributable JFR records later.
     *
     * @throws IllegalArgumentException if {@code providerId} is {@code null} or blank
     */
    public FlowEngineCapabilities {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
    }

    /**
     * Rebrands a template ({@link #COMMUNITY} or {@link #ENTERPRISE}) with the caller's own
     * provider identifier, so JFR and diagnostic output name the driver that is actually running
     * rather than the template it started from.
     *
     * <p>{@snippet lang="java" :
     * private static final FlowEngineCapabilities CAPS =
     *         FlowEngineCapabilities.COMMUNITY.withProvider("community-flow");
     * }
     *
     * @param newProviderId stable provider identifier; must not be blank
     * @return a descriptor carrying every flag of this one under the new {@code providerId}; this
     *         instance is unchanged
     * @throws IllegalArgumentException if {@code newProviderId} is {@code null} or blank
     * @apiNote Call it once at class-load time and cache the result — it allocates, which is why
     *          {@link FlowEngine#capabilities()} must not call it per invocation.
     */
    public FlowEngineCapabilities withProvider(String newProviderId) {
        return new FlowEngineCapabilities(
                deterministicExecution,
                offHeapDescriptors,
                lockFreeScheduler,
                zeroGcAfterStart,
                persistenceBacked,
                compensationSupport,
                choreographySupport,
                newProviderId
        );
    }
}
