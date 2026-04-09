/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

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

final class CommunityEventEngine implements EventEngine {

    private final CommunityEventRegistry registry;
    private final CommunityEventQueue queue;
    private final CommunityEventLoop loop;
    private final EventBus bus;
    private final OutboxOrchestrator outboxOrchestrator;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong publishedTotal = new AtomicLong(0L);

    @SuppressWarnings("PMD.CommentDefaultAccessModifier")
    CommunityEventEngine(EventEngineConfig config) {
        Objects.requireNonNull(config, "config");
        this.registry = new CommunityEventRegistry();
        this.queue = new CommunityEventQueue(config.queueCapacity());
        this.loop = new CommunityEventLoop(registry, queue, config.batchSize());

        InMemoryEventBus delegateBus = new InMemoryEventBus(registry);
        this.bus = new PersistentQueueingBus(delegateBus, queue, registry, publishedTotal);
        this.outboxOrchestrator = buildOutboxOrchestrator(config, delegateBus, registry);
    }

    @Override
    public EventBus bus() {
        return bus;
    }

    @Override
    public EventQueue queue() {
        return queue;
    }

    @Override
    public EventLoop loop() {
        return loop;
    }

    @Override
    public EventRegistry registry() {
        return registry;
    }

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

    private static final class PersistentQueueingBus implements EventBus {

        private final EventBus delegate;
        private final EventQueue queue;
        private final CommunityEventRegistry registry;
        private final AtomicLong publishedTotal;

        private PersistentQueueingBus(
                EventBus delegate,
                EventQueue queue,
                CommunityEventRegistry registry,
                AtomicLong publishedTotal) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.queue = Objects.requireNonNull(queue, "queue");
            this.registry = Objects.requireNonNull(registry, "registry");
            this.publishedTotal = Objects.requireNonNull(publishedTotal, "publishedTotal");
        }

        @Override
        public void publish(EventDescriptor descriptor, EventPayload payload) {
            publishedTotal.incrementAndGet();
            enqueuePersistent(descriptor, payload);
            delegate.publish(descriptor, payload);
        }

        @Override
        public SubscriptionToken subscribe(String eventType, EventHandler handler) {
            return delegate.subscribe(eventType, handler);
        }

        @Override
        public void unsubscribe(SubscriptionToken token) {
            delegate.unsubscribe(token);
        }

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
                boolean queued = queue.push(descriptor, payload);
                if (!queued) {
                    // push() returns false only on InterruptedException (interrupt flag already restored by queue).
                    // Bus took ownership but cannot deliver — close the caller's ref before propagating.
                    payload.close();
                    throw new EventBusException("Interrupted while enqueueing persistent event '"
                            + registry.nameOfOrdinal(descriptor.eventTypeOrdinal()) + "'");
                }
            } catch (EventBusException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                // push() retained internally then threw; it already closed its own retain.
                // Close the caller's ref to prevent a leak.
                payload.close();
                throw new EventBusException("Failed to enqueue persistent event '"
                        + registry.nameOfOrdinal(descriptor.eventTypeOrdinal()) + "'", ex);
            }
        }
    }
}
