/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.spi.time.TimeSource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** A {@link TimeSource} a test advances by hand: {@code nanoTime()} returns exactly what was set or added. */
final class ManualTimeSource implements TimeSource {

    private final AtomicLong nanos;

    ManualTimeSource(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    void advance(long deltaNanos) {
        nanos.addAndGet(deltaNanos);
    }

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    @Override
    public Instant wallTime() {
        return Instant.EPOCH.plusNanos(nanos.get());
    }
}
