/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow;

/**
 * Immutable descriptor of the capabilities of a specific {@link FlowEngine} implementation.
 *
 * <h2>Usage</h2>
 * <p>Used by {@code KernelBootstrap} to populate JFR bootstrap events and emit operator
 * warnings (e.g., when deterministic execution is unavailable in Community tier).
 * TCK tests gate zero-alloc assertions against this descriptor.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All fields are {@code boolean} primitives or {@link String} — ideal for future
 * {@code value record} scalarisation. No identity operations used.
 *
 * <h2>O(1) Access</h2>
 * <p>Implementations MUST return a pre-built constant from
 * {@link FlowEngine#capabilities()} — never construct a new instance on every call.
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
 * @param providerId             stable identifier of the provider that created this engine
 *                               (e.g. {@code "community"}, {@code "enterprise"})
 *
 * @since 0.5.0
 */
public record FlowEngineCapabilities(
        boolean deterministicExecution,
        boolean offHeapDescriptors,
        boolean lockFreeScheduler,
        boolean zeroGcAfterStart,
        boolean persistenceBacked,
        boolean compensationSupport,
        String providerId
) {

    // CHECKSTYLE.OFF: DeclarationOrder — static constants in records must follow components list

    /**
     * Pre-built Community capabilities template.
     *
     * <p><b>Usage:</b> driver implementations SHOULD NOT return this constant directly
     * from {@link FlowEngine#capabilities()} because its {@link #providerId()}
     * is {@code "community"}, which may produce incorrect JFR/diagnostic metadata for
     * custom providers. Instead, build a provider-branded cached constant once at
     * class-load time:
     * <pre>{@code
     * private static final FlowEngineCapabilities CAPS =
     *         FlowEngineCapabilities.COMMUNITY.withProvider("my-flow-community");
     *
     * public FlowEngineCapabilities capabilities() { return CAPS; }
     * }</pre>
     */
    public static final FlowEngineCapabilities COMMUNITY = new FlowEngineCapabilities(
            false, false, false, false, true, true, "community"
    );

    /**
     * Pre-built Enterprise capabilities template.
     *
     * <p><b>Usage:</b> driver implementations SHOULD NOT return this constant directly
     * from {@link FlowEngine#capabilities()} because its {@link #providerId()}
     * is {@code "enterprise"}, which may produce incorrect JFR/diagnostic metadata for
     * custom providers. Instead, build a provider-branded cached constant once at
     * class-load time:
     * <pre>{@code
     * private static final FlowEngineCapabilities CAPS =
     *         FlowEngineCapabilities.ENTERPRISE.withProvider("my-flow-enterprise");
     *
     * public FlowEngineCapabilities capabilities() { return CAPS; }
     * }</pre>
     */
    public static final FlowEngineCapabilities ENTERPRISE = new FlowEngineCapabilities(
            true, true, true, true, true, true, "enterprise"
    );

    // CHECKSTYLE.ON: DeclarationOrder

    /**
     * Compact constructor — validates invariants eagerly (fail-fast bootstrap).
     */
    public FlowEngineCapabilities {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
    }

    /**
     * Returns a new descriptor identical to this one but with the given {@code providerId}.
     *
     * <p>Intended for driver modules that want to reuse a standard template
     * ({@link #COMMUNITY} or {@link #ENTERPRISE}) while stamping their own
     * provider identifier for accurate JFR and diagnostic output. Call once at
     * class-load time and cache the result — allocation happens only during bootstrap.
     *
     * <pre>{@code
     * private static final FlowEngineCapabilities CAPS =
     *         FlowEngineCapabilities.COMMUNITY.withProvider("community-flow");
     * }</pre>
     *
     * @param newProviderId stable provider identifier; must not be blank
     * @return new descriptor with all flags from this instance, new providerId
     * @throws IllegalArgumentException if {@code newProviderId} is blank
     */
    public FlowEngineCapabilities withProvider(String newProviderId) {
        return new FlowEngineCapabilities(
                deterministicExecution,
                offHeapDescriptors,
                lockFreeScheduler,
                zeroGcAfterStart,
                persistenceBacked,
                compensationSupport,
                newProviderId
        );
    }
}
