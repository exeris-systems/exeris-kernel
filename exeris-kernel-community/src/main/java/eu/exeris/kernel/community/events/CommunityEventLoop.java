/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.community.events.jfr.EventLoopFailureEvent;
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
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@SuppressWarnings({
    "PMD.CyclomaticComplexity",      // aggregate across loop/dispatch/tracking helpers
    "PMD.TooManyMethods",            // deliberate: loop, dispatch, fork, tracking all belong here
    "PMD.CloseResource",             // TrackingPayload ownership transferred across lambda boundaries
    "PMD.UseTryWithResources"        // wrappers list cannot be expressed as TWR; closed deterministically in finally
})
final class CommunityEventLoop implements EventLoop {

    private static final long IDLE_PARK_NANOS = 1_000_000L;
    private static final long ZERO_PROCESSED = 0L;
    private static final String COMMENT_DEFAULT_ACCESS_MODIFIER = "PMD.CommentDefaultAccessModifier";

    private final EventRegistry registry;
    private final EventQueue queue;
    private final int batchSize;

    private final Map<Integer, List<EventBatchProcessor>> processorsByOrdinal = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicLong processedTotal = new AtomicLong(0L);
    private final AtomicLong failedTotal = new AtomicLong(0L);
    private final AtomicLong dispatchNanosTotal = new AtomicLong(0L);

    private volatile Thread loopThread;

    private record QueuedEvent(EventDescriptor descriptor, EventPayload payload) {
    }

    @SuppressWarnings(COMMENT_DEFAULT_ACCESS_MODIFIER)
    CommunityEventLoop(EventRegistry registry, EventQueue queue, int batchSize) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.queue = Objects.requireNonNull(queue, "queue");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        this.batchSize = batchSize;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        loopThread = Thread.ofVirtual()
                .name("community-event-loop")
            .uncaughtExceptionHandler((thread, throwable) -> {
                running.set(false);
                EventLoopFailureEvent.emit(thread.getName(), "UNCAUGHT", throwable, 0);
            })
                .start(this::runLoop);
    }

    @Override
    public void stop() {
        running.set(false);

        Thread thread = loopThread;
        if (thread == null) {
            return;
        }

        try {
            thread.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

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

    @SuppressWarnings(COMMENT_DEFAULT_ACCESS_MODIFIER)
    long processedTotal() {
        return processedTotal.get();
    }

    @SuppressWarnings(COMMENT_DEFAULT_ACCESS_MODIFIER)
    long failedTotal() {
        return failedTotal.get();
    }

    @SuppressWarnings(COMMENT_DEFAULT_ACCESS_MODIFIER)
    long averageDispatchNanos() {
        long processed = processedTotal.get();
        if (processed == ZERO_PROCESSED) {
            return 0L;
        }
        return dispatchNanosTotal.get() / processed;
    }

    private void runLoop() {
        List<QueuedEvent> drained = new ArrayList<>(batchSize);
        while (running.get() || !queue.isEmpty()) {
            drained.clear();
            queue.drain((descriptor, payload) -> drained.add(new QueuedEvent(descriptor, payload)), batchSize);
            if (drained.isEmpty()) {
                LockSupport.parkNanos(IDLE_PARK_NANOS);
                continue;
            }
            dispatchByOrdinal(drained);
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

        try (StructuredTaskScope<Void, Void> scope =
                     StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll())) {
            for (EventBatchProcessor processor : processors) {
                forkProcessor(scope, processor, readonlyDescriptors, payloads, failures);
            }
            try {
                scope.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        closePayloads(payloads);
        recordOutcome(payloads, failures);
        dispatchNanosTotal.addAndGet(System.nanoTime() - started);
    }

    private static void forkProcessor(StructuredTaskScope<Void, Void> scope,
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
