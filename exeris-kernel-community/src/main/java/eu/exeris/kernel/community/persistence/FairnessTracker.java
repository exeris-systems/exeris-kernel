/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 * @since 0.6.0
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.GodClass", "PMD.TooManyMethods"})
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

    private final ConcurrentMap<Long, BucketMetrics> metricsBySecond;
    private final LongSupplier nanoTimeSource;
    private final AtomicLong lastRecordNanos;
    private final AtomicLong decisionSequence;
    private volatile long lastCleanupBucketKey;
    private volatile CachedSnapshot cachedSnapshot;
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
        this.lastCleanupBucketKey = Long.MIN_VALUE;
        this.cachedSnapshot = CachedSnapshot.initial();
    }

    /**
     * Record an admission decision (accepted or rejected).
     */
    /* default */ void recordDecision(boolean accepted, int queueDepth) {
        long now = nanoTimeSource.getAsLong();
        long bucketKey = bucketKeyAt(now);
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
        maybeCleanOldBuckets(now, bucketKey);
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
        long totalAccepted = 0L;
        long totalRejected = 0L;
        for (Map.Entry<Long, BucketMetrics> entry : metricsBySecond.entrySet()) {
            if (entry.getKey() < windowStart) {
                continue;
            }
            BucketMetrics bucket = entry.getValue();
            totalAccepted += bucket.accepted;
            totalRejected += bucket.rejected;
        }

        long totalDecisions = totalAccepted + totalRejected;
        if (totalDecisions <= NO_SAMPLES) {
            return 1.0; // No data: assume perfect fairness
        }

        // Fairness = accepted / (total decisions)
        return (double) totalAccepted / (double) totalDecisions;
    }

    /**
     * Compute P95 queue depth over the observation window.
     */
    /* default */ long computeQueueDepthP95() {
        long windowStart = nanoTimeSource.getAsLong() - RECENT_WINDOW_NANOS;
        long sampleCount = 0L;
        long bin0 = 0L;
        long bin1 = 0L;
        long bin2 = 0L;
        long bin3 = 0L;
        long bin4 = 0L;
        long bin5 = 0L;
        long bin6 = 0L;
        long bin7 = 0L;
        for (Map.Entry<Long, BucketMetrics> entry : metricsBySecond.entrySet()) {
            if (entry.getKey() < windowStart) {
                continue;
            }
            BucketMetrics bucket = entry.getValue();
            sampleCount += bucket.queueDepthSamples;
            bin0 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 0);
            bin1 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 1);
            bin2 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 2);
            bin3 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 3);
            bin4 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 4);
            bin5 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 5);
            bin6 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 6);
            bin7 += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(bucket.queueDepthHistogram, 7);
        }

        if (sampleCount <= NO_SAMPLES) {
            return NO_SAMPLES;
        }

        long targetRank = percentileRank(sampleCount, QUEUE_DEPTH_P95_PERCENTILE);
        long running = 0L;
        running += bin0;
        if (running >= targetRank) {
            return upperBoundForBin(0, DEPTH_BIN_UPPER_BOUNDS);
        }
        running += bin1;
        if (running >= targetRank) {
            return upperBoundForBin(1, DEPTH_BIN_UPPER_BOUNDS);
        }
        running += bin2;
        if (running >= targetRank) {
            return upperBoundForBin(2, DEPTH_BIN_UPPER_BOUNDS);
        }
        running += bin3;
        if (running >= targetRank) {
            return upperBoundForBin(3, DEPTH_BIN_UPPER_BOUNDS);
        }
        running += bin4;
        if (running >= targetRank) {
            return upperBoundForBin(4, DEPTH_BIN_UPPER_BOUNDS);
        }
        running += bin5;
        if (running >= targetRank) {
            return upperBoundForBin(5, DEPTH_BIN_UPPER_BOUNDS);
        }
        running += bin6;
        if (running >= targetRank) {
            return upperBoundForBin(6, DEPTH_BIN_UPPER_BOUNDS);
        }
        running += bin7;
        if (running >= targetRank) {
            return upperBoundForBin(7, DEPTH_BIN_UPPER_BOUNDS);
        }
        return upperBoundForBin(LAST_DEPTH_BIN, DEPTH_BIN_UPPER_BOUNDS);
    }

    /* default */ long computeQueueWaitP95Ms() {
        long windowStart = nanoTimeSource.getAsLong() - RECENT_WINDOW_NANOS;
        long[] waitBins = new long[WAIT_BIN_COUNT];
        WindowAggregate aggregate = aggregateWindowMetrics(windowStart, null, waitBins);
        return percentileFromHistogram(
                aggregate.waitSampleCount(),
                QUEUE_WAIT_P95_PERCENTILE,
                waitBins,
                WAIT_BIN_UPPER_BOUNDS_MS,
                LAST_WAIT_BIN);
    }

    /* default */ boolean indicatesAdmissionStress(double fairnessThreshold, long queueDepthThreshold) {
        FairnessSnapshot snapshot = readSnapshot();
        double fairnessRatio = snapshot.fairnessRatio();
        long queueDepthP95 = snapshot.queueDepthP95();

        return fairnessRatio < fairnessThreshold && queueDepthP95 >= queueDepthThreshold;
    }

    /* default */ FairnessSnapshot computeSnapshot() {
        return readSnapshot();
    }

    private FairnessSnapshot readSnapshot() {
        long now = nanoTimeSource.getAsLong();
        long currentDecisionSequence = decisionSequence.get();
        CachedSnapshot local = cachedSnapshot;

        if (!local.shouldRefresh(now, currentDecisionSequence)) {
            return local.snapshot();
        }

        synchronized (snapshotLock) {
            // Recheck under lock — another thread may have refreshed while we waited.
            local = cachedSnapshot;
            if (!local.shouldRefresh(now, currentDecisionSequence)) {
                return local.snapshot();
            }
            FairnessSnapshot recomputed = computeSnapshotAt(now);
            cachedSnapshot = new CachedSnapshot(now, currentDecisionSequence, recomputed);
            return recomputed;
        }
    }

    private FairnessSnapshot computeSnapshotAt(long now) {
        long windowStart = now - RECENT_WINDOW_NANOS;
        java.util.Arrays.fill(depthBinsReuse, 0L);
        java.util.Arrays.fill(waitBinsReuse, 0L);
        WindowAggregate aggregate = aggregateWindowMetrics(windowStart, depthBinsReuse, waitBinsReuse);

        double fairnessRatio = fairnessRatio(aggregate.totalAccepted(), aggregate.totalRejected());
        long queueDepthP95 = percentileFromHistogram(
                aggregate.depthSampleCount(),
                QUEUE_DEPTH_P95_PERCENTILE,
                depthBinsReuse,
                DEPTH_BIN_UPPER_BOUNDS,
                LAST_DEPTH_BIN);
        long queueWaitP95Ms = percentileFromHistogram(
                aggregate.waitSampleCount(),
                QUEUE_WAIT_P95_PERCENTILE,
                waitBinsReuse,
                WAIT_BIN_UPPER_BOUNDS_MS,
                LAST_WAIT_BIN);

        return new FairnessSnapshot(fairnessRatio, queueDepthP95, queueWaitP95Ms);
    }

    private WindowAggregate aggregateWindowMetrics(long windowStart, long[] depthBinsOrNull, long... waitBinsOrNull) {
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
                accumulateHistogram(depthBinsOrNull, bucket.queueDepthHistogram);
            }

            if (waitBinsOrNull != null) {
                waitSampleCount += bucket.queueWaitSamples;
                accumulateHistogram(waitBinsOrNull, bucket.queueWaitMsHistogram);
            }
        }

        return new WindowAggregate(totalAccepted, totalRejected, depthSampleCount, waitSampleCount);
    }

    private static void accumulateHistogram(long[] targetBins, long... sourceHistogram) {
        for (int index = 0; index < targetBins.length; index++) {
            targetBins[index] += (long) BucketMetrics.ARRAY_LONG_VH.getVolatile(sourceHistogram, index);
        }
    }

    private static double fairnessRatio(long totalAccepted, long totalRejected) {
        long totalDecisions = totalAccepted + totalRejected;
        if (totalDecisions <= NO_SAMPLES) {
            return 1.0;
        }
        return (double) totalAccepted / (double) totalDecisions;
    }

    private static long percentileFromHistogram(
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

    private void maybeCleanOldBuckets(long now, long currentBucketKey) {
        if (currentBucketKey == lastCleanupBucketKey && metricsBySecond.size() <= MAX_BUCKET_SIZE) {
            return;
        }
        lastCleanupBucketKey = currentBucketKey;
        long cutoff = now - RECENT_WINDOW_NANOS;
        metricsBySecond.entrySet().removeIf(e -> e.getKey() < cutoff);
    }

    private static long bucketKeyAt(long nanos) {
        return nanos / BUCKET_DURATION_NANOS * BUCKET_DURATION_NANOS;
    }

    private long observeQueueWaitNanos(long now, int queueDepth) {
        long previous = lastRecordNanos.getAndSet(now);
        if (queueDepth <= NO_QUEUE_DEPTH || previous == Long.MIN_VALUE || now <= previous) {
            return NO_WAIT_NANOS;
        }
        return now - previous;
    }

    private static long percentileRank(long sampleCount, long percentile) {
        return Math.max(1L, (sampleCount * percentile + 99L) / 100L);
    }

    private static long upperBoundForBin(int bin, long... upperBounds) {
        if (bin < FIRST_BIN_INDEX) {
            return upperBounds[FIRST_BIN_INDEX];
        }
        if (bin >= upperBounds.length) {
            return upperBounds[upperBounds.length - 1];
        }
        return upperBounds[bin];
    }

    private record CachedSnapshot(long refreshedAtNanos, long decisionSequence, FairnessSnapshot snapshot) {
        private static CachedSnapshot initial() {
            return new CachedSnapshot(
                    Long.MIN_VALUE,
                    Long.MIN_VALUE,
                    new FairnessSnapshot(1.0d, NO_SAMPLES, NO_WAIT_MS));
        }

        private boolean shouldRefresh(long now, long currentDecisionSequence) {
            if (refreshedAtNanos == Long.MIN_VALUE) {
                return true;
            }
            long elapsedNanos = now - refreshedAtNanos;
            long decisionsSinceRefresh = currentDecisionSequence - decisionSequence;
            return elapsedNanos >= SNAPSHOT_REFRESH_INTERVAL_NANOS
                    || decisionsSinceRefresh >= SNAPSHOT_REFRESH_DECISION_STRIDE;
        }
    }

    private record WindowAggregate(
            long totalAccepted,
            long totalRejected,
            long depthSampleCount,
            long waitSampleCount) {
    }

    /* default */ record FairnessSnapshot(double fairnessRatio, long queueDepthP95, long queueWaitP95Ms) {
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

        private static int binForValue(long value, long... upperBounds) {
            for (int index = 0; index < upperBounds.length; index++) {
                if (value <= upperBounds[index]) {
                    return index;
                }
            }
            return upperBounds.length - 1;
        }

        /* default */ void recordQueueDepth(int depth) {
            int bin = binForValue(depth, DEPTH_BIN_UPPER_BOUNDS);
            ARRAY_LONG_VH.getAndAdd(queueDepthHistogram, bin, 1L);
            QUEUE_DEPTH_SAMPLES_VH.getAndAdd(this, 1L);
        }

        /* default */ void recordQueueWaitNanos(long waitNanos) {
            long waitMs = waitNanos <= NO_WAIT_NANOS ? NO_WAIT_MS : TimeUnit.NANOSECONDS.toMillis(waitNanos);
            int bin = binForValue(waitMs, WAIT_BIN_UPPER_BOUNDS_MS);
            ARRAY_LONG_VH.getAndAdd(queueWaitMsHistogram, bin, 1L);
            QUEUE_WAIT_SAMPLES_VH.getAndAdd(this, 1L);
        }
    }
}
