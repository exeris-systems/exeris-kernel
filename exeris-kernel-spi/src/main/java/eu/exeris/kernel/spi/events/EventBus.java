/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.events.EventBusException;

/**
 * SPI: Event Bus for publish/subscribe messaging within the kernel.
 *
 * <h2>Routing Model</h2>
 * <p>Routing uses {@link EventDescriptor#eventTypeOrdinal()} — an integer intern
 * assigned by {@link EventRegistry} at registration time. O(1), no {@link String}
 * comparison in the hot path.
 *
 * <h2>Broadcast RAII Protocol</h2>
 * <p>When {@link #publish} is called with N registered handlers for a type:
 * <ol>
 *   <li>{@code N == 0}: {@link EventPayload#close()} called immediately — slab returned.</li>
 *   <li>{@code N == 1}: payload passed as-is (initial refCount = 1); handler closes.</li>
 *   <li>{@code N > 1}: {@link EventPayload#retain()} called {@code (N-1)} times before
 *       any handler fork — total refCount becomes N. Each handler's
 *       {@link EventPayload#close()} decrements by 1. The last close returns the
 *       backing memory to the pool.</li>
 * </ol>
 * This protocol is <b>the responsibility of the EventBus implementation</b>.
 * Handlers always receive a correctly-counted payload and simply close it.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li><b>Standard</b>: In-memory routing table with asynchronous virtual-thread dispatch.
 *       Payloads are heap-backed — retain/close are lightweight no-ops.</li>
 *   <li><b>High-performance native</b>: Off-heap routing table with slab-allocated subscriber slots.
 *       O(1) ordinal lookup. Zero heap allocation per publish after startup.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see EventDescriptor
 * @see EventPayload
 * @see EventHandler
 */
public interface EventBus {

    /**
     * Publishes an event to all registered handlers for its type.
     *
     * <p><b>RAII contract:</b> The caller transfers ownership of {@code payload} to
     * the bus. After this call returns, the caller MUST NOT call {@code payload.close()}
     * (the bus and handlers manage the refCount).
     *
     * <p>Standard implementations dispatch asynchronously on virtual threads and return
     * after all handlers complete. High-performance native implementations write the
     * descriptor and payload reference to a ring buffer and return immediately (O(1)).
     *
     * @param descriptor routing metadata (non-null)
     * @param payload    event payload (non-null; use {@link EventPayload#empty()} for no-data events)
     * @throws EventBusException if the queue is full and the implementation cannot accept the event
     */
    void publish(EventDescriptor descriptor, EventPayload payload);

    /**
     * Subscribes a handler to receive events of the given type name.
     *
     * @param eventType the event type name (e.g. {@code "UserCreated"})
     * @param handler   the handler (non-null)
     * @return an opaque {@link SubscriptionToken} for later unsubscription
     * @throws EventBusException if the subscription cannot be registered
     */
    SubscriptionToken subscribe(String eventType, EventHandler handler);

    /**
     * Unsubscribes a previously registered handler. O(1) for Enterprise. No-op for invalid token.
     *
     * @param token the token returned by {@link #subscribe}
     */
    void unsubscribe(SubscriptionToken token);

    /**
     * Publishes an event and blocks until all handlers have completed.
     *
     * <p>Same RAII ownership transfer as {@link #publish} — caller must NOT close payload.
     * Standard implementations join structured concurrency after forking all handlers.
     * High-performance native implementations spin on the processed-event counter (bounded wait).
     *
     * <p>MUST NOT be called from within an event handler — deadlock risk.
     *
     * @param descriptor routing metadata (non-null)
     * @param payload    event payload (non-null)
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws EventBusException    if a handler invocation failed
     */
    void publishAndAwait(EventDescriptor descriptor, EventPayload payload) throws InterruptedException;
}
