/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p>When {@link #publish} is called on a bus that is not {@linkplain #isBrokered() brokered},
 * with N registered handlers for a type:
 * <ol>
 *   <li>{@code N == 0}: {@link EventPayload#close()} called immediately — slab returned.</li>
 *   <li>{@code N == 1}: payload passed as-is (initial refCount = 1); handler closes.</li>
 *   <li>{@code N > 1}: {@link EventPayload#retain()} called {@code (N-1)} times before
 *       any handler fork — total refCount becomes N. Each handler's
 *       {@link EventPayload#close()} decrements by 1. The last close returns the
 *       backing memory to the pool.</li>
 * </ol>
 * <p>A handler is therefore always given a correctly-counted payload and simply closes it; the
 * counting is the bus's job, never the handler's.
 *
 * <p>A brokered bus does not fan the caller's payload out. It copies the payload onto the wire and
 * releases the caller's reference exactly once, whether the publication is accepted or refused;
 * the protocol above applies to the payload it creates when it consumes the publication and hands
 * it to its handlers.
 *
 * <h2>Ordering</h2>
 * <p>The bus is <b>unordered by design</b> (ADR-049): it makes no per-key, per-stream or
 * per-aggregate ordering promise, and {@link EventDescriptor#FLAG_ORDERED} on a descriptor is a
 * routing hint here, not a guarantee. Ordering is a property of the durable-log surface —
 * {@link EventStreamAppender} — which linearizes concurrent appends per {@link StreamId}.
 *
 * <p><b>Allocation:</b> allocates (dispatch state per publication on the standard binding, which
 * starts one virtual thread per handler); a native binding performs zero heap allocation per
 * {@link #publish} after start
 * <p><b>Thread confinement:</b> virtual-thread-safe — publish, subscribe and unsubscribe may all
 * be called concurrently. The publishing thread must itself be bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE}; handler threads spawned by
 * {@link #publish} do not inherit that, or any other, {@code ScopedValue} binding — only the
 * in-thread handlers invoked by {@link #publishAndAwait} on a bus that is not brokered do. A
 * brokered bus's handlers observe none of the publisher's bindings
 * <p><b>Ownership:</b> {@link #publish} and {@link #publishAndAwait} take the caller's payload
 * reference; from then on the bus owns the fan-out and each handler closes the reference it was
 * given. The caller never closes a published payload, on any outcome
 *
 * @implSpec An implementation that is not brokered performs the broadcast retain protocol above,
 *           and performs it on every exit path: a dispatch that fails part-way — a
 *           {@link EventPayload#retain()} that throws, a handler thread that cannot be started —
 *           releases the references no handler will ever own before the failure reaches the
 *           caller. Otherwise a failed publish leaks a slab permanently. A brokered implementation
 *           releases the caller's reference exactly once, before {@link #publish} or
 *           {@link #publishAndAwait} returns or throws, and performs the retain protocol on the
 *           payload it creates on consume. Every implementation accepts a payload it cannot
 *           deliver: with zero subscribers the payload is closed immediately rather than dropped.
 * @implNote The standard binding is an in-memory routing table with asynchronous virtual-thread
 *           dispatch over heap-backed payloads; a native binding is an off-heap routing table with
 *           slab-allocated subscriber slots and O(1) ordinal lookup.
 * @since 0.5
 * @see EventDescriptor
 * @see EventPayload
 * @see EventHandler
 */
public interface EventBus {

    /**
     * Hands an event to the bus for fan-out and returns without waiting for any handler —
     * completion is not observable through this method.
     *
     * @param descriptor routing metadata (non-null)
     * @param payload    event payload (non-null; use {@link EventPayload#empty()} for no-data events)
     * @throws EventBusException {@code EX-EVENT-6002} when the backing queue is at capacity and
     *         the engine is configured to refuse rather than block
     *         ({@link EventEngineConfig#busPublishFailFast()}); {@code rawArgs} carry
     *         {@code [String eventType, long queueDepth, long queueCapacity]}.
     *         {@code EX-EVENT-6009} when the bus does not accept the event for any other reason;
     *         {@code rawArgs} carry {@code [int eventTypeOrdinal, String reason]} and the upstream
     *         failure, when there is one, is the cause
     * @apiNote Ownership of {@code payload} passes to the bus on entry: do not close it after this
     *          call, on success or on failure. Handlers may already be running by the time this
     *          returns, so do not treat a normal return as delivery — on a bus that is not
     *          brokered, use {@link #publishAndAwait} when the caller needs that.
     * @implNote The standard binding dispatches asynchronously on virtual threads; a native
     *           binding writes the event to a ring buffer that its loop drains. On
     *           {@code EX-EVENT-6002} the publisher must not retry inline — the exception is
     *           propagated to the caller's structured-scope boundary so the joiner policy decides
     *           whether to fail fast or shed the event.
     */
    void publish(EventDescriptor descriptor, EventPayload payload);

    /**
     * Registers a handler to receive every subsequent publication of the named event type, and
     * returns the handle that revokes it.
     *
     * @param eventType the event type name (e.g. {@code "UserCreated"})
     * @param handler   the handler (non-null)
     * @return an opaque {@link SubscriptionToken} for later unsubscription
     * @throws EventBusException {@code EX-EVENT-6011} if the subscription cannot be registered;
     *         {@code rawArgs} carry {@code [String eventType]}
     * @apiNote Subscriptions take effect for publications made after this call returns; an event
     *          published concurrently may or may not reach the new handler.
     * @implNote The in-memory binding rejects a subscription to a type that
     *           {@link EventRegistry} does not know, so register the type before subscribing.
     */
    SubscriptionToken subscribe(String eventType, EventHandler handler);

    /**
     * Revokes the subscription the token identifies, so no publication after this call reaches
     * that handler.
     *
     * @param token the token returned by {@link #subscribe}
     * @implSpec Tolerates a token that names no live subscription — an already-revoked one, or
     *           {@link SubscriptionToken#INVALID} — as a no-op rather than an error.
     * @implNote O(1) on the Enterprise binding, which addresses the subscriber slot directly from
     *           the token's ordinal.
     */
    void unsubscribe(SubscriptionToken token);

    /**
     * Hands an event to the bus and blocks until the bus has finished with it: on a bus that is not
     * {@linkplain #isBrokered() brokered}, until every handler has finished with it, so a normal
     * return means delivery actually happened.
     *
     * <p>On a bus that is not brokered, how the wait is implemented is not part of this contract,
     * and the scoped-value bindings a handler observes follow from it: the in-memory binding runs
     * handlers on the calling thread, so they observe every {@code ScopedValue} the publisher had
     * bound — including values the kernel does not define (ADR-066). A binding that dispatches
     * onto other threads can only deliver what it can name.
     *
     * <p>On a brokered bus the call blocks until the broker has acknowledged the publication, to
     * the durability the binding is configured for. It promises nothing about any handler: a
     * handler may run after this call has returned, on another thread or in another process, and
     * observes none of the publisher's {@code ScopedValue} bindings. The caller's payload reference
     * is released before this call returns or throws.
     *
     * @param descriptor routing metadata (non-null)
     * @param payload    event payload (non-null)
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws EventBusException    {@code EX-EVENT-6010} if the event was delivered and one or
     *         more handler invocations failed — on a bus that is not brokered only; a brokered bus
     *         never throws it. {@code rawArgs} carry
     *         {@code [int eventTypeOrdinal, int failedHandlerCount]} and the individual handler
     *         failures are attached as suppressed exceptions. {@code EX-EVENT-6002} or
     *         {@code EX-EVENT-6009} if the bus does not accept the event, as for {@link #publish}
     * @apiNote Same ownership transfer as {@link #publish} — the caller does not close
     *          {@code payload}. Never call this from inside an event handler: on a bus that is
     *          not brokered the wait is on handlers, and a handler waiting on handlers can
     *          deadlock.
     * @implNote A native binding spins on the processed-event counter for a bounded wait.
     */
    void publishAndAwait(EventDescriptor descriptor, EventPayload payload) throws InterruptedException;

    /**
     * Reports whether a publication reaches handlers only through an external broker, after the
     * publishing call has returned.
     *
     * <p>The answer selects which half of this contract the bus keeps. A bus that is not brokered
     * fans the caller's payload out to its handlers itself, and {@link #publishAndAwait} returns
     * once every handler has finished. A brokered bus hands the publication to the broker, a
     * handler receives it from there, and {@link #publishAndAwait} awaits the broker's
     * acknowledgement rather than any handler.
     *
     * @return {@code true} when a publication reaches handlers only through an external broker,
     *         after the publishing call has returned; {@code false} when the bus dispatches to its
     *         handlers itself
     * @implSpec The default returns {@code false}.
     * @since 0.12
     */
    default boolean isBrokered() {
        return false;
    }
}
