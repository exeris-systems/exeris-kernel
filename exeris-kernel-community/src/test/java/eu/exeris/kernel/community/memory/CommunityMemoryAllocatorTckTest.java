/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.memory.AbstractMemoryAllocatorTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractMemoryAllocatorTck} backed by
 * {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Every allocation is backed by a new {@code Arena.ofShared()}, ensuring cross-thread
 * ownership safety. The test suite verifies that despite OS-level mmap/munmap on every
 * alloc/release, the contract holds: no leaks, correct isolation, correct stats.
 *
 * @since 0.5.0
 */
@DisplayName("Community: MemoryAllocator TCK")
class CommunityMemoryAllocatorTckTest extends AbstractMemoryAllocatorTck {

    private static final MemoryProviderConfig CONFIG = MemoryProviderConfig.defaults();

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(CONFIG);
    }

    @Override
    protected boolean hasFixedSlabBudget() {
        return false; // Community: unlimited — OOM test skipped
    }
}
