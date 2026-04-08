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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
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
                if (FlightRecorder.isInitialized()) {
                    EventLoopFailureEvent evt = new EventLoopFailureEvent();
                    if (evt.isEnabled()) {
                        evt.loopName      = thread.getName();
                        evt.phase         = "UNCAUGHT";
                        evt.exceptionType = throwable.getClass().getSimpleName();
                        evt.affectedCount = 0;
                        evt.commit();
                    }
                }
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

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void dispatchBatch(int ordinal, List<EventDescriptor> descriptors, List<EventPayload> payloads) {
        long started = System.nanoTime();
        List<EventBatchProcessor> processors = processorsByOrdinal.get(ordinal);

        if (processors == null || processors.isEmpty()) {
            closePayloads(payloads);
            processedTotal.addAndGet(payloads.size());
            dispatchNanosTotal.addAndGet(System.nanoTime() - started);
            return;
        }

        try {
            List<EventDescriptor> readonlyDescriptors = List.copyOf(descriptors);
            int processorCount = processors.size();
            if (processorCount > 1) {
                for (EventPayload p : payloads) {
                    for (int r = 1; r < processorCount; r++) {
                        p.retain();
                    }
                }
            }
            List<EventPayload> readonlyPayloads = List.copyOf(payloads);
            for (EventBatchProcessor processor : processors) {
                processor.processBatch(readonlyDescriptors, readonlyPayloads);
            }
            processedTotal.addAndGet(payloads.size());
        } catch (RuntimeException ex) {
            failedTotal.addAndGet(payloads.size());
            if (FlightRecorder.isInitialized()) {
                EventLoopFailureEvent evt = new EventLoopFailureEvent();
                if (evt.isEnabled()) {
                    evt.loopName      = "community-event-loop";
                    evt.phase         = "DISPATCH";
                    evt.exceptionType = ex.getClass().getSimpleName();
                    evt.affectedCount = payloads.size();
                    evt.commit();
                }
            }
            requeueOrClose(descriptors, payloads);
        } finally {
            dispatchNanosTotal.addAndGet(System.nanoTime() - started);
        }
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    private void requeueOrClose(List<EventDescriptor> descriptors, List<EventPayload> payloads) {
        for (int i = 0; i < payloads.size(); i++) {
            EventDescriptor descriptor = descriptors.get(i);
            EventPayload payload = payloads.get(i);
            boolean shouldRequeue = descriptor.isPersistent() && payload.isAlive();

            boolean requeued = false;
            try {
                if (shouldRequeue) {
                    requeued = queue.push(descriptor, payload);
                }
            } catch (RuntimeException ex) {
                failedTotal.incrementAndGet();
            } finally {
                closePayloadQuietly(payload);
            }

            if (shouldRequeue && !requeued) {
                failedTotal.incrementAndGet();
                emitRequeueFailure();
            }
        }
    }

    private static void emitRequeueFailure() {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        EventLoopFailureEvent evt = new EventLoopFailureEvent();
        if (evt.isEnabled()) {
            evt.loopName      = "community-event-loop";
            evt.phase         = "REQUEUE";
            evt.exceptionType = "QueueFull";
            evt.affectedCount = 1;
            evt.commit();
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private static void closePayloads(List<EventPayload> payloads) {
        for (EventPayload payload : payloads) {
            payload.close();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void closePayloadQuietly(EventPayload payload) {
        try {
            payload.close();
        } catch (RuntimeException ex) {
            // Best-effort close to ensure batch processing continues.
        }
    }
}
