/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import java.util.concurrent.atomic.AtomicLong;

final class CommunityReleaseAction implements Runnable {

    private final long bytes;
    private final AtomicLong releaseCount;
    private final AtomicLong allocatedBytes;
    private final boolean jfrEnabled;

    /* default */ CommunityReleaseAction(long bytes,
                                         AtomicLong releaseCount,
                                         AtomicLong allocatedBytes,
                                         boolean jfrEnabled) {
        this.bytes = bytes;
        this.releaseCount = releaseCount;
        this.allocatedBytes = allocatedBytes;
        this.jfrEnabled = jfrEnabled;
    }

    @Override
    public void run() {
        long count = releaseCount.incrementAndGet();
        allocatedBytes.addAndGet(-bytes);
        if (jfrEnabled) {
            CommunityReleaseEvent.emit(bytes, count);
        }
    }
}
