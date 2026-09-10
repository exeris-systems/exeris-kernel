/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.memory.AbstractLoanedBufferTck;
import org.junit.jupiter.api.DisplayName;

/**
 * TCK binding: {@link CommunityMemoryAllocator} against the full
 * {@link AbstractLoanedBufferTck} contract suite.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Every {@link eu.exeris.kernel.spi.memory.LoanedBuffer} produced by the Community
 * allocator — backed by {@code CommunityLoanedBuffer} extending {@code AbstractLoanedBuffer}
 * — correctly implements the complete SPI contract: reference counting, slice zero-copy,
 * view/peek semantics, close actions, and liveness checks.
 *
 * @since 0.5.0
 * @see AbstractLoanedBufferTck
 */
@DisplayName("Community: LoanedBuffer TCK")
class CommunityLoanedBufferTckTest extends AbstractLoanedBufferTck {

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
