/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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

    /**
     * Pre-computed {@link WatermarkLevel} values array — avoids {@code WatermarkLevel.values()}
     * defensive array copy on every {@link #currentLevel()} call from the hot path.
     */
    private static final WatermarkLevel[] LEVELS = WatermarkLevel.values();

    static {
        try {
            LEVEL_ORDINAL = MethodHandles.lookup()
                    .findVarHandle(WatermarkManager.class, "levelOrdinal", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Current watermark level ordinal — mutated via VarHandle CAS.
     * Declared package-private so PMD does not flag it as unused.
     */
    /* default */ volatile int levelOrdinal = WatermarkLevel.NORMAL.ordinal();

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

        int prevOrdinal = (int) LEVEL_ORDINAL.getAcquire(this);
        int newOrdinal = newLevel.ordinal();

        if (prevOrdinal != newOrdinal
                && LEVEL_ORDINAL.compareAndSet(this, prevOrdinal, newOrdinal)) {
            WatermarkLevel prevLevel = LEVELS[prevOrdinal];
            MemoryPressureEvent.emit(
                    stats.allocatedBytes(),
                    stats.totalBytes(),
                    prevLevel,
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
