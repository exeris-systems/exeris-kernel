/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

/* default */ final class ResourceArbiterPolicy {

    private ResourceArbiterPolicy() {
    }

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

    /* default */ static ResourceArbiter.Action actionForScalingContext(ScalingContext scalingContext,
                                                                        int utilizationPct) {
        return scalingContext.actionFor(utilizationPct / 100.0);
    }
}