/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.core.memory.AbstractLoanedBuffer;

/**
 * Community: assembles a pool-backed {@link CommunityLoanedBuffer} from a
 * {@link CommunityArenaShardPool} allocation.
 *
 * <p>Holds no state of its own; {@link #allocateOwned} is the single seam between the
 * shard pool's segment allocation and the buffer's reference-count lifecycle.
 */
final class CommunityArenaBuffers {

    private CommunityArenaBuffers() {
    }

    /* default */ static AbstractLoanedBuffer allocateOwned(
            long capacityBytes, long alignmentBytes, CommunityArenaShardPool pool,
            CommunityReleaseAccounting releaseAccounting) {
        CommunityArenaShardPool.Allocation allocation = pool.allocateSegment(capacityBytes, alignmentBytes);
        return CommunityLoanedBuffer.allocateOwnedPooled(
            allocation.segment(),
                capacityBytes,
            allocation.originShard(),
                pool,
                releaseAccounting);
    }

}
