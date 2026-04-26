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
import eu.exeris.kernel.tck.contract.memory.MemoryCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link MemoryCarrierPinningTck} backed by
 * {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Verifies that {@link eu.exeris.kernel.spi.memory.AllocationHint#MICRO} allocation,
 * write, and close never pins a carrier thread on the slab pool lock path.
 *
 * @since 0.6.0
 */
@DisplayName("Community: Memory carrier pinning TCK")
class CommunityMemoryCarrierPinningTckTest extends MemoryCarrierPinningTck {

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
