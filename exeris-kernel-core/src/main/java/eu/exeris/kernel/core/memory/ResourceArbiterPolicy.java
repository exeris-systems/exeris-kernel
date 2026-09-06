/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

/**
 * Stateless threshold-to-action policy consulted by {@link ResourceArbiter}: maps either a
 * fixed {@link WatermarkLevel} or a per-tenant {@link ScalingContext} to the
 * {@link ResourceArbiter.Action} the arbiter should return.
 *
 * <p>Holds no fields and is never instantiated; keeping this mapping out of
 * {@link ResourceArbiter} itself separates the threshold policy from the arbiter's
 * decision-cache bookkeeping.
 */
/* default */ final class ResourceArbiterPolicy {

    private ResourceArbiterPolicy() {
    }

    /**
     * Maps a fixed {@link WatermarkLevel} to the {@link ResourceArbiter.Action} for the
     * given {@link ResourceArbiter.Context}, applying the stricter {@code KERNEL_LOGIC}
     * threshold documented on {@link ResourceArbiter}: {@link WatermarkLevel#CRITICAL}
     * already sheds load for {@code KERNEL_LOGIC}, where {@code TRANSPORT_IO} only rejects
     * until {@link WatermarkLevel#SHEDDING}.
     *
     * @param context the arbitration context evaluated
     * @param level   the current watermark level
     * @return the action for this level/context pair; never {@code null}
     */
    /* default */ static ResourceArbiter.Action actionForLevel(ResourceArbiter.Context context,
                                                               WatermarkLevel level) {
        return switch (level) {
            case NORMAL -> ResourceArbiter.Action.ALLOW;
            case WARNING -> ResourceArbiter.Action.THROTTLE;
            case CRITICAL -> context == ResourceArbiter.Context.KERNEL_LOGIC
                    ? ResourceArbiter.Action.SHED_LOAD
                    : ResourceArbiter.Action.REJECT;
            case SHEDDING -> ResourceArbiter.Action.SHED_LOAD;
        };
    }

    /**
     * Delegates to {@link ScalingContext#actionFor(double)}, converting the integer
     * utilization percentage to the {@code [0.0, 1.0]} ratio that overload expects.
     *
     * @param scalingContext the tenant-specific thresholds to apply
     * @param utilizationPct current memory utilization in percent {@code [0..100]}
     * @return the action for this tier at this utilization; never {@code null}
     */
    /* default */ static ResourceArbiter.Action actionForScalingContext(ScalingContext scalingContext,
                                                                        int utilizationPct) {
        return scalingContext.actionFor(utilizationPct / 100.0);
    }
}