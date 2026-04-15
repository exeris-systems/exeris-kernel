/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.tck;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicLong;

@DisplayName("Core: PAQS Scheduler TCK")
class CorePaqsSchedulerTckTest extends AbstractPaqsSchedulerTck {

    @Override
    protected MemoryAllocator createDynamicAllocator(AtomicLong allocated, long totalBytes) {
        return new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                long current = allocated.get();
                return new MemoryStats(totalBytes, current, totalBytes - current,
                        0, 0, current, 0, 0, LeakDetectionMode.DISABLED);
            }

            @Override
            public LoanedBuffer allocate(AllocationHint hint) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateNetwork(int bytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateCarrierSlab(int sizeClass) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateInfrastructure(long size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // test stub
            }
        };
    }
}
