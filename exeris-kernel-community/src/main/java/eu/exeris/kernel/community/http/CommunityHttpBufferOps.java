/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/* default */ final class CommunityHttpBufferOps {

    private CommunityHttpBufferOps() {
    }

    /* default */ static long findCrLf(MemorySegment segment, long start, long endExclusive) {
        for (long index = start; index + 1 < endExclusive; index++) {
            if (segment.get(ValueLayout.JAVA_BYTE, index) == '\r'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 1) == '\n') {
                return index;
            }
        }
        return -1;
    }

    /* default */ static long retainUnreadBytes(LoanedBuffer aggregate, long consumedBytes) {
        long total = aggregate.size();
        return compactUnreadBytes(aggregate, total, consumedBytes);
    }

    /* default */ static long compactUnreadBytes(LoanedBuffer aggregate,
                                                 long bufferedBytes,
                                                 long offset) {
        long unreadBytes = Math.max(bufferedBytes - offset, 0);
        if (unreadBytes > 0 && offset > 0) {
            MemorySegment.copy(
                    aggregate.segment(), offset,
                    aggregate.segment(), 0,
                    unreadBytes);
        }
        aggregate.setSize(unreadBytes);
        return unreadBytes;
    }
}