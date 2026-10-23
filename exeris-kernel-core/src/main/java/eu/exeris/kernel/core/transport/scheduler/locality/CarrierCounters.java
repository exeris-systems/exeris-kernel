/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Scheduling event counts of one carrier, written only by that carrier's own thread.
 *
 * <p>Each slot has a single writer, so an increment is a read and an opaque write rather than an
 * atomic read-modify-write; a reader on another thread sees a value that only ever grows, possibly
 * a few events behind. An instance exists only while {@code exeris.locality.stats} is on.
 */
final class CarrierCounters {

    static final int TASKS_RUN = 0;
    static final int IDLE_PARKS = 1;
    static final int STEAL_TASKS = 2;
    static final int LIFO_HITS = 3;
    static final int IDLE_SPINS = 4;
    static final int SPINS_FOUND = 5;
    static final int YIELD_BUDGET = 6;
    static final int STEAL_ATTEMPTS = 7;
    static final int STEAL_SUCCESSES = 8;
    static final int WAKE_PEER = 9;
    private static final int SLOTS = 10;

    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] slots = new long[SLOTS];

    /** Owner thread only. */
    void add(int slot, long delta) {
        SLOT.setOpaque(slots, slot, (long) SLOT.getOpaque(slots, slot) + delta);
    }

    /** Owner thread only. */
    void inc(int slot) {
        add(slot, 1);
    }

    /** Any thread. */
    long get(int slot) {
        return (long) SLOT.getOpaque(slots, slot);
    }

    void reset() {
        for (int i = 0; i < SLOTS; i++) {
            SLOT.setOpaque(slots, i, 0L);
        }
    }
}
