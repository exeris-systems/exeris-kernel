/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.community.events.jfr.EventLoopFailureEvent;
import eu.exeris.kernel.core.concurrent.StructuredScope;
import eu.exeris.kernel.spi.events.EventBatchProcessor;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventLoop;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventQueue;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import jdk.jfr.FlightRecorder;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Community binding of {@link EventLoop}: a single {@linkplain Thread#ofVirtual() virtual
 * thread} that drains the given {@link EventQueue} in batches of up to {@code batchSize} and
 * dispatches each ordinal's batch to its registered {@link EventBatchProcessor}s through a
 * per-batch {@link StructuredScope}.
 *
 * <p><b>JFR commit protocol.</b> Every emit in this class ({@link EventLoopFailureEvent}) is a
 * single-phase commit — the event is populated and {@code commit()}ted directly, with no
 * {@code begin()} call and therefore no measured duration. It carries a point-in-time
 * occurrence, not an interval, so it cannot straddle a blocking operation on the virtual
 * thread that emits it.
 *
 * <p><b>Allocation:</b> allocates a {@code QueuedEvent} record per drained event and a
 * {@code TrackingPayload} wrapper per payload per dispatch, plus the per-ordinal grouping maps
 * in {@code dispatchByOrdinal} — not zero-alloc on the dispatch path.
 * <p><b>Thread confinement:</b> {@link #runLoop()} runs on exactly one virtual thread, created
 * and tracked by {@link #start()}/{@link #stop()}; {@link #registerProcessor} and the public
 * accessors may be called from any thread.
 * <p><b>Ownership:</b> every drained {@link EventPayload}'s original reference is closed
 * exactly once via {@link #closePayloads}, whether or not a processor is registered for its
 * ordinal. When processors are registered, each additionally receives its own
 * {@link EventPayload#retain() retained} {@code TrackingPayload} wrapper, closed either by the
 * processor itself or, as a safety net, by {@code forkProcessor}'s {@code finally} block for
 * any wrapper the processor left open.
 */
@SuppressWarnings({
    "PMD.CyclomaticComplexity",      // aggregate across loop/dispatch/tracking helpers
    "PMD.TooManyMethods",            // deliberate: loop, dispatch, fork, tracking all belong here
    "PMD.CouplingBetweenObjects",    // loop owns dispatch, JFR, queue, and structured-scope coordination by design
    "PMD.CloseResource",             // TrackingPayload ownership transferred across lambda boundaries
    "PMD.UseTryWithResources"        // wrappers list cannot be expressed as TWR; closed deterministically in finally
})
final class CommunityEventLoop implements EventLoop {

    private static final long IDLE_PARK_NANOS = 1_000_000L;
    private static final long ZERO_PROCESSED = 0L;

    private final EventRegistry registry;
    private final EventQueue queue;
    private final int batchSize;

    private final Map<Integer, List<EventBatchProcessor>> processorsByOrdinal = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicLong processedTotal = new AtomicLong(0L);
    private final AtomicLong failedTotal = new AtomicLong(0L);
    private final AtomicLong dispatchNanosTotal = new AtomicLong(0L);
    private final AtomicReference<Thread> loopThread = new AtomicReference<>();

    private record QueuedEvent(EventDescriptor descriptor, EventPayload payload) {
    }

    /* default */ CommunityEventLoop(EventRegistry registry, EventQueue queue, int batchSize) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.queue = Objects.requireNonNull(queue, "queue");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        this.batchSize = batchSize;
    }

    /**
     * Starts the drain loop on a newly created, named virtual thread named
     * {@code "community-event-loop"}.
     *
     * <p>Idempotent: a second call while already running is a no-op (compare-and-set guard).
     * The thread's uncaught-exception handler marks the loop stopped and emits
     * {@link EventLoopFailureEvent} with phase {@code "UNCAUGHT"} — an escape hatch for a
     * failure {@link #runLoop} itself does not catch, not the normal per-batch failure path
     * (that one goes through {@link #recordOutcome}).
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        Thread thread = Thread.ofVirtual()
                .name("community-event-loop")
                .uncaughtExceptionHandler((failedThread, throwable) -> {
                    running.set(false);
                    EventLoopFailureEvent.emit(failedThread.getName(), "UNCAUGHT", throwable, 0);
                })
                .unstarted(this::runLoop);
        loopThread.set(thread);
        boolean started = false;
        try {
            thread.start();
            started = true;
        } finally {
            if (!started) {
                loopThread.compareAndSet(thread, null);
                running.set(false);
            }
        }
    }

    /**
     * Clears the running flag and joins the loop's virtual thread, blocking until it returns.
     *
     * <p>Because {@link #runLoop} keeps draining while {@code !queue.isEmpty()} even after
     * {@code running} goes false, this call does not return until every event that was already
     * queued has been dispatched — a graceful drain, not an abrupt halt. Idempotent: a call
     * with no thread on record (never started, or already joined) is a no-op. If the calling
     * thread is interrupted while joining, this method restores the interrupt status on itself
     * and returns rather than propagating.
     */
    @Override
    public void stop() {
        running.set(false);

        Thread thread = loopThread.get();
        if (thread == null) {
            return;
        }

        try {
            thread.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns whether the loop's virtual thread is expected to be draining the queue.
     *
     * <p>Backed by a plain flag set by {@link #start()}/{@link #stop()} and cleared early by
     * the uncaught-exception handler on an unrecovered failure — it does not itself confirm the
     * thread is alive.
     *
     * @return {@code true} if the loop is running
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Registers {@code processor} to receive future batches of {@code eventType}, resolving
     * the type name to its ordinal through the registry supplied at construction.
     *
     * <p>May be called before or after {@link #start()} — {@code processorsByOrdinal} is a
     * {@link java.util.concurrent.ConcurrentHashMap} of {@link CopyOnWriteArrayList}s, so a
     * registration is visible to the next drain regardless of when it happens. Multiple
     * processors for the same ordinal are invoked in registration order.
     *
     * @param eventType the event type name to process (non-null)
     * @param processor the batch processor (non-null)
     * @throws EventEngineException EX-EVENT-6001 if {@code eventType} was never registered in
     *         the {@link EventRegistry} this loop was constructed with
     */
    @Override
    public void registerProcessor(String eventType, EventBatchProcessor processor) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(processor, "processor");

        int ordinal = registry.ordinalOf(eventType);
        if (ordinal < 0) {
            throw new EventEngineException(
                    "Cannot register processor for unregistered event type: '" + eventType + "'");
        }

        processorsByOrdinal
                .computeIfAbsent(ordinal, _ -> new CopyOnWriteArrayList<>())
                .add(processor);
    }

    /* default */ long processedTotal() {
        return processedTotal.get();
    }

    /* default */ long failedTotal() {
        return failedTotal.get();
    }

    /* default */ long averageDispatchNanos() {
        long processed = processedTotal.get();
        if (processed == ZERO_PROCESSED) {
            return 0L;
        }
        return dispatchNanosTotal.get() / processed;
    }

    private void runLoop() {
        List<QueuedEvent> drained = new ArrayList<>(batchSize);
        try {
            while (running.get() || !queue.isEmpty()) {
                drained.clear();
                queue.drain((descriptor, payload) -> drained.add(new QueuedEvent(descriptor, payload)), batchSize);
                if (drained.isEmpty()) {
                    LockSupport.parkNanos(IDLE_PARK_NANOS);
                    continue;
                }
                dispatchByOrdinal(drained);
            }
        } finally {
            loopThread.compareAndSet(Thread.currentThread(), null);
        }
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private void dispatchByOrdinal(List<QueuedEvent> drained) {
        Map<Integer, List<EventDescriptor>> descriptorsByOrdinal = new HashMap<>();
        Map<Integer, List<EventPayload>> payloadsByOrdinal = new HashMap<>();

        for (QueuedEvent event : drained) {
            int ordinal = event.descriptor().eventTypeOrdinal();
            descriptorsByOrdinal.computeIfAbsent(ordinal, _ -> new ArrayList<>()).add(event.descriptor());
            payloadsByOrdinal.computeIfAbsent(ordinal, _ -> new ArrayList<>()).add(event.payload());
        }

        for (Map.Entry<Integer, List<EventDescriptor>> entry : descriptorsByOrdinal.entrySet()) {
            int ordinal = entry.getKey();
            List<EventDescriptor> descriptors = entry.getValue();
            List<EventPayload> payloads = payloadsByOrdinal.getOrDefault(ordinal, List.of());
            dispatchBatch(ordinal, descriptors, payloads);
        }
    }

    private void dispatchBatch(int ordinal, List<EventDescriptor> descriptors, List<EventPayload> payloads) {
        long started = System.nanoTime();
        List<EventBatchProcessor> processors = processorsByOrdinal.get(ordinal);

        if (processors == null || processors.isEmpty()) {
            closePayloads(payloads);
            processedTotal.addAndGet(payloads.size());
            dispatchNanosTotal.addAndGet(System.nanoTime() - started);
            return;
        }

        List<EventDescriptor> readonlyDescriptors = Collections.unmodifiableList(descriptors);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // openWithoutBindings states the status quo rather than narrowing it: the loop thread is
        // started by a plain Thread.ofVirtual(), so it holds no ScopedValue bindings, and the
        // StructuredTaskScope this replaces therefore inherited an empty set into every processor.
        try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
            for (EventBatchProcessor processor : processors) {
                forkProcessor(scope, processor, readonlyDescriptors, payloads, failures);
            }
            try {
                scope.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        closePayloads(payloads);
        recordOutcome(payloads, failures);
        dispatchNanosTotal.addAndGet(System.nanoTime() - started);
    }

    private static void forkProcessor(StructuredScope scope,
                                      EventBatchProcessor processor,
                                      List<EventDescriptor> readonlyDescriptors,
                                      List<EventPayload> payloads,
                                      Queue<Throwable> failures) {
        List<TrackingPayload> wrappers = new ArrayList<>(payloads.size());
        for (EventPayload p : payloads) {
            p.retain();
            wrappers.add(new TrackingPayload(p));
        }
        List<EventPayload> wrappedView = Collections.unmodifiableList(wrappers);
        scope.fork(() -> {
            try {
                processor.processBatch(readonlyDescriptors, wrappedView);
            } catch (RuntimeException ex) { //NOPMD AvoidCatchingGenericException — untrusted processor SPI boundary
                failures.add(ex);
            } finally {
                for (TrackingPayload w : wrappers) {
                    if (!w.isClosed()) {
                        w.close();
                    }
                }
            }
            return null;
        });
    }

    private void recordOutcome(List<EventPayload> payloads, Queue<Throwable> failures) {
        if (failures.isEmpty()) {
            processedTotal.addAndGet(payloads.size());
            return;
        }
        failedTotal.addAndGet(payloads.size());
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        Throwable first = failures.peek();
        EventLoopFailureEvent.emit("community-event-loop", "DISPATCH", first, payloads.size());
    }

    private static void closePayloads(List<EventPayload> payloads) {
        for (EventPayload payload : payloads) {
            payload.close();
        }
    }

    /**
     * Delegates all {@link EventPayload} operations to the underlying ref.
     * Tracks whether {@link #close()} has been called so the loop can deterministically
     * close any refs the processor failed to close — preventing refCount leaks without
     * risking over-release.
     */
    private static final class TrackingPayload implements EventPayload {
        private final EventPayload delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        /* default */ TrackingPayload(EventPayload delegate) {
            this.delegate = delegate;
        }

        /* default */ boolean isClosed() {
            return closed.get();
        }

        @Override
        public MemorySegment segment() {
            return delegate.segment();
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public void retain() {
            delegate.retain();
        }

        /**
         * Closes the underlying delegate exactly once: the first caller (processor or the
         * loop's own safety net) wins the compare-and-set and forwards the close; every
         * subsequent call is a no-op.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }

        @Override
        public int refCount() {
            return delegate.refCount();
        }

        @Override
        public boolean isAlive() {
            return !closed.get() && delegate.isAlive();
        }
    }
}
