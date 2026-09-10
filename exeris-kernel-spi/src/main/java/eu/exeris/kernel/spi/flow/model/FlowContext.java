/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * SPI: the state of one live flow instance, as a step sees it while executing.
 *
 * <p>This is an <b>interface</b> rather than a record because a binding is free to serve it as a
 * reusable view over off-heap storage instead of as a per-instance object. A {@code FlowContext}
 * therefore carries no object identity worth reading: two references describing the same saga need
 * not be the same object, and one object may describe a different saga on the next dispatch. The
 * 128-bit instance UUID exposed through {@link #instanceIdMost()} and {@link #instanceIdLeast()} is
 * the identity.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — every accessor reads one field or one off-heap
 * slot, and {@link #isSameInstance(FlowContext)} compares two {@code long}s; none of them allocates.
 *
 * @apiNote Do not compare contexts with {@code ==}, do not synchronise on one, and do not key
 *          anything on {@code System.identityHashCode()}. All three read the object identity this
 *          type does not promise. Use {@link #isSameInstance(FlowContext)} instead.
 * @implNote The Enterprise binding is a Flyweight: one reusable per-carrier view slides its
 *           {@code baseAddress} over the off-heap context slab, so no object is created per
 *           dispatch and the Zero-GC contract survives
 *           {@link eu.exeris.kernel.spi.flow.FlowEngine#start()}. The Community binding is a plain
 *           heap record and makes no zero-GC claim.
 * @since 0.5
 */
public interface FlowContext {

    /**
     * Most significant 64 bits of this instance's 128-bit UUID.
     *
     * <p>Paired with {@link #instanceIdLeast()} this is the instance's only stable identity.
     *
     * @return the high half of the flow instance UUID
     * @implNote Off-heap offset 0 in the Enterprise context slab.
     */
    long instanceIdMost();

    /**
     * Least significant 64 bits of this instance's 128-bit UUID.
     *
     * <p>Paired with {@link #instanceIdMost()} this is the instance's only stable identity.
     *
     * @return the low half of the flow instance UUID
     * @implNote Off-heap offset 8 in the Enterprise context slab.
     */
    long instanceIdLeast();

    /**
     * Name of the {@link FlowDefinition} this instance was compiled from.
     *
     * @return the definition name; never {@code null} and never blank
     */
    String definitionName();

    /**
     * Zero-based index of the step currently executing, addressed within the plan this instance is
     * bound to.
     *
     * <p>A position, not an identity: the same index addresses a different step once a redeploy
     * reorders the definition, which is why a persisted instance also records
     * {@link FlowSnapshot#currentStepName()}.
     *
     * @return the index of the executing step; {@code >= 0}
     * @implNote Off-heap offset 16 in the Enterprise context slab.
     */
    int currentStep();

    /**
     * Lifecycle state this instance currently occupies.
     *
     * @return the current state; never {@code null}
     * @implNote Off-heap offset 20 in the Enterprise context slab, stored as {@link FlowState#code}.
     */
    FlowState state();

    /**
     * Absolute deadline in nanoseconds in the {@code System.nanoTime()} epoch.
     *
     * <p>Computed by the scheduler at instance creation as:
     * {@code System.nanoTime() + plan.timeoutDurationNanos()}.
     * Distinct from {@link eu.exeris.kernel.spi.flow.model.FlowExecutionPlan#timeoutDurationNanos()}
     * which is a configured <em>duration</em>. {@code Long.MAX_VALUE} means the instance has no
     * deadline and the check is skipped.
     *
     * @return the absolute deadline, or {@code Long.MAX_VALUE} for no timeout
     * @implNote Off-heap offset 32 in the Enterprise context slab.
     */
    long timeoutNanos();

    /**
     * Compares instance identity by UUID rather than by object identity.
     *
     * @param other the context to compare against; {@code null} is permitted and compares unequal
     * @return {@code true} when both describe the same flow instance; {@code false} when
     *         {@code other} is {@code null} or carries a different instance UUID
     */
    default boolean isSameInstance(FlowContext other) {
        return other != null
               && this.instanceIdMost() == other.instanceIdMost()
               && this.instanceIdLeast() == other.instanceIdLeast();
    }
}
