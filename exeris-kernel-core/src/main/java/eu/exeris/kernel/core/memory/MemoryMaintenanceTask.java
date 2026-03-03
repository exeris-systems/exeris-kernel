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
import java.util.concurrent.locks.LockSupport;

/**
 * Core: Periodic background maintenance task running on a dedicated Virtual Thread.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Calls {@link MemoryAllocator#performMaintenance()} every
 *       {@link #maintenanceIntervalMs()} ms — triggers per-partition JFR telemetry
 *       in the Enterprise driver.</li>
 *   <li>Calls {@link WatermarkManager#refresh()} every
 *       {@link #watermarkIntervalMs()} ms — updates the pressure level consumed by
 *       {@link ResourceArbiter#decide(ResourceArbiter.Context)}.</li>
 *   <li>Emits a {@code MemoryMaintenanceCycleEvent} JFR event on each maintenance
 *       iteration when {@link MemoryAllocator#performMaintenance()} runs.</li>
 * </ol>
 *
 * <h2>No {@code ScheduledExecutorService}</h2>
 * <p>Per the architectural ban on {@code ExecutorService} and {@code Executors}, timing
 * is implemented as a Virtual Thread loop using {@link LockSupport#parkNanos(long)}.
 * {@code parkNanos} is used instead of {@code Thread.sleep()} to avoid creating a
 * {@code Thread.sleep()} allocation (the SleepEvent) and to allow unparking for
 * graceful shutdown without {@code InterruptedException} handling complexity.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   start()  — launches the Virtual Thread, returns immediately
 *   stop()   — sets the stop flag, unparks the sleeping VT, waits for termination
 *   close()  — alias for stop() (implements AutoCloseable for try-with-resources)
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>The {@code running} flag is a {@code volatile boolean} read by the VT loop and
 * written by {@code stop()}, and it is also updated via a {@link VarHandle} compare-and-set
 * in {@code start()} to enforce at-most-once startup. Volatile provides the required
 * visibility; CAS is used solely for idempotent lifecycle control.</p>
 *
 * @see WatermarkManager
 * @see MemoryAllocator#performMaintenance()
 * @since 0.5.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
public final class MemoryMaintenanceTask implements AutoCloseable {

    /**
     * Default interval between {@link MemoryAllocator#performMaintenance()} calls.
     */
    public static final long DEFAULT_MAINTENANCE_INTERVAL_MS = 10_000L;

    /**
     * Default interval between {@link WatermarkManager#refresh()} calls.
     */
    public static final long DEFAULT_WATERMARK_INTERVAL_MS = 5_000L;

    private static final VarHandle RUNNING;
    private static final VarHandle MAINTENANCE_THREAD;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            RUNNING = lookup.findVarHandle(MemoryMaintenanceTask.class, "loopActive", boolean.class);
            MAINTENANCE_THREAD = lookup.findVarHandle(MemoryMaintenanceTask.class, "maintenanceThread", Thread.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /* default */ volatile boolean loopActive;
    /* default */ Thread maintenanceThread;

    private final MemoryAllocator allocator;
    private final WatermarkManager watermarkManager;
    private final long maintenanceIntervalMs;
    private final long watermarkIntervalMs;


    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a maintenance task with default intervals.
     *
     * @param allocator        the allocator to maintain; must not be {@code null}
     * @param watermarkManager the watermark monitor to refresh; must not be {@code null}
     */
    public MemoryMaintenanceTask(MemoryAllocator allocator, WatermarkManager watermarkManager) {
        this(allocator, watermarkManager,
                DEFAULT_MAINTENANCE_INTERVAL_MS, DEFAULT_WATERMARK_INTERVAL_MS);
    }

    /**
     * Creates a maintenance task with custom intervals.
     *
     * @param allocator             the allocator to maintain; must not be {@code null}
     * @param watermarkManager      the watermark monitor to refresh; must not be {@code null}
     * @param maintenanceIntervalMs interval between performMaintenance() calls in ms
     * @param watermarkIntervalMs   interval between watermark refresh calls in ms
     */
    public MemoryMaintenanceTask(MemoryAllocator allocator,
                                 WatermarkManager watermarkManager,
                                 long maintenanceIntervalMs,
                                 long watermarkIntervalMs) {
        if (allocator == null) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        if (watermarkManager == null) {
            throw new IllegalArgumentException("watermarkManager must not be null");
        }
        if (maintenanceIntervalMs <= 0) {
            throw new IllegalArgumentException("maintenanceIntervalMs must be > 0");
        }
        if (watermarkIntervalMs <= 0) {
            throw new IllegalArgumentException("watermarkIntervalMs must be > 0");
        }
        this.allocator = allocator;
        this.watermarkManager = watermarkManager;
        this.maintenanceIntervalMs = maintenanceIntervalMs;
        this.watermarkIntervalMs = watermarkIntervalMs;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Starts the maintenance Virtual Thread.
     *
     * <p>Idempotent — safe to call multiple times, including after {@link #stop()}.
     * If the task is already running the call is a no-op. After a previous
     * {@link #stop()}, calling {@code start()} again will launch a new maintenance thread.
     */
    public void start() {
        if (!RUNNING.compareAndSet(this, false, true)) {
            return;
        }
        Thread thread = Thread.ofVirtual()
                .name("exeris-memory-maintenance")
                .unstarted(this::maintenanceLoop);
        MAINTENANCE_THREAD.setRelease(this, thread);
        thread.start();
    }

    /**
     * Signals the maintenance loop to stop and waits for the Virtual Thread to terminate.
     *
     * <p>Idempotent — safe to call multiple times.
     */
    @SuppressWarnings("java:S2142")
    public void stop() {
        RUNNING.setRelease(this, false);
        Thread mainThread = (Thread) MAINTENANCE_THREAD.getAndSet(this, null);
        if (mainThread != null && mainThread.isAlive()) {
            LockSupport.unpark(mainThread);
            boolean interrupted = false;
            boolean joined = false;
            while (!joined) {
                try {
                    mainThread.join();
                    joined = true;
                } catch (InterruptedException _) {
                    interrupted = true;
                    LockSupport.unpark(mainThread);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Alias for {@link #stop()} — enables try-with-resources usage.
     */
    @Override
    public void close() {
        stop();
    }

    // =========================================================================
    // Accessors (for tests and monitoring)
    // =========================================================================

    /**
     * Returns the configured maintenance interval in milliseconds.
     */
    public long maintenanceIntervalMs() {
        return maintenanceIntervalMs;
    }

    /**
     * Returns the configured watermark refresh interval in milliseconds.
     */
    public long watermarkIntervalMs() {
        return watermarkIntervalMs;
    }

    /**
     * Returns {@code true} if the maintenance thread is currently active.
     */
    public boolean isRunning() {
        return (boolean) RUNNING.getAcquire(this);
    }

    // =========================================================================
    // Maintenance loop — runs on a dedicated Virtual Thread
    // =========================================================================

    private void maintenanceLoop() {
        long lastMaintenanceNs = System.nanoTime();
        long lastWatermarkNs = System.nanoTime();
        long maintenanceNs = maintenanceIntervalMs * 1_000_000L;
        long watermarkNs = watermarkIntervalMs * 1_000_000L;

        while ((boolean) RUNNING.getAcquire(this)) {
            long nowNs = System.nanoTime();
            lastWatermarkNs = maybeRefreshWatermark(nowNs, lastWatermarkNs, watermarkNs);
            lastMaintenanceNs = maybeRunMaintenance(nowNs, lastMaintenanceNs, maintenanceNs);
            parkUntilNextDeadline(nowNs, lastWatermarkNs, lastMaintenanceNs, watermarkNs, maintenanceNs);
        }
    }

    private long maybeRefreshWatermark(long nowNs, long lastNs, long intervalNs) {
        if (nowNs - lastNs >= intervalNs) {
            runQuietly(watermarkManager::refresh);
            return nowNs;
        }
        return lastNs;
    }

    private long maybeRunMaintenance(long nowNs, long lastNs, long intervalNs) {
        if (nowNs - lastNs >= intervalNs) {
            runFullMaintenance();
            return nowNs;
        }
        return lastNs;
    }

    private void parkUntilNextDeadline(long nowNs,
                                       long lastWatermarkNs,
                                       long lastMaintenanceNs,
                                       long watermarkNs,
                                       long maintenanceNs) {
        long nextDeadlineNs = Math.min(
                lastWatermarkNs + watermarkNs,
                lastMaintenanceNs + maintenanceNs);
        long parkNs = nextDeadlineNs - nowNs;
        if (parkNs > 0 && (boolean) RUNNING.getAcquire(this)) {
            LockSupport.parkNanos(parkNs);
        }
    }

    private void runFullMaintenance() {
        long startNs = System.nanoTime();
        WatermarkLevel level = watermarkManager.currentLevel();
        runQuietly(allocator::performMaintenance);
        long durationUs = (System.nanoTime() - startNs) / 1_000L;
        MemoryStats stats = allocator.stats();
        MemoryMaintenanceEvents.CycleEvent.emit(level, durationUs,
                stats.allocatedBytes(),
                stats.totalBytes());
    }

    private static void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException runtimeEx) { //NOPMD AvoidCatchingGenericException
            MemoryMaintenanceEvents.FailureEvent.emit(runtimeEx);
        }
    }
}
