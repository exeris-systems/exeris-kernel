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

import java.lang.foreign.Arena;

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

    /* default */ static AbstractLoanedBuffer allocateOwned(long capacityBytes, long alignmentBytes) {
        // Transport handoff crosses VT boundaries: carrier thread often allocates while a stream VT closes.
        // Use auto arena to keep cross-thread safety while avoiding explicit close pressure on hot release paths.
        //CHECKSTYLE:OFF
        Arena arena = Arena.ofAuto();
        //CHECKSTYLE:ON
        return CommunityLoanedBuffer.allocateOwned(
                capacityBytes,
                alignmentBytes,
                arena
        );
    }
}
