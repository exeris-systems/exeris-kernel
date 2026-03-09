/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.tck.contract.memory.AbstractMemoryPressureSignalTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractMemoryPressureSignalTck} backed by
 * {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Verifies that {@link eu.exeris.kernel.spi.memory.MemoryStats} pressure signals
 * emitted by the Community allocator correctly reflect allocation state — the data
 * that {@code WatermarkManager} in Core relies on for load-shedding decisions.
 *
 * <p>Community operates with heap-backed arenas ({@code totalBytes == -1}),
 * so {@link #hasFixedBudget()} returns {@code false} and the budget-consistency
 * L3 tests are skipped as not applicable.
 *
 * @since 0.5.0
 */
@DisplayName("Community: MemoryPressureSignal TCK")
class CommunityMemoryPressureSignalTckTest extends AbstractMemoryPressureSignalTck {

    @Override
    protected MemoryProvider createProvider() {
        return new CommunityMemoryProvider();
    }

    @Override
    protected boolean hasFixedBudget() {
        return false; // Community: heap-backed, no fixed off-heap budget
    }
}
