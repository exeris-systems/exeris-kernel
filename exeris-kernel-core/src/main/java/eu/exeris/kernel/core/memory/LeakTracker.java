/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.LeakDetectionMode;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core: {@link Cleaner}-based forensic leak tracker for {@link AbstractLoanedBuffer}.
 *
 * <h2>How It Works</h2>
 * <p>When a buffer is created with {@link LeakDetectionMode#PARANOID} or
 * {@link LeakDetectionMode#SAMPLED}, the allocator calls
 * {@link #track(Object, long, int, String)} to register a phantom cleanup action.
 * If the JVM garbage-collects the buffer object <em>before</em> {@link ActiveLeakHandle#cancel()} is called,
 * the Cleaner thread fires {@link LeakDetectedEvent} and increments the {@link #leakCount()}.
 *
 * <h2>Cancellation Protocol</h2>
 * <p>The allocator (via {@link AbstractLoanedBuffer#onRelease()}) MUST call
 * {@link LeakHandle#cancel()} when the buffer is properly closed. If cancel is not called,
 * the Cleaner fires when the JVM finalizes the tracking phantom reference.
 *
 * <h2>Thread Safety</h2>
 * <p>{@link #leakCount} is updated via {@link AtomicLong#incrementAndGet()} — lock-free,
 * safe for concurrent Cleaner callbacks from multiple threads.
 *
 * <h2>Zero-Allocation on Disabled Path</h2>
 * <p>When {@link LeakDetectionMode#DISABLED}, {@link #track} returns a no-op singleton
 * {@link LeakHandle#NOOP} — zero allocations.
 *
 * <h2>SAMPLED Mode (1-in-128 Sampling)</h2>
 * <p>In SAMPLED mode, approximately 1/128 of all allocations are tracked. The sampling
 * is based on a simple counter modulo — no {@code Random} allocation, no {@code ThreadLocal}.
 *
 * <h2>Memory.md EX-MEM-1002 Compliance</h2>
 * <p>Detected leaks are emitted as {@link LeakDetectedEvent} JFR events bearing
 * error code {@code EX-MEM-1002}. No {@code System.err} or logging framework is used.
 *
 * @see AbstractLoanedBuffer
 * @see LeakDetectedEvent
 * @since 0.5.0
 */
public final class LeakTracker {

    /**
     * Sampling period for {@link LeakDetectionMode#SAMPLED}: track every Nth allocation.
     * 128 → ~0.78% overhead. Power-of-two for cheap modulo via bitwise AND.
     */
    private static final int SAMPLE_MASK = 127; // 128 - 1

    /**
     * Kernel-wide JVM Cleaner — one daemon thread services all phantom references.
     * Declared as a single static instance to avoid creating multiple Cleaner threads
     * (each would be an OS thread — violating the "No Waste Compute" principle).
     */
    private static final Cleaner CLEANER = Cleaner.create();

    private final LeakDetectionMode mode;
    private final AtomicLong leakCount = new AtomicLong(0);
    private final AtomicLong sampleIndex = new AtomicLong(0);

    /**
     * Creates a new {@link LeakTracker} with the given detection mode.
     *
     * @param mode detection strictness; must not be {@code null}
     */
    public LeakTracker(LeakDetectionMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("LeakDetectionMode must not be null");
        }
        this.mode = mode;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Registers a leak-tracking phantom reference for the given buffer object.
     *
     * <p>Returns a {@link LeakHandle} which MUST be cancelled when the buffer is properly
     * closed via {@link AbstractLoanedBuffer#close()}. Failing to cancel will cause the
     * Cleaner to fire {@link LeakDetectedEvent} after GC.
     *
     * <p>In {@link LeakDetectionMode#DISABLED} mode, returns {@link LeakHandle#NOOP}
     * immediately — no allocation, no Cleaner registration.
     *
     * @param referent        the buffer object to track (strongly typed to avoid accidental
     *                        inner-class captures that would prevent GC)
     * @param capacityBytes   byte capacity of the underlying segment (for the JFR event)
     * @param identityHash    raw identity hash code of the buffer; formatted to hex lazily only on leak
     * @param allocationStack stack trace string captured at allocation time; pass {@code "<sampled>"}
     *                        for SAMPLED mode (no stack capture for performance)
     * @return a {@link LeakHandle} to cancel when the buffer is properly closed
     */
    public LeakHandle track(Object referent,
                            long capacityBytes,
                            int identityHash,
                            String allocationStack) {
        if (referent == null) {
            throw new IllegalArgumentException("referent must not be null");
        }
        if (allocationStack == null) {
            throw new IllegalArgumentException("allocationStack must not be null");
        }
        if (mode == LeakDetectionMode.DISABLED) {
            return LeakHandle.NOOP;
        }
        if (mode == LeakDetectionMode.SAMPLED) {
            long idx = sampleIndex.getAndIncrement();
            if ((idx & SAMPLE_MASK) != 0) {
                return LeakHandle.NOOP;
            }
        }
        LeakAction action = new LeakAction(leakCount, identityHash, allocationStack, capacityBytes);
        Cleaner.Cleanable cleanable = CLEANER.register(referent, action);
        return new ActiveLeakHandle(cleanable, action);
    }

    /**
     * Returns the cumulative count of leaked buffers detected since this tracker was created.
     *
     * @return non-negative leak count
     */
    public long leakCount() {
        return leakCount.get();
    }

    /**
     * Returns the active detection mode.
     *
     * @return detection mode; never {@code null}
     */
    public LeakDetectionMode mode() {
        return mode;
    }

    // =========================================================================
    // LeakHandle — cancellable token
    // =========================================================================

    /**
     * Cancellable token returned by {@link #track}.
     *
     * <p>The handle MUST be cancelled in {@link AbstractLoanedBuffer#onRelease()}.
     * If the handle is not cancelled and the buffer is GC'd, the Cleaner fires the
     * {@link LeakDetectedEvent}.
     */
    public sealed interface LeakHandle permits LeakHandle.NoopHandle, ActiveLeakHandle {

        /**
         * Singleton no-op handle for DISABLED mode and unsampled SAMPLED allocations.
         */
        NoopHandle NOOP = new NoopHandle();

        /**
         * Cancels the leak tracking for a properly-closed buffer.
         *
         * <p>Safe to call multiple times — idempotent.
         */
        void cancel();

        /**
         * No-op implementation for the DISABLED path — zero allocation.
         */
        final class NoopHandle implements LeakHandle {
            private NoopHandle() {
            }

            @Override
            public void cancel() {
                // intentional no-op
            }
        }
    }

    // =========================================================================
    // Internal: active handle backed by a Cleaner.Cleanable
    // =========================================================================

    /**
     * Active leak handle backed by a {@link Cleaner.Cleanable}.
     * Cancelling calls {@link Cleaner.Cleanable#clean()} which de-registers the
     * phantom reference without firing the leak action.
     */
    /* default */ static final class ActiveLeakHandle implements LeakHandle {

        private final Cleaner.Cleanable cleanable;
        private final LeakAction action;

        /* default */ ActiveLeakHandle(Cleaner.Cleanable cleanable, LeakAction action) {
            this.cleanable = cleanable;
            this.action = action;
        }

        @Override
        public void cancel() {
            action.markCancelled();
            cleanable.clean();
        }
    }

    // =========================================================================
    // Internal: Cleanable action — must not hold a reference to the referent!
    // =========================================================================

    /**
     * The cleanup action registered with the JVM Cleaner.
     *
     * <p><b>Critical:</b> This class MUST NOT hold a strong reference to the
     * {@code referent} (the buffer). If it did, the buffer would never become
     * phantom-reachable and the Cleaner would never fire. All fields are
     * independent of the referent object graph.
     */
    /* default */ static final class LeakAction implements Runnable {

        private final AtomicLong leakCount;
        private final int identityHash;
        private final String allocationStack;
        private final long capacityBytes;
        private final AtomicBoolean fired = new AtomicBoolean(false);

        /* default */ LeakAction(AtomicLong leakCount,
                                 int identityHash,
                                 String allocationStack,
                                 long capacityBytes) {
            this.leakCount = leakCount;
            this.identityHash = identityHash;
            this.allocationStack = allocationStack;
            this.capacityBytes = capacityBytes;
        }

        /**
         * Marks this action as cancelled — called by {@link ActiveLeakHandle#cancel()}.
         */
        /* default */ void markCancelled() {
            fired.set(true);
        }

        @Override
        public void run() {
            if (!fired.compareAndSet(false, true)) {
                return;
            }
            leakCount.incrementAndGet();
            String bufferLabel = Integer.toHexString(identityHash);
            LeakDetectedEvent.emit(bufferLabel, allocationStack, capacityBytes);
        }
    }
}
