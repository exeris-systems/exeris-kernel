/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;

/**
 * The 128-bit flow instance identity as a {@code (most, least)} long pair — the map key the
 * runtime indexes live and parked instances by.
 *
 * @param instanceIdMost  most-significant 64 bits of the flow instance UUID
 * @param instanceIdLeast least-significant 64 bits of the flow instance UUID
 */
@SuppressWarnings("PMD.PublicMemberInNonPublicType")
record FlowKey(long instanceIdMost, long instanceIdLeast) {

    /**
     * Derives the key from a flow context's identity fields.
     *
     * @param context the context to read {@code instanceIdMost}/{@code instanceIdLeast} from
     * @return a key equal to any other key derived from the same instance identity
     */
    public static FlowKey from(FlowContext context) {
        return new FlowKey(context.instanceIdMost(), context.instanceIdLeast());
    }
}
