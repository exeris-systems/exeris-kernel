/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testing;

import eu.exeris.kernel.spi.time.TimeSource;

import java.time.Instant;

/**
 * A {@link TimeSource} that never moves: every {@code nanoTime()} read returns zero, so a limiter that
 * compares arrival times sees one instant and a case decides its outcome without a clock.
 */
public final class HeldTimeSource implements TimeSource {

    /** Creates a source held at zero. */
    public HeldTimeSource() {
        // no state: the held instant is the epoch
    }

    @Override
    public long nanoTime() {
        return 0L;
    }

    @Override
    public Instant wallTime() {
        return Instant.EPOCH;
    }
}
