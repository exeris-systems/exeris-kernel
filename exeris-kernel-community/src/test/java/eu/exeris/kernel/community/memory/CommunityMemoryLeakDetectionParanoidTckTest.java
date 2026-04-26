/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.tck.contract.memory.AbstractMemoryLeakDetectionTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractMemoryLeakDetectionTck} (PARANOID mode)
 * backed by {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Verifies that {@link LeakDetectionMode#PARANOID} mode in the Community allocator
 * correctly tracks all allocations, detects un-closed buffers as leaks after GC, and
 * throws {@link IllegalStateException} on use-after-close.
 *
 * <h2>Allocation size</h2>
 * <p>Inherits the default 64 MB stress size from {@link AbstractMemoryLeakDetectionTck},
 * appropriate for Community tier in CI without requiring 1 GB heap headroom.
 *
 * @since 0.6.0
 */
@DisplayName("Community: Memory LeakDetection PARANOID TCK")
class CommunityMemoryLeakDetectionParanoidTckTest extends AbstractMemoryLeakDetectionTck {

    @Override
    protected MemoryProvider createProvider() {
        return new CommunityMemoryProvider();
    }
}
