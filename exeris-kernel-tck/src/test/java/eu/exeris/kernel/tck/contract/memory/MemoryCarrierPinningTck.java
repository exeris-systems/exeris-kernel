/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.ValueLayout;

/**
 * TCK: Carrier pinning verifier for the Memory allocation hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code allocator.allocate(MICRO) → write → close} — slab pool acquire/release
 * must never pin a carrier thread (no synchronized on the pool lock path).
 *
 * @see AbstractSubsystemCarrierPinningTck
 * @see AbstractZeroGcJfrMonitorTck
 * @since 0.5.0
 */
@DisplayName("Memory carrier pinning TCK")
public abstract class MemoryCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    protected abstract MemoryAllocator createAllocator();

    private MemoryAllocator allocator;
    private int counter;

    @Override
    protected String subsystemName() {
        return "Memory";
    }

    @Override
    protected String hotPathDescription() {
        return "MemoryAllocator.allocate(MICRO) → write → close";
    }

    @Override
    protected void bootstrapSubsystem() {
        allocator = createAllocator();
        counter = 0;
    }

    @Override
    protected void runSingleIteration() {
        try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
            buf.segment().set(ValueLayout.JAVA_LONG, 0, counter++);
        }
    }

    @Override
    protected void tearDownSubsystem() {
        allocator.close();
    }
}
