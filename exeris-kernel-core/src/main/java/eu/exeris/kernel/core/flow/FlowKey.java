/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
value record FlowKey(long instanceIdMost, long instanceIdLeast) {

    public static FlowKey from(FlowContext context) {
        return new FlowKey(context.instanceIdMost(), context.instanceIdLeast());
    }
}
