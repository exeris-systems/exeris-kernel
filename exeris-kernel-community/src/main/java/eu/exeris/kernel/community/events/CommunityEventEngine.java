/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.community.events.jfr.CommunityEventQueueOverflowEvent;
import eu.exeris.kernel.core.events.InMemoryEventBus;
import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.core.events.outbox.OutboxEventStore;
import eu.exeris.kernel.core.events.outbox.OutboxOrchestrator;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventEngineStats;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventLoop;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventQueue;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Community binding of {@link EventEngine}: composes a {@link CommunityEventRegistry}, a
 * {@link CommunityEventQueue}, a {@link CommunityEventLoop}, an {@link InMemoryEventBus}
 * wrapped for persistent-event queueing, and — when {@link EventEngineConfig#outboxEnabled()}
 * and a {@link eu.exeris.kernel.spi.persistence.PersistenceEngine} are both available — an
 * {@link OutboxOrchestrator} wired to a JDBC outbox store and a local bus broker port.
 *
 * <p>Without a bound persistence engine the outbox orchestrator is never constructed and
 * {@link #start()} / {@link #close()} simply skip it: the engine still runs (bus, queue and
 * loop are unconditional), it just has nothing to poll and re-deliver.
 */
final class CommunityEventEngine implements EventEngine {

    private final CommunityEventRegistry registry;
    private final CommunityEventQueue queue;
    private final CommunityEventLoop loop;
    private final EventBus bus;
    private final OutboxOrchestrator outboxOrchestrator;
    private final boolean failFastOnFull;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong publishedTotal = new AtomicLong(0L);

    /* default */ CommunityEventEngine(EventEngineConfig config) {
        Objects.requireNonNull(config, "config");
        this.registry = new CommunityEventRegistry();
        this.failFastOnFull = config.busPublishFailFast();
        this.queue = new CommunityEventQueue(config.queueCapacity(), failFastOnFull);
        this.loop = new CommunityEventLoop(registry, queue, config.batchSize());

        InMemoryEventBus delegateBus = new InMemoryEventBus(registry);
        this.bus = new PersistentQueueingBus(config.engineName(), delegateBus, queue,
                registry, publishedTotal, failFastOnFull);
        this.outboxOrchestrator = buildOutboxOrchestrator(config, delegateBus, registry);
    }

    /**
     * Returns this engine's bus, a persistent-queueing decorator over the Core
     * {@link InMemoryEventBus} that also enqueues persistent events into {@link #queue()}
     * before delegating.
     *
     * @return the non-null event bus
     */
    @Override
    public EventBus bus() {
        return bus;
    }

    /**
     * Returns this engine's {@link CommunityEventQueue}, the bounded, heap-backed in-memory
     * buffer that persistent publishes are enqueued into and {@link #loop()} drains.
     *
     * @return the non-null event queue
     */
    @Override
    public EventQueue queue() {
        return queue;
    }

    /**
     * Returns this engine's {@link CommunityEventLoop}, the single-virtual-thread drain loop
     * that dispatches queued batches to registered
     * {@link eu.exeris.kernel.spi.events.EventBatchProcessor}s.
     *
     * @return the non-null event loop
     */
    @Override
    public EventLoop loop() {
        return loop;
    }

    /**
     * Returns this engine's {@link CommunityEventRegistry}, the heap-backed ordinal↔name
     * mapping shared by the bus and the loop.
     *
     * @return the non-null event registry
     */
    @Override
    public EventRegistry registry() {
        return registry;
    }

    /**
     * Starts the event loop and, when an outbox orchestrator was built, starts it too.
     *
     * <p>Idempotent: a second call while already started is a no-op (guarded by a
     * compare-and-set on the started flag). If the orchestrator fails to start, this method
     * compensates by stopping the loop it just started and resetting the started flag to
     * {@code false} before rethrowing, so a failed {@code start()} leaves the engine in the
     * same not-started state it began in rather than half-running.
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // partial-start compensation
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        loop.start();
        if (outboxOrchestrator != null) {
            try {
                outboxOrchestrator.start();
            } catch (RuntimeException ex) {
                loop.stop();
                started.set(false);
                throw ex;
            }
        }
    }

    /**
     * Stops the outbox orchestrator (if any) and then the event loop.
     *
     * <p>Idempotent: a call while not started is a no-op (guarded by a compare-and-set on the
     * started flag). The orchestrator is stopped before the loop so no further outbox-relayed
     * publish reaches a loop that has already begun draining down.
     */
    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (outboxOrchestrator != null) {
            outboxOrchestrator.stop();
        }
        loop.stop();
    }

    /**
     * Returns a point-in-time snapshot combining this engine's own published-count counter
     * with the loop's processed/failed counters and the queue's current depth and capacity.
     *
     * @return the current engine stats snapshot
     */
    @Override
    public EventEngineStats stats() {
        return new EventEngineStats(
                publishedTotal.get(),
                loop.processedTotal(),
                loop.failedTotal(),
                queue.size(),
                queue.capacity(),
                loop.averageDispatchNanos(),
                loop.isRunning()
        );
    }

    private static OutboxOrchestrator buildOutboxOrchestrator(
            EventEngineConfig config,
            EventBus delegateBus,
            CommunityEventRegistry registry) {
        if (!config.outboxEnabled() || !KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            return null;
        }

        OutboxEventStore eventStore = new CommunityJdbcOutboxEventStoreAdapter(
                KernelProviders.persistenceEngine(),
                registry);
        OutboxBrokerPort brokerPort = new CommunityEventBusOutboxBrokerPort(delegateBus);

        return OutboxOrchestrator.builder()
                .eventStore(eventStore)
                .brokerPort(brokerPort)
                .batchSize(config.outboxBatchSize())
                .build();
    }

    /**
     * Decorates a delegate {@link EventBus} so that every publish of a persistent
     * {@link EventDescriptor} is also pushed onto the engine's {@link EventQueue} before the
     * delegate is invoked — the seam that lets the queue-backed {@link CommunityEventLoop}
     * and the fire-and-forget in-memory bus share one publish call.
     *
     * <p>Non-persistent descriptors ({@code descriptor.isPersistent() == false}) bypass the
     * queue entirely and go straight to the delegate. This queue is heap-backed and separate
     * from the JDBC-backed {@link OutboxOrchestrator} this engine may also build: the two
     * share no code path.
     */
    private static final class PersistentQueueingBus implements EventBus {

        private final String engineName;
        private final EventBus delegate;
        private final EventQueue queue;
        private final CommunityEventRegistry registry;
        private final AtomicLong publishedTotal;
        private final boolean failFastOnFull;

        private PersistentQueueingBus(
                String engineName,
                EventBus delegate,
                EventQueue queue,
                CommunityEventRegistry registry,
                AtomicLong publishedTotal,
                boolean failFastOnFull) {
            this.engineName = Objects.requireNonNull(engineName, "engineName");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.queue = Objects.requireNonNull(queue, "queue");
            this.registry = Objects.requireNonNull(registry, "registry");
            this.publishedTotal = Objects.requireNonNull(publishedTotal, "publishedTotal");
            this.failFastOnFull = failFastOnFull;
        }

        /**
         * Counts the publish, enqueues {@code payload} onto the engine queue when
         * {@code descriptor} is persistent, then delegates to the wrapped bus.
         *
         * <p>The queue push retains its own reference to {@code payload} (per
         * {@link EventQueue#push}); the payload handed to the delegate afterward is the same
         * caller-owned instance, so both the queue and the delegate's fan-out see a correctly
         * counted reference.
         *
         * @param descriptor routing metadata (non-null)
         * @param payload    event payload; ownership transfers to this bus (non-null)
         * @throws eu.exeris.kernel.spi.exceptions.events.EventBusException EX-EVENT-6002 if
         *         {@code descriptor} is persistent and {@link EventQueue#push} either refuses
         *         the event (queue full in fail-fast mode) or raises an unexpected
         *         {@code RuntimeException}
         */
        @Override
        public void publish(EventDescriptor descriptor, EventPayload payload) {
            publishedTotal.incrementAndGet();
            enqueuePersistent(descriptor, payload);
            delegate.publish(descriptor, payload);
        }

        /**
         * Delegates subscription to the wrapped bus unchanged; this decorator has no
         * subscribe-side behaviour of its own.
         *
         * @param eventType the event type name (non-null)
         * @param handler   the handler (non-null)
         * @return the token returned by the delegate
         */
        @Override
        public SubscriptionToken subscribe(String eventType, EventHandler handler) {
            return delegate.subscribe(eventType, handler);
        }

        /**
         * Delegates unsubscription to the wrapped bus unchanged.
         *
         * @param token the token returned by {@link #subscribe}
         */
        @Override
        public void unsubscribe(SubscriptionToken token) {
            delegate.unsubscribe(token);
        }

        /**
         * Same queueing-then-delegating behaviour as {@link #publish}, but blocks until the
         * delegate's handlers have completed.
         *
         * @param descriptor routing metadata (non-null)
         * @param payload    event payload; ownership transfers to this bus (non-null)
         * @throws InterruptedException if the calling thread is interrupted while waiting
         * @throws eu.exeris.kernel.spi.exceptions.events.EventBusException EX-EVENT-6002 if
         *         {@code descriptor} is persistent and {@link EventQueue#push} either refuses
         *         the event (queue full in fail-fast mode) or raises an unexpected
         *         {@code RuntimeException}
         */
        @Override
        public void publishAndAwait(EventDescriptor descriptor, EventPayload payload) throws InterruptedException {
            publishedTotal.incrementAndGet();
            enqueuePersistent(descriptor, payload);
            delegate.publishAndAwait(descriptor, payload);
        }

        private void enqueuePersistent(EventDescriptor descriptor, EventPayload payload) {
            if (!descriptor.isPersistent()) {
                return;
            }

            try {
                if (!queue.push(descriptor, payload)) {
                    payload.close();
                    throw failedPushException(descriptor);
                }
            } catch (EventBusException ex) {
                throw ex;
            } catch (RuntimeException ex) { //NOPMD AvoidCatchingGenericException — untrusted SPI queue boundary
                // push() retained internally then threw; it already closed its own retain.
                // Close the caller's ref to prevent a leak.
                payload.close();
                throw new EventBusException("Failed to enqueue persistent event '"
                        + registry.nameOfOrdinal(descriptor.eventTypeOrdinal()) + "'", ex);
            }
        }

        /**
         * Builds the {@code EX-EVENT-6002} exception for a failed persistent-queue push,
         * choosing between the two failure shapes {@link CommunityEventQueue#push} can produce.
         * Fail-fast mode uses the typed {@link EventBusException#publishOverflow} factory
         * carrying the documented Glass-Box rawArgs {@code [eventType, queueDepth,
         * queueCapacity]}; blocking mode's {@code false} return only happens on interrupt, so
         * it falls back to the message-only constructor.
         *
         * <p>On the fail-fast branch this also emits {@link CommunityEventQueueOverflowEvent}
         * so operator dashboards can attribute overflow rates to specific engine + event-type
         * pairs — the publishing-caller exception is per-call and leaves no post-mortem trail.
         */
        private EventBusException failedPushException(EventDescriptor descriptor) {
            String eventType = registry.nameOfOrdinal(descriptor.eventTypeOrdinal());
            if (failFastOnFull) {
                CommunityEventQueueOverflowEvent.emit(
                        engineName, eventType, queue.size(), queue.capacity());
                return EventBusException.publishOverflow(eventType, queue.size(), queue.capacity());
            }
            return new EventBusException("Interrupted while enqueueing persistent event '" + eventType + '\'');
        }
    }
}
