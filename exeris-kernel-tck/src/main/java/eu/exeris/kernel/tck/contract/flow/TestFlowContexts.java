/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal heap-based FlowContext factory for TCK test fixtures. @since 0.5.0 */
final class TestFlowContexts {
    private TestFlowContexts() {}
    static FlowContext create(String instanceId, String definitionName) {
        return createWithState(instanceId, definitionName, FlowState.RUNNING);
    }
    static FlowContext createWithState(String instanceId, String definitionName, FlowState state) {
        UUID uuid = UUID.nameUUIDFromBytes(instanceId.getBytes(StandardCharsets.UTF_8));
        return new FlowContext() {
            @Override public long instanceIdMost()    { return uuid.getMostSignificantBits(); }
            @Override public long instanceIdLeast()   { return uuid.getLeastSignificantBits(); }
            @Override public String definitionName()  { return definitionName; }
            @Override public int currentStep()        { return 0; }
            @Override public FlowState state()        { return state; }
            @Override public long timeoutNanos()      { return System.nanoTime() + 30_000_000_000L; }
        };
    }
}
