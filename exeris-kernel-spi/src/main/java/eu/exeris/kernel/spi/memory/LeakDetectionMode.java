/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.memory;

/**
 * Leak detection strictness levels for the {@link MemoryAllocator}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum constants are JVM singletons — no heap allocation on comparison.
 *
 * @since 0.5
 * @see MemoryProviderConfig#leakDetection()
 */
public enum LeakDetectionMode {

    /**
     * Abandoned buffers go unreported: nothing is registered at allocation, so buffer
     * creation and release carry no tracking overhead at all.
     *
     * @apiNote The production setting. A leak under this mode is invisible until it shows up
     *          as exhaustion ({@code EX-MEM-1001}).
     */
    DISABLED,

    /**
     * A sampled subset of allocations is tracked, so a persistent leak surfaces
     * statistically while the untracked majority pays nothing.
     *
     * @apiNote Cheap enough for a canary deployment. It will not catch a specific one-off
     *          leak — for that, use {@link #PARANOID}.
     * @implNote The kernel's Core leak tracker registers one allocation in 128 with a
     *           {@link java.lang.ref.Cleaner} and captures no allocation stack in this mode;
     *           detections are reported asynchronously from the cleaner thread as
     *           {@code EX-MEM-1002} JFR events.
     */
    SAMPLED,

    /**
     * Every allocation is tracked, so any buffer collected without {@code close()} is
     * reported, with the stack that allocated it.
     *
     * @apiNote For integration tests and controlled leak hunts only: the per-allocation
     *          registration and stack capture roughly double the cost of an allocation.
     * @implNote Detections are reported as {@code EX-MEM-1002} JFR events from the cleaner
     *           thread — no {@code System.err}, no logging framework.
     */
    PARANOID
}
