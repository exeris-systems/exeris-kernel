/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Core: Memory-pressure monitor that samples {@link MemoryAllocator#stats()} and
 * resolves the current {@link WatermarkLevel}.
 *
 * <h2>Governor Role (The Brain)</h2>
 * <p>The {@code WatermarkManager} is the Core's "thermometer". It does NOT perform
 * any I/O, does NOT know whether it is running Community or Enterprise allocation drivers,
 * and does NOT shed load itself. It answers a single question:
 * <em>"How full is our off-heap tier right now?"</em>
 *
 * <h2>Atomic Level Cache (VarHandle)</h2>
 * <p>The current {@link WatermarkLevel} is stored in a {@code volatile int} (ordinal)
 * mutated via {@link VarHandle} CAS. This guarantees:
 * <ul>
 *   <li>Lock-free reads from the {@link ResourceArbiter} hot path.</li>
 *   <li>Atomic transitions: only the maintenance VT (or unit test) writes this field.</li>
 *   <li>JIT visibility: acquire/release semantics ensure ResourceArbiter sees the latest level.</li>
 * </ul>
 *
 * <h2>Level Transitions and JFR</h2>
 * <p>Every transition (e.g., NORMAL → WARNING) emits a {@link MemoryPressureEvent}.
 * Same-level refreshes are silent (no JFR event) to avoid event flooding.
 *
 * <h2>Performance Contract</h2>
 * <p>{@link #currentLevel()} is O(1) — a single VarHandle acquire read of an int ordinal.
 * {@link #refresh()} is O(1) plus one {@link MemoryAllocator#stats()} call (diagnostic path).
 *
 * <h2>Driver Agnosticism</h2>
 * <p>All knowledge of {@code ElasticArenaCluster}, {@code GlobalMemoryArbiter}, or
 * {@code io_uring} ring buffers is entirely absent from this class. It reads only
 * the {@link MemoryStats} contract.
 *
 * @see WatermarkLevel
 * @see ResourceArbiter
 * @since 0.5.0
 */
public final class WatermarkManager {

    private static final VarHandle LEVEL_ORDINAL;
    private static final VarHandle LAST_ALLOCATED_BYTES;
    private static final VarHandle LAST_TOTAL_BYTES;

    /**
     * Sentinel value for {@link eu.exeris.kernel.spi.memory.MemoryStats#totalBytes()} indicating
     * that the allocator operates without a fixed off-heap budget (e.g., Community tier).
     * Matches the {@code -1} sentinel defined in {@link eu.exeris.kernel.spi.memory.MemoryStats}.
     */
    private static final long NO_BUDGET_SENTINEL = 0L;

    /**
     * Pre-computed {@link WatermarkLevel} values array — avoids {@code WatermarkLevel.values()}
     * defensive array copy on every {@link #currentLevel()} call from the hot path.
     */
    private static final WatermarkLevel[] LEVELS = WatermarkLevel.values();

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            LEVEL_ORDINAL = lookup.findVarHandle(WatermarkManager.class, "levelOrdinal", int.class);
            LAST_ALLOCATED_BYTES = lookup.findVarHandle(WatermarkManager.class, "lastAllocatedBytes", long.class);
            LAST_TOTAL_BYTES = lookup.findVarHandle(WatermarkManager.class, "lastTotalBytes", long.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Current watermark level ordinal — mutated via VarHandle CAS.
     * Declared package-private so PMD does not flag it as unused.
     */
    /* default */ volatile int levelOrdinal = WatermarkLevel.NORMAL.ordinal();

    /**
     * Last sampled allocation stats — written by {@link #refresh()}, read by
     * {@link #currentUtilizationPct()}. VarHandle setRelease/getAcquire semantics
     * ensure the two fields are always observed in a consistent state on the same
     * thread that called {@link #refresh()}, and are visible to ResourceArbiter
     * after a cache-miss read of {@link #currentLevel()}.
     */
    /* default */ volatile long lastAllocatedBytes;
    /* default */ volatile long lastTotalBytes;

    private final MemoryAllocator allocator;

    /**
     * Creates a new {@link WatermarkManager} monitoring the given allocator.
     *
     * @param allocator the allocator whose {@link MemoryStats} will be sampled; must not be {@code null}
     */
    public WatermarkManager(MemoryAllocator allocator) {
        if (allocator == null) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        this.allocator = allocator;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the most recently computed {@link WatermarkLevel}.
     *
     * <p>This is the <b>hot-path read</b> — O(1), single VarHandle acquire.
     * Called by {@link ResourceArbiter#decide} on every request.
     *
     * @return current watermark level; never {@code null}
     */
    public WatermarkLevel currentLevel() {
        int ordinal = (int) LEVEL_ORDINAL.getAcquire(this);
        return LEVELS[ordinal];
    }

    /**
     * Returns the most recently sampled utilization as an integer percentage {@code [0..100]}.
     *
     * <p><b>Hot-path safe</b> — O(1), two VarHandle acquire reads, zero allocation.
     * Derives utilization from the raw {@code allocatedBytes} and {@code totalBytes} last
     * written by {@link #refresh()}. Returns {@code 0} if no budget is known
     * ({@code totalBytes == 0}, i.e., Community tier with no fixed ceiling).
     *
     * @return memory utilization percentage in {@code [0..100]}
     */
    public int currentUtilizationPct() {
        long allocated = (long) LAST_ALLOCATED_BYTES.getAcquire(this);
        long total = (long) LAST_TOTAL_BYTES.getAcquire(this);
        if (total <= NO_BUDGET_SENTINEL) {
            return 0;
        }
        return (int) Math.clamp(allocated * 100L / total, 0L, 100L);
    }

    /**
     * Samples the allocator's {@link MemoryStats} and updates the cached
     * {@link WatermarkLevel} atomically.
     *
     * <p>Called by the kernel background maintenance Virtual Thread — typically every
     * 10 seconds. NOT called from any carrier thread or io_uring event loop.
     *
     * <h2>JFR Event</h2>
     * <p>A {@link MemoryPressureEvent} is emitted only when the level actually changes.
     * Repeated calls at the same level are silent.
     *
     * @return the newly computed level (may equal the previous level)
     */
    public WatermarkLevel refresh() {
        MemoryStats stats = allocator.stats();
        double utilization = stats.utilization();
        WatermarkLevel newLevel = WatermarkLevel.forUtilization(utilization);

        LAST_ALLOCATED_BYTES.setRelease(this, stats.allocatedBytes());
        LAST_TOTAL_BYTES.setRelease(this, stats.totalBytes());

        int newOrdinal = newLevel.ordinal();
        int prevOrdinal;
        do {
            prevOrdinal = (int) LEVEL_ORDINAL.getAcquire(this);
        } while (prevOrdinal != newOrdinal
                && !LEVEL_ORDINAL.compareAndSet(this, prevOrdinal, newOrdinal));
        if (prevOrdinal != newOrdinal) {
            MemoryPressureEvent.emit(
                    stats.allocatedBytes(),
                    stats.totalBytes(),
                    LEVELS[prevOrdinal],
                    newLevel);
        }
        return newLevel;
    }

    /**
     * Forces the internal level to the given value without reading the allocator.
     *
     * <p><b>Use in tests only.</b> This method bypasses the normal stats-sampling path
     * to allow deterministic scenario testing of {@link ResourceArbiter} reactions.
     *
     * @param level the level to force; must not be {@code null}
     */
    /* default */ void forceLevel(WatermarkLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        LEVEL_ORDINAL.setRelease(this, level.ordinal());
    }
}
