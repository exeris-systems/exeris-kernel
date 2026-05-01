/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.core.memory.AbstractLoanedBuffer;

final class CommunityArenaBuffers {

    private CommunityArenaBuffers() {
    }

    /* default */ static AbstractLoanedBuffer allocateOwned(
            long capacityBytes, long alignmentBytes, CommunityArenaShardPool pool) {
        CommunityArenaShardPool.Allocation allocation = pool.allocateSegment(capacityBytes, alignmentBytes);
        return CommunityLoanedBuffer.allocateOwnedPooled(
            allocation.segment(),
                capacityBytes,
            allocation.originShard(),
                pool);
    }

}
