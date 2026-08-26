/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
record FlowKey(long instanceIdMost, long instanceIdLeast) {

    public static FlowKey from(FlowContext context) {
        return new FlowKey(context.instanceIdMost(), context.instanceIdLeast());
    }
}
