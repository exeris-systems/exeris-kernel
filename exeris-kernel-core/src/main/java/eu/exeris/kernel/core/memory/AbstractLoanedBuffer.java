/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Core: Abstract base implementation of {@link LoanedBuffer} with reference-count
 * lifecycle management via {@link VarHandle} (lock-free, atomic CAS).
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>Reference counting uses {@code VarHandle} CAS — no lock objects, no monitors.
 * {@link #slice} and {@link #view} return a {@link SliceLoanedBuffer} sharing the
 * same backing {@link MemorySegment} via {@code MemorySegment.asSlice()} — zero bytes copied.
 *
 * <h2>Close Actions — Flat Storage (Zero-GC)</h2>
 * <p>Close actions are stored as four plain {@code Runnable} fields instead of a
 * {@code List}. This eliminates the heap allocation of {@code CopyOnWriteArrayList}
 * and its backing {@code Object[]} on every buffer creation — which was a confirmed
 * GC leak identified by the Zero-GC JFR Monitor TCK test.
 * Four slots cover all real-world use cases:
 * <ul>
 *   <li>Slot 1: arena/slab release (always present)</li>
 *   <li>Slot 2: telemetry callback (optional, set by allocator)</li>
 *   <li>Slot 3–4: reserved for Enterprise-tier extensions</li>
 * </ul>
 *
 * <h2>Method Count Note</h2>
 * <p>This class intentionally implements the full {@link LoanedBuffer} contract here.
 * Splitting it would force subclasses to re-implement reference-counting boilerplate.
 *
 * @since 0.5.0
 */
public abstract class AbstractLoanedBuffer implements LoanedBuffer { //NOPMD TooManyMethods

    private static final int INITIAL_REF_COUNT = 1;
    private static final VarHandle REF_COUNT;

    static {
        try {
            REF_COUNT = MethodHandles.lookup()
                    .findVarHandle(AbstractLoanedBuffer.class, "refCount", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Reference count — mutated exclusively via {@link #REF_COUNT} VarHandle CAS.
     * Declared package-private so PMD does not flag it as unused private field.
     */
    /* default */ volatile int refCount = INITIAL_REF_COUNT;
    private volatile long size;

    private Runnable closeAction1;
    private Runnable closeAction2;
    private Runnable closeAction3;
    private Runnable closeAction4;

    /**
     * Leak tracker handle — cancelled in {@link #onRelease()} when the buffer is properly
     * closed. Default is the no-op handle ({@link LeakTracker.LeakHandle#NOOP}).
     * Set via {@link #enableLeakTracking(LeakTracker)} by allocators in SAMPLED/PARANOID mode.
     */
    private LeakTracker.LeakHandle leakHandle = LeakTracker.LeakHandle.NOOP;

    // =========================================================================
    // Abstract contract
    // =========================================================================

    protected abstract MemorySegment backingSegment();

    protected abstract void onRelease();

    // =========================================================================
    // LoanedBuffer implementation
    // =========================================================================

    @Override
    public final MemorySegment segment() {
        checkAlive();
        return backingSegment();
    }

    @Override
    public final long size() {
        return size;
    }

    @Override
    public final long capacity() {
        return backingSegment().byteSize();
    }

    @Override
    public final void setSize(long newSize) {
        checkAlive();
        if (newSize < 0 || newSize > capacity()) {
            throw new IllegalArgumentException(
                    "newSize " + newSize + " out of range [0, " + capacity() + "]");
        }
        this.size = newSize;
    }

    @Override
    public final boolean isAlive() {
        return (int) REF_COUNT.getAcquire(this) > 0;
    }

    @Override
    public final int refCount() {
        return (int) REF_COUNT.getAcquire(this);
    }

    @Override
    public final void retain() {
        int current;
        do {
            current = (int) REF_COUNT.getAcquire(this);
            if (current <= 0) {
                throw new IllegalStateException("Cannot retain a released buffer");
            }
        } while (!REF_COUNT.compareAndSet(this, current, current + 1));
    }

    @Override
    public final void close() {
        int prev;
        do {
            prev = (int) REF_COUNT.getAcquire(this);
            if (prev <= 0) {
                return;
            }
        } while (!REF_COUNT.compareAndSet(this, prev, prev - 1));

        if (isInitialCount(prev)) {
            leakHandle.cancel();
            fireCloseActions();
            onRelease();
        }
    }

    /**
     * Registers a close action to execute (LIFO) when refCount reaches zero.
     *
     * <p>Supports at most 4 actions per buffer. Exceeding the limit indicates
     * a design error in the caller — fail fast with {@link IllegalStateException}.
     *
     * @throws IllegalStateException if all 4 slots are already occupied
     */
    @Override
    public final void addCloseAction(Runnable action) {
        checkAlive();
        if (closeAction1 == null) {
            closeAction1 = action;
        } else if (closeAction2 == null) {
            closeAction2 = action;
        } else if (closeAction3 == null) {
            closeAction3 = action;
        } else if (closeAction4 == null) {
            closeAction4 = action;
        } else {
            throw new IllegalStateException(
                    "AbstractLoanedBuffer supports at most 4 close actions. "
                            + "Exceeding this limit indicates a design error.");
        }
    }

    /**
     * Registers a {@link LeakTracker} for this buffer instance.
     *
     * <p>Called by allocators operating in {@link eu.exeris.kernel.spi.memory.LeakDetectionMode#SAMPLED}
     * or {@link eu.exeris.kernel.spi.memory.LeakDetectionMode#PARANOID} mode immediately
     * after buffer creation.
     *
     * <p>The tracker handle is cancelled in {@link #onRelease()} so a properly-closed
     * buffer does not appear in {@link LeakTracker#leakCount()}. In PARANOID mode the
     * allocation stack trace is captured via {@link #captureAllocationStack()}.
     *
     * <p>O(1) — stores a single reference. Safe to call once only.
     *
     * @param tracker the active leak tracker; must not be {@code null}
     */
    public final void enableLeakTracking(LeakTracker tracker) {
        if (tracker == null) {
            throw new IllegalArgumentException("tracker must not be null");
        }
        String stackTrace = tracker.mode() == LeakDetectionMode.PARANOID
                ? captureAllocationStack()
                : "<sampled>";
        String bufferLabel = Integer.toHexString(System.identityHashCode(this));
        leakHandle = tracker.track(this, capacity(), bufferLabel, stackTrace);
    }

    // =========================================================================
    // Zero-copy fragmentation
    // =========================================================================

    @Override
    public final LoanedBuffer slice(long offset, long length) {
        checkAlive();
        MemorySegment slice = backingSegment().asSlice(offset, length);
        retain();
        return new SliceLoanedBuffer(slice, length, this);
    }

    @Override
    public final LoanedBuffer view() {
        checkAlive();
        retain();
        return new SliceLoanedBuffer(
                backingSegment().asSlice(0, size).asReadOnly(), size, this);
    }

    @Override
    public final LoanedBuffer peek(long offset, long length) {
        checkAlive();
        return new PeekLoanedBuffer(backingSegment().asSlice(offset, length), length, this);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    protected final void checkAlive() {
        if (!isAlive()) {
            throw new IllegalStateException("LoanedBuffer is closed (refCount=0)");
        }
    }

    /**
     * Resets this buffer for pool reuse: refCount → 1, size → 0, close actions cleared.
     * Called by Enterprise-tier slab pool implementations after full release.
     */
    @SuppressWarnings("unused")
    protected final void resetForReuse() {
        REF_COUNT.setRelease(this, INITIAL_REF_COUNT);
        this.size = 0;
        this.closeAction1 = null; //NOPMD NullAssignment
        this.closeAction2 = null; //NOPMD NullAssignment
        this.closeAction3 = null; //NOPMD NullAssignment
        this.closeAction4 = null; //NOPMD NullAssignment
        this.leakHandle = LeakTracker.LeakHandle.NOOP;
    }

    private static boolean isInitialCount(int count) {
        return count == INITIAL_REF_COUNT;
    }

    /**
     * Fires close actions in LIFO order: action2 first (telemetry), then action1 (release).
     * Cancels the leak tracker handle first to mark the buffer as properly closed.
     * Neither failure propagates — kernel stability takes priority.
     */
    private void fireCloseActions() {
        if (closeAction4 != null) {
            runQuietly(closeAction4);
        }
        if (closeAction3 != null) {
            runQuietly(closeAction3);
        }
        if (closeAction2 != null) {
            runQuietly(closeAction2);
        }
        if (closeAction1 != null) {
            runQuietly(closeAction1);
        }
    }

    /**
     * Captures the current thread's stack trace as a compact string for PARANOID
     * leak detection. Only called when leak tracking is being registered.
     *
     * <p>This method deliberately allocates (it is on the slow allocation init path,
     * not the hot path). The cost is paid once at buffer creation, not on every read.
     *
     * @return multi-line stack trace string, or empty string if capture fails
     */
    private static String captureAllocationStack() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        // Skip: getStackTrace, captureAllocationStack, enableLeakTracking, track (LeakTracker)
        int skip = 4;
        if (frames.length <= skip) {
            return "";
        }
        StringBuilder stackBuilder = new StringBuilder(frames.length * 64);
        for (int i = skip; i < frames.length; i++) {
            stackBuilder.append('\t').append(frames[i]).append('\n');
        }
        return stackBuilder.toString();
    }

    /**
     * Executes a close action, absorbing any {@link RuntimeException}.
     * Close actions MUST NOT propagate — kernel stability takes priority over
     * individual cleanup failures. Failures are recorded via JFR for post-mortem.
     */
    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) { //NOPMD AvoidCatchingGenericException
            // Intentional: close-action failures must not crash the releasing thread.
            // Record via JFR so post-mortem analysis can detect leaked resources.
            CloseActionFailureEvent.emit(e);
        }
    }

    // =========================================================================
    // Inner: PeekLoanedBuffer — non-owning view, delegates lifecycle to parent
    // =========================================================================

    private static final class PeekLoanedBuffer implements LoanedBuffer {

        private final MemorySegment segment;
        private final long size;
        private final AbstractLoanedBuffer parent;

        /* default */ PeekLoanedBuffer(MemorySegment segment, long size, AbstractLoanedBuffer parent) {
            this.segment = segment;
            this.size = size;
            this.parent = parent;
        }

        @Override
        public MemorySegment segment() {
            parent.checkAlive();
            return segment;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public void setSize(long newSize) {
            throw new UnsupportedOperationException("peek view is immutable");
        }

        @Override
        public boolean isAlive() {
            return parent.isAlive();
        }

        @Override
        public int refCount() {
            return parent.refCount();
        }

        @Override
        public void retain() {
            // non-owning: no-op
        }

        @Override
        public void close() {
            // non-owning: no-op
        }

        @Override
        public void addCloseAction(Runnable action) {
            throw new UnsupportedOperationException("peek view does not own resources");
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            parent.checkAlive();
            MemorySegment slice = segment.asSlice(offset, length);
            parent.retain();
            return new SliceLoanedBuffer(slice, length, parent);
        }

        @Override
        public LoanedBuffer view() {
            parent.checkAlive();
            MemorySegment readOnly = segment.asReadOnly();
            parent.retain();
            return new SliceLoanedBuffer(readOnly, size, parent);
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            parent.checkAlive();
            return new PeekLoanedBuffer(segment.asSlice(offset, length), length, parent);
        }
    }

    // =========================================================================
    // Inner: SliceLoanedBuffer — zero-copy view backed by parent refCount
    // =========================================================================

    private static final class SliceLoanedBuffer extends AbstractLoanedBuffer {

        private final MemorySegment segment;
        private final AbstractLoanedBuffer parent; // null for peek() slices

        SliceLoanedBuffer(MemorySegment segment, long initialSize, //NOPMD CallSuperInConstructor
                          AbstractLoanedBuffer parent) {
            super();
            this.segment = segment;
            this.parent = parent;
            setSize(initialSize);
        }

        @Override
        protected MemorySegment backingSegment() {
            return segment;
        }

        @Override
        protected void onRelease() {
            if (parent != null) {
                parent.close();
            }
        }
    }
}
