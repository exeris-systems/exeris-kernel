/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.tck.contract.memory.AbstractMemoryGovernorTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractMemoryGovernorTck} backed by
 * {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Verifies that the governor signal exposed by {@link eu.exeris.kernel.spi.memory.MemoryStats}
 * is correct at all times — baseline (L1), under allocation load (L2), and after
 * {@code performMaintenance()} (L4). The fixed-budget L3 tests are skipped because
 * Community operates without a bounded off-heap budget ({@code totalBytes == -1}).
 *
 * <p>This is the contract that the Core {@code WatermarkManager} and {@code ResourceArbiter}
 * rely on when making load-shedding decisions. A Community allocator that reports stale
 * or incorrect {@code allocatedBytes} would blind the governor.
 *
 * @since 0.5.0
 */
@DisplayName("Community: MemoryGovernor Signal TCK")
class CommunityMemoryGovernorTckTest extends AbstractMemoryGovernorTck {

    @Override
    protected MemoryProvider createProvider() {
        return new CommunityMemoryProvider();
    }

    @Override
    protected boolean hasFixedBudget() {
        return false;
    }
}

