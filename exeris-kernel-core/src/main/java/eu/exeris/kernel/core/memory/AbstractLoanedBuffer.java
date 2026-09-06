/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;

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
 * and its backing {@code Object[]} on every buffer creation.
 * <p>These four close-action slots are optional <em>auxiliary</em> callbacks that run
 * when the buffer is closed. The mandatory resource release for the backing storage
 * is implemented by subclasses in {@link #onRelease()}; any of the close-action slots
 * may legitimately be {@code null}. Typical usage:
 * <ul>
 *   <li>Slot&nbsp;1: commonly used for arena/slab release callbacks by allocators (optional)</li>
 *   <li>Slot&nbsp;2: telemetry callback (optional, set by allocator)</li>
 *   <li>Slot&nbsp;3–4: reserved for Enterprise-tier extensions (optional)</li>
 * </ul>
 *
 * <p><b>Allocation:</b> allocates one wrapper object per {@link #slice}, {@link #view}
 * or {@link #peek} call; the backing {@link MemorySegment} itself is never copied,
 * only re-sliced.
 * <p><b>Thread confinement:</b> any thread — the reference count is mutated exclusively
 * through a {@code VarHandle} compare-and-set, so {@link #retain()} and {@link #close()}
 * need no external synchronization; a close action registered on one thread is made
 * visible to whichever thread's {@link #close()} call fires it by the fence pair
 * described on {@link #addCloseAction(Runnable)}.
 * <p><b>Ownership:</b> the thread whose {@link #close()} call drives the reference
 * count to zero triggers {@link #onRelease()} exactly once; a {@link #slice} or
 * {@link #view} holds a reference of its own and must be closed independently of its
 * parent, while a {@link #peek} view holds no reference and must not outlive its parent.
 *
 * @since 0.5
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

    /**
     * Supplies the region of memory that backs this buffer's storage, without
     * copying or adjustment.
     *
     * @return the backing memory segment; never {@code null}
     * @implSpec The returned segment's {@link MemorySegment#byteSize() byteSize} is
     *           this buffer's {@link #capacity()}. Because {@link #capacity()} calls
     *           this method directly, without checking {@link #isAlive()} first,
     *           implementations must keep returning a valid segment after
     *           {@link #close()} — only {@link #segment()}, {@link #slice}, {@link #view}
     *           and {@link #peek} guard the call behind {@link #checkAlive()}.
     */
    protected abstract MemorySegment backingSegment();

    /**
     * Called exactly once when the final reference is released. {@link #close()} dispatches it
     * only on the single thread whose refcount CAS reaches the {@code isInitialCount} branch, so
     * implementations MUST NOT add their own idempotency guard — single dispatch is already
     * guaranteed by the refcount CAS.
     */
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
            Reference.reachabilityFence(this);
            fireCloseActions();
            onRelease();
        }
    }

    /**
     * Registers a close action to run, in LIFO order, when this buffer's reference
     * count reaches zero.
     *
     * <p>At most 4 actions may be registered per buffer instance; a caller that needs
     * more should compose its own aggregate {@link Runnable} instead of registering a
     * fifth. An action registered on one thread is guaranteed to be visible to whichever
     * thread's {@link #close()} call drives the reference count to zero and runs it,
     * even without other synchronization between the two threads.
     *
     * @param action the action to run on final release; must not be {@code null}
     * @throws IllegalArgumentException if {@code action} is {@code null}
     * @throws IllegalStateException if this buffer has already been closed, or if
     *         4 close actions are already registered
     * @implNote Visibility is established with a {@link VarHandle#fullFence()} issued
     *           after the slot write, paired with the matching fence before the read
     *           in {@link #fireCloseActions()} — the {@code VarHandle} CAS on the
     *           reference count only synchronises the count itself, not these plain
     *           {@code Runnable} fields.
     */
    @Override
    public final void addCloseAction(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("Close action must not be null");
        }
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
        VarHandle.fullFence();
    }

    /**
     * Registers a {@link LeakTracker} for this buffer instance so an unclosed buffer
     * is reported after garbage collection.
     *
     * <p>Called by allocators operating in {@link eu.exeris.kernel.spi.memory.LeakDetectionMode#SAMPLED}
     * or {@link eu.exeris.kernel.spi.memory.LeakDetectionMode#PARANOID} mode immediately
     * after buffer creation. The returned tracking is cancelled in {@link #onRelease()},
     * so a buffer that is closed properly never appears in {@link LeakTracker#leakCount()}.
     * In {@code PARANOID} mode the allocation stack trace is captured via
     * {@link #captureAllocationStack()}.
     *
     * @param tracker the active leak tracker; must not be {@code null}
     * @throws IllegalArgumentException if {@code tracker} is {@code null}
     * @apiNote Call at most once per buffer instance; a second call replaces the
     *          stored handle without cancelling the one from the first call.
     */
    public final void enableLeakTracking(LeakTracker tracker) {
        if (tracker == null) {
            throw new IllegalArgumentException("tracker must not be null");
        }
        String stackTrace = tracker.mode() == LeakDetectionMode.PARANOID
                ? captureAllocationStack()
                : "<sampled>";
        int identityHash = System.identityHashCode(this);
        leakHandle = tracker.track(this, capacity(), identityHash, stackTrace);
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

    /**
     * Throws if this buffer has already been released.
     *
     * @throws IllegalStateException if {@link #isAlive()} is {@code false}
     */
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
     * Fires the close actions in LIFO order — action 4 first, down to action 1 —
     * from the single thread whose {@link #close()} call drove the reference count
     * to zero. An action that throws is absorbed and reported via
     * {@link CloseActionFailureEvent} instead of stopping the remaining actions or
     * propagating to the caller of {@link #close()}.
     *
     * @implNote Reads the four action-slot fields behind a {@link VarHandle#fullFence()},
     *           the acquire side of the release fence described on
     *           {@link #addCloseAction(Runnable)} — together they guarantee this thread
     *           observes every slot the registering thread wrote, even though the
     *           fields themselves are plain (non-volatile).
     */
    private void fireCloseActions() {
        VarHandle.fullFence();
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
            PeekMisuseEvent.emit("retain");
        }

        @Override
        public void close() {
            // non-owning: no-op
        }

        @Override
        public void addCloseAction(Runnable action) {
            PeekMisuseEvent.emit("addCloseAction");
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

    /**
     * Zero-copy slice sharing the backing {@link MemorySegment} of a parent buffer.
     *
     * <h2>Independent Ownership Unit</h2>
     * <p>A {@code SliceLoanedBuffer} is a <em>distinct</em> ownership unit with its own
     * reference count (starts at 1) and its own close-action slots. It retains the parent
     * once on construction and releases it exactly once in {@link #onRelease()}.
     *
     * <p><strong>Close-action isolation contract:</strong> Actions registered via
     * {@link #addCloseAction(Runnable)} on a slice are triggered when <em>this slice's</em>
     * reference count reaches zero — <strong>not</strong> when the parent is closed.
     * Callers must never assume that a parent's close transitively fires actions registered
     * on its slices.
     *
     * {@snippet lang="java" :
     * parent.addCloseAction(parentCleanup);   // fires when the parent's refCount -> 0
     * LoanedBuffer slice = parent.slice(0, 64);
     * slice.addCloseAction(sliceCleanup);     // fires when the SLICE's refCount -> 0
     * }
     */
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
