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
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

import java.lang.foreign.ValueLayout;

/**
 * TCK: Abstract base for Zero-GC JFR contract — Memory subsystem.
 *
 * <h2>Phase Separation (The Key Invariant)</h2>
 * <p>This test enforces a strict two-phase discipline:
 * <ol>
 *   <li><b>Bootstrap phase</b> — {@link #createAllocator()} is called <em>before</em>
 *       any JFR recording starts. Enterprise allocators build their entire
 *       {@code BufferPool} at this point. JFR is dark — none of these allocations
 *       appear in the recording.</li>
 *   <li><b>Active (steady-state) phase</b> — only the hot-path loop runs inside the
 *       recording window.</li>
 * </ol>
 *
 * <h2>Tier Differentiation</h2>
 * <ul>
 *   <li><b>Community ({@code supportsZeroGcHotPath() == false})</b>: bounded allocs</li>
 *   <li><b>Enterprise ({@code supportsZeroGcHotPath() == true})</b>: zero allocs</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractZeroGcJfrMonitorTck extends AbstractSubsystemZeroAllocTck {

    /**
     * Subclass supplies the allocator under test.
     */
    protected abstract MemoryAllocator createAllocator();

    private MemoryAllocator allocator;
    private int iterationCounter;

    @Override
    protected String subsystemName() {
        return "Memory";
    }

    @Override
    protected String hotPathDescription() {
        return "MemoryAllocator.allocate(MICRO) → write → close";
    }

    @Override
    protected int maxExerisAllocationsPerIteration() {
        return 3;
    }

    @Override
    protected void bootstrapSubsystem() {
        allocator = createAllocator();
        iterationCounter = 0;
    }

    @Override
    protected void runSingleIteration() {
        try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
            buf.segment().set(ValueLayout.JAVA_LONG, 0, iterationCounter++);
        }
    }

    @Override
    protected void tearDownSubsystem() {
        allocator.close();
    }
}
