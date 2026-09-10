/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Lightweight fairness tracking for admission control.
 *
 * <p>Tracks per-1-second fairness buckets using atomic counters.
 * Computes composite fairness ratio via moving window to detect
 * starvation and guide adaptive backoff.
 *
 * <h2>Memory Discipline</h2>
 * <p>Uses ConcurrentHashMap with bounded size (no unbounded growth);
 * buckets are periodically reclaimed and old entries removed.
 * VarHandle for lock-free counters on hot path.
 *
 * @since 0.5
 */
final class FairnessTracker {

    // One bucket per 1-second window; tracks: accepted count, queue depth sum
    private static final long BUCKET_DURATION_NANOS = 1_000_000_000L; // 1 second
    private static final long RECENT_WINDOW_NANOS = 10L * BUCKET_DURATION_NANOS;
    private static final int MAX_BUCKET_SIZE = 100; // Keep last 100 seconds max
    private static final int DEPTH_BIN_COUNT = 8;
    private static final int WAIT_BIN_COUNT = 10;
    private static final long NO_SAMPLES = 0L;
    private static final int NO_QUEUE_DEPTH = 0;
    private static final long NO_WAIT_NANOS = 0L;
    private static final long NO_WAIT_MS = 0L;
    private static final int FIRST_BIN_INDEX = 0;
    private static final int LAST_DEPTH_BIN = DEPTH_BIN_COUNT - 1;
    private static final int LAST_WAIT_BIN = WAIT_BIN_COUNT - 1;
    private static final long QUEUE_WAIT_P95_PERCENTILE = 95L;
    private static final long QUEUE_DEPTH_P95_PERCENTILE = 95L;
    private static final long SNAPSHOT_REFRESH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(5L);
    private static final long SNAPSHOT_REFRESH_DECISION_STRIDE = 16L;
    private static final long[] DEPTH_BIN_UPPER_BOUNDS = {
            0L, 1L, 3L, 7L, 15L, 31L, 63L, Long.MAX_VALUE
    };
    private static final long[] WAIT_BIN_UPPER_BOUNDS_MS = {
            0L, 1L, 2L, 5L, 10L, 20L, 50L, 100L, 250L, Long.MAX_VALUE
    };
    private static final long[] EMPTY_BINS = new long[0];

    private final ConcurrentMap<Long, BucketMetrics> metricsBySecond;
    private final LongSupplier nanoTimeSource;
    private final AtomicLong lastRecordNanos;
    private final AtomicLong decisionSequence;
    private final WindowMetricsAggregator windowAggregator;
    private final SnapshotRefreshPolicy snapshotRefreshPolicy;
    private volatile long lastCleanupBucketKey;
    private final AtomicReference<CachedSnapshot> cachedSnapshot;
    /** Preallocated histogram arrays reused exclusively under {@link #snapshotLock} during snapshot refresh. */
    private final long[] depthBinsReuse = new long[DEPTH_BIN_COUNT];
    private final long[] waitBinsReuse  = new long[WAIT_BIN_COUNT];
    private final Object snapshotLock   = new Object();

    /* default */ FairnessTracker() {
        this(System::nanoTime);
    }

    /* default */ FairnessTracker(LongSupplier nanoTimeSource) {
        this.metricsBySecond = new ConcurrentHashMap<>();
        this.nanoTimeSource = nanoTimeSource;
        this.lastRecordNanos = new AtomicLong(Long.MIN_VALUE);
        this.decisionSequence = new AtomicLong(0L);
        this.windowAggregator = new WindowMetricsAggregator(metricsBySecond);
        this.snapshotRefreshPolicy =
                new SnapshotRefreshPolicy(SNAPSHOT_REFRESH_INTERVAL_NANOS, SNAPSHOT_REFRESH_DECISION_STRIDE);
        this.lastCleanupBucketKey = Long.MIN_VALUE;
        this.cachedSnapshot = new AtomicReference<>(CachedSnapshot.initial());
    }

    /**
     * Record an admission decision (accepted or rejected).
     */
    /* default */ void recordDecision(boolean accepted, int queueDepth) {
        long now = nanoTimeSource.getAsLong();
        long bucketKey = now / BUCKET_DURATION_NANOS * BUCKET_DURATION_NANOS;
        BucketMetrics bucket = metricsBySecond.computeIfAbsent(bucketKey, _ -> new BucketMetrics());
        long observedQueueWaitNanos = observeQueueWaitNanos(now, queueDepth);
        if (accepted) {
            bucket.recordAccepted();
        } else {
            bucket.recordRejected();
        }
        bucket.recordQueueDepth(queueDepth);
        bucket.recordQueueWaitNanos(observedQueueWaitNanos);
        decisionSequence.incrementAndGet();
        if (bucketKey != lastCleanupBucketKey || metricsBySecond.size() > MAX_BUCKET_SIZE) {
            lastCleanupBucketKey = bucketKey;
            long cutoff = now - RECENT_WINDOW_NANOS;
            metricsBySecond.entrySet().removeIf(entry -> entry.getKey() < cutoff);
        }
    }

    /**
     * Compute current fairness composite ratio over the last 10 seconds.
     * Returns ratio in range [0.0, 1.0], where 1.0 = perfect fairness,
     * 0.5 = severe skew (min tier gets half mean throughput).
     *
     * @return fairness composite (target ≥0.90)
     */
    /* default */ double computeFairnessRatio() {
        long windowStart = nanoTimeSource.getAsLong() - RECENT_WINDOW_NANOS;
        return windowAggregator.computeFairnessRatio(windowStart);
    }

    /**
     * Compute P95 queue depth over the observation window.
     */
    /* default */ long computeQueueDepthP95() {
        long windowStart = nanoTimeSource.getAsLong() - RECENT_WINDOW_NANOS;
        synchronized (snapshotLock) {
            return windowAggregator.computeQueueDepthP95(windowStart, depthBinsReuse);
        }
    }

    /* default */ long computeQueueWaitP95Ms() {
        long windowStart = nanoTimeSource.getAsLong() - RECENT_WINDOW_NANOS;
        synchronized (snapshotLock) {
            return windowAggregator.computeQueueWaitP95Ms(windowStart, waitBinsReuse);
        }
    }

    /* default */ boolean indicatesAdmissionStress(double fairnessThreshold, long queueDepthThreshold) {
        FairnessSnapshot snapshot = computeSnapshot();
        double fairnessRatio = snapshot.fairnessRatio();
        long queueDepthP95 = snapshot.queueDepthP95();

        return fairnessRatio < fairnessThreshold && queueDepthP95 >= queueDepthThreshold;
    }

    /* default */ FairnessSnapshot computeSnapshot() {
        long now = nanoTimeSource.getAsLong();
        long currentDecisionSequence = decisionSequence.get();
        CachedSnapshot local = cachedSnapshot.get();

        if (!snapshotRefreshPolicy.shouldRefresh(local, now, currentDecisionSequence)) {
            return local.snapshot();
        }

        synchronized (snapshotLock) {
            local = cachedSnapshot.get();
            if (!snapshotRefreshPolicy.shouldRefresh(local, now, currentDecisionSequence)) {
                return local.snapshot();
            }
            FairnessSnapshot recomputed =
                    windowAggregator.computeSnapshot(now - RECENT_WINDOW_NANOS, depthBinsReuse, waitBinsReuse);
            cachedSnapshot.set(new CachedSnapshot(now, currentDecisionSequence, recomputed));
            return recomputed;
        }
    }

    private long observeQueueWaitNanos(long now, int queueDepth) {
        long previous = lastRecordNanos.getAndSet(now);
        if (queueDepth <= NO_QUEUE_DEPTH || previous == Long.MIN_VALUE || now <= previous) {
            return NO_WAIT_NANOS;
        }
        return now - previous;
    }

    private record CachedSnapshot(long refreshedAtNanos, long decisionSequence, FairnessSnapshot snapshot) {
        private static CachedSnapshot initial() {
            return new CachedSnapshot(
                    Long.MIN_VALUE,
                    Long.MIN_VALUE,
                    new FairnessSnapshot(1.0d, NO_SAMPLES, NO_WAIT_MS));
        }
    }

    private record SnapshotRefreshPolicy(long refreshIntervalNanos, long decisionStride) {
        private boolean shouldRefresh(CachedSnapshot snapshot, long now, long currentDecisionSequence) {
            if (snapshot.refreshedAtNanos() == Long.MIN_VALUE) {
                return true;
            }
            long elapsedNanos = now - snapshot.refreshedAtNanos();
            long decisionsSinceRefresh = currentDecisionSequence - snapshot.decisionSequence();
            return elapsedNanos >= refreshIntervalNanos || decisionsSinceRefresh >= decisionStride;
        }
    }

    private record WindowAggregate(
            long totalAccepted,
            long totalRejected,
            long depthSampleCount,
            long waitSampleCount) {
    }

    /** A computed, time-windowed fairness reading — see {@link #computeSnapshot()}. */
    /* default */ record FairnessSnapshot(double fairnessRatio, long queueDepthP95, long queueWaitP95Ms) {
    }

    private static final class WindowMetricsAggregator {
        private final ConcurrentMap<Long, BucketMetrics> metricsBySecond;

        private WindowMetricsAggregator(ConcurrentMap<Long, BucketMetrics> metricsBySecond) {
            this.metricsBySecond = metricsBySecond;
        }

        private double computeFairnessRatio(long windowStart) {
            WindowAggregate aggregate = aggregateWindowMetrics(windowStart, null, EMPTY_BINS);
            return HistogramMath.fairnessRatio(aggregate.totalAccepted(), aggregate.totalRejected());
        }

        private long computeQueueDepthP95(long windowStart, long... depthBinsReuse) {
            java.util.Arrays.fill(depthBinsReuse, 0L);
            WindowAggregate aggregate = aggregateWindowMetrics(windowStart, depthBinsReuse, EMPTY_BINS);
            return HistogramMath.percentileFromHistogram(
                    aggregate.depthSampleCount(),
                    QUEUE_DEPTH_P95_PERCENTILE,
                    depthBinsReuse,
                    DEPTH_BIN_UPPER_BOUNDS,
                    LAST_DEPTH_BIN);
        }

        private long computeQueueWaitP95Ms(long windowStart, long... waitBinsReuse) {
            java.util.Arrays.fill(waitBinsReuse, 0L);
            WindowAggregate aggregate = aggregateWindowMetrics(windowStart, null, waitBinsReuse);
            return HistogramMath.percentileFromHistogram(
                    aggregate.waitSampleCount(),
                    QUEUE_WAIT_P95_PERCENTILE,
                    waitBinsReuse,
                    WAIT_BIN_UPPER_BOUNDS_MS,
                    LAST_WAIT_BIN);
        }

        private FairnessSnapshot computeSnapshot(long windowStart, long[] depthBinsReuse, long... waitBinsReuse) {
            java.util.Arrays.fill(depthBinsReuse, 0L);
            java.util.Arrays.fill(waitBinsReuse, 0L);
            WindowAggregate aggregate = aggregateWindowMetrics(windowStart, depthBinsReuse, waitBinsReuse);

            return new FairnessSnapshot(
                    HistogramMath.fairnessRatio(aggregate.totalAccepted(), aggregate.totalRejected()),
                    HistogramMath.percentileFromHistogram(
                            aggregate.depthSampleCount(),
                            QUEUE_DEPTH_P95_PERCENTILE,
                            depthBinsReuse,
                            DEPTH_BIN_UPPER_BOUNDS,
                            LAST_DEPTH_BIN),
                    HistogramMath.percentileFromHistogram(
                            aggregate.waitSampleCount(),
                            QUEUE_WAIT_P95_PERCENTILE,
                            waitBinsReuse,
                            WAIT_BIN_UPPER_BOUNDS_MS,
                            LAST_WAIT_BIN));
        }

        private WindowAggregate aggregateWindowMetrics(
                long windowStart,
                long[] depthBinsOrNull,
                long... waitBinsOrNull) {
            long totalAccepted = 0L;
            long totalRejected = 0L;
            long depthSampleCount = 0L;
            long waitSampleCount = 0L;

            for (Map.Entry<Long, BucketMetrics> entry : metricsBySecond.entrySet()) {
                if (entry.getKey() < windowStart) {
                    continue;
                }

                BucketMetrics bucket = entry.getValue();
                totalAccepted += bucket.accepted;
                totalRejected += bucket.rejected;

                if (depthBinsOrNull != null) {
                    depthSampleCount += bucket.queueDepthSamples;
                    HistogramMath.accumulateHistogram(depthBinsOrNull, bucket.queueDepthHistogram);
                }

                if (waitBinsOrNull != null) {
                    waitSampleCount += bucket.queueWaitSamples;
                    HistogramMath.accumulateHistogram(waitBinsOrNull, bucket.queueWaitMsHistogram);
                }
            }

            return new WindowAggregate(totalAccepted, totalRejected, depthSampleCount, waitSampleCount);
        }
    }

    private interface HistogramMath {
        static void accumulateHistogram(long[] targetBins, long... sourceHistogram) {
            for (int index = 0; index < targetBins.length; index++) {
                targetBins[index] += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(sourceHistogram, index);
            }
        }

        static double fairnessRatio(long totalAccepted, long totalRejected) {
            long totalDecisions = totalAccepted + totalRejected;
            if (totalDecisions <= NO_SAMPLES) {
                return 1.0d;
            }
            return (double) totalAccepted / (double) totalDecisions;
        }

        static long percentileFromHistogram(
                long sampleCount,
                long percentile,
                long[] histogramBins,
                long[] upperBounds,
                int fallbackBin) {
            if (sampleCount <= NO_SAMPLES) {
                return NO_SAMPLES;
            }

            long targetRank = percentileRank(sampleCount, percentile);
            long running = 0L;
            for (int index = 0; index < histogramBins.length; index++) {
                running += histogramBins[index];
                if (running >= targetRank) {
                    return upperBoundForBin(index, upperBounds);
                }
            }
            return upperBoundForBin(fallbackBin, upperBounds);
        }

        static int binForValue(long value, long... upperBounds) {
            for (int index = 0; index < upperBounds.length; index++) {
                if (value <= upperBounds[index]) {
                    return index;
                }
            }
            return upperBounds.length - 1;
        }

        static long percentileRank(long sampleCount, long percentile) {
            return Math.max(1L, (sampleCount * percentile + 99L) / 100L);
        }

        static long upperBoundForBin(int bin, long... upperBounds) {
            if (bin < FIRST_BIN_INDEX) {
                return upperBounds[FIRST_BIN_INDEX];
            }
            if (bin >= upperBounds.length) {
                return upperBounds[upperBounds.length - 1];
            }
            return upperBounds[bin];
        }
    }

    /**
     * Per-second bucket metrics using VarHandle for lock-free updates.
     */
    /* default */ static final class BucketMetrics {
        private static final VarHandle ACCEPTED_VH;
        private static final VarHandle REJECTED_VH;
        private static final VarHandle QUEUE_DEPTH_SAMPLES_VH;
        private static final VarHandle QUEUE_WAIT_SAMPLES_VH;
        private static final VarHandle ARRAY_LONG_VH;

        static {
            try {
                java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
                ACCEPTED_VH = lookup.findVarHandle(BucketMetrics.class, "accepted", long.class);
                REJECTED_VH = lookup.findVarHandle(BucketMetrics.class, "rejected", long.class);
                QUEUE_DEPTH_SAMPLES_VH = lookup.findVarHandle(BucketMetrics.class, "queueDepthSamples", long.class);
                QUEUE_WAIT_SAMPLES_VH = lookup.findVarHandle(BucketMetrics.class, "queueWaitSamples", long.class);
                ARRAY_LONG_VH = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private volatile long accepted;
        private volatile long rejected;
        private volatile long queueDepthSamples;
        private volatile long queueWaitSamples;
        private final long[] queueDepthHistogram = new long[DEPTH_BIN_COUNT];
        private final long[] queueWaitMsHistogram = new long[WAIT_BIN_COUNT];

        /* default */ void recordAccepted() {
            ACCEPTED_VH.getAndAdd(this, 1L);
        }

        /* default */ void recordRejected() {
            REJECTED_VH.getAndAdd(this, 1L);
        }

        /* default */ void recordQueueDepth(int depth) {
            int bin = HistogramMath.binForValue(depth, DEPTH_BIN_UPPER_BOUNDS);
            ARRAY_LONG_VH.getAndAdd(queueDepthHistogram, bin, 1L);
            QUEUE_DEPTH_SAMPLES_VH.getAndAdd(this, 1L);
        }

        /* default */ void recordQueueWaitNanos(long waitNanos) {
            long waitMs = waitNanos <= NO_WAIT_NANOS ? NO_WAIT_MS : TimeUnit.NANOSECONDS.toMillis(waitNanos);
            int bin = HistogramMath.binForValue(waitMs, WAIT_BIN_UPPER_BOUNDS_MS);
            ARRAY_LONG_VH.getAndAdd(queueWaitMsHistogram, bin, 1L);
            QUEUE_WAIT_SAMPLES_VH.getAndAdd(this, 1L);
        }
    }
}
