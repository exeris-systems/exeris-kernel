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
import eu.exeris.kernel.tck.contract.memory.AbstractPanamaSegfaultShieldTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractPanamaSegfaultShieldTck} backed by
 * {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Verifies that {@code AbstractLoanedBuffer} releases logical ownership when the
 * last slice's refCount drops to zero (no premature invalidation, no allocator-visible leak),
 * without assuming deterministic {@code Arena.close()} behaviour for {@code Arena.ofAuto()}.
 *
 * @since 0.5.0
 */
@DisplayName("Community: Panama Segfault Shield TCK")
class CommunityPanamaSegfaultShieldTckTest extends AbstractPanamaSegfaultShieldTck {

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
