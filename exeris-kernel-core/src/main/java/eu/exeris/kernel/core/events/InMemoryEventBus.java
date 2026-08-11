/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.core.events.jfr.EventBusPublishEvent;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import jdk.jfr.FlightRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core: In-Memory Event Bus — local distribution mechanism for subscribers within the same Kernel instance.
 *
 * <h2>Routing</h2>
 * <p>Routing uses {@link EventDescriptor#eventTypeOrdinal()} — O(1) integer key lookup via
 * {@code ConcurrentHashMap<Integer, CopyOnWriteArrayList<Slot>>}. No {@link String}
 * comparison on the hot dispatch path.
 *
 * <h2>RAII Broadcast Protocol</h2>
 * <ul>
 *   <li>{@code N == 0}: {@link EventPayload#close()} called immediately — no leak.</li>
 *   <li>{@code N == 1}: payload passed as-is; handler closes.</li>
 *   <li>{@code N > 1}: {@link EventPayload#retain()} called {@code (N-1)} times before
 *       forking; total refCount = N; each handler's close() decrements by 1.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * <p>{@link #publish} is fire-and-forget: after retain normalization, the calling thread
 * starts one virtual thread per handler and returns immediately without waiting.
 *
 * <p>{@link #publishAndAwait} runs the handlers <em>on the calling thread</em>, in subscription
 * order, and returns once all have completed. This is the preferred pattern when the caller needs
 * delivery confirmation.
 *
 * <h2>ScopedValue Propagation (JEP 506)</h2>
 * <p>The two paths differ, and the difference is not cosmetic:
 * <ul>
 *   <li>{@link #publishAndAwait} — a handler observes <b>every</b> binding the publisher had,
 *       including scoped values the kernel does not know about, because it never leaves the
 *       publisher's thread. This is the contract {@code AbstractEventBusTck}'s golden case
 *       asserts, and running in-thread is what keeps it reachable without {@code --enable-preview}
 *       (ADR-066).</li>
 *   <li>{@link #publish} — a handler observes <b>no</b> bindings. A plain
 *       {@code Thread.ofVirtual().start(...)} does not inherit {@code ScopedValue} bindings; only
 *       a fork inside a structured scope does. This has always been the behaviour of this path;
 *       the previous claim here that both paths inherit was measured false and corrected in
 *       v0.11.</li>
 * </ul>
 * <p>Zero {@code ThreadLocal} either way.
 *
 * @since 0.5.0
 */
// CouplingBetweenObjects: EventBus coordinates registry, handlers and JFR — inherent to the bus role
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.CloseResource"})
// CyclomaticComplexity: aggregate inflated by helpers; no single method exceeds threshold.
// CloseResource: TrackingWrapper ownership transferred across lambda/loop boundaries —
//   closed deterministically by TWR in fork lambda or closeUnclosed() on interrupt path.
public final class InMemoryEventBus implements EventBus {

    /** Unique bus ID embedded in each {@link SubscriptionToken}. */
    private static final AtomicInteger BUS_ID_SEQ = new AtomicInteger(0);

    /** Minimum subscriber count that requires explicit retain before dispatch. */
    private static final int MULTI_SUBSCRIBER_THRESHOLD = 1;

    private final int           busId;
    private final EventRegistry registry;

    /** Subscriber table: eventTypeOrdinal → ordered list of Slot. */
    private final Map<Integer, List<Slot>> subscribers =
            new ConcurrentHashMap<>();

    /** Monotonically-increasing sequence for subscription ordinals — O(1) token generation. */
    private final AtomicLong subscriptionSeq = new AtomicLong(0L);

    /**
     * Creates the bus.
     *
     * @param registry the active event type registry — used to resolve ordinals on subscribe
     */
    public InMemoryEventBus(EventRegistry registry) {
        this.busId    = BUS_ID_SEQ.incrementAndGet();
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    // =========================================================================
    // EventBus — publish (fire-and-forget)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
    * <p>If {@code N == 0} handlers are registered, closes the payload immediately.
    * Otherwise retains the payload {@code (N-1)} times and starts one virtual thread
    * per subscriber directly from the calling thread. The calling thread returns
     * immediately and does not wait for handlers.
     *
     * <p>Fire-and-forget semantics are preserved: the calling thread returns immediately
     * after starting all handler virtual threads. ScopedValue bindings are inherited at
     * virtual thread creation time — no latch, no wait.
     */
    @Override
    public void publish(EventDescriptor descriptor, EventPayload payload) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload,    "payload");

        List<Slot> slots = resolveSlots(descriptor.eventTypeOrdinal());
        emitJfr(descriptor.eventTypeOrdinal(), slots.size(), false);

        if (slots.isEmpty()) {
            payload.close();
            return;
        }

        int slotCount = slots.size();
        if (slotCount > MULTI_SUBSCRIBER_THRESHOLD) {
            for (int i = 1; i < slotCount; i++) {
                payload.retain();
            }
        }

        for (Slot slot : slots) {
            EventPayload slotPayload = payload;
            Thread.ofVirtual().start(() -> slot.handler().handle(descriptor, slotPayload));
        }
    }

    // =========================================================================
    // EventBus — publishAndAwait (blocks calling thread until all handlers done)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p><b>Handlers run on the calling thread, in subscription order</b> — the method blocks until
     * all of them have completed, which is what it promises, so it does the work rather than
     * delegating it and waiting.
     *
     * <p>That choice is what preserves this path's {@code ScopedValue} contract without a preview
     * API (ADR-066). The contract is that a handler observes <em>whatever</em> the publisher had
     * bound, including scoped values the kernel has never heard of — an application's own trace or
     * tenant context. {@code StructuredTaskScope} delivered that by inheriting bindings into forked
     * subtasks; no GA mechanism can, because building a {@code ScopedValue.Carrier} requires naming
     * each value and nothing can enumerate a thread's live bindings. Staying on the publisher's
     * thread satisfies the contract by construction instead of reconstructing it.
     *
     * <p>The trade is latency: handler durations now sum rather than overlap, and a slow handler
     * delays its successors. Callers that want fan-out have {@link #publish}, which is
     * fire-and-forget and unchanged.
     *
     * <p>Failure handling is unchanged — every handler runs, failures are collected, and the first
     * is thrown once all have finished.
     */
    // java:S1181 — the same exemption the five other release-before-rethrow sites carry (see
    // NativeCipherContext, SecurityInterceptor, PaqsScheduler). The Throwable is not handled here:
    // it is rethrown unchanged, and the catch exists only so the wrappers no handler will ever
    // reach are released first. Narrowing it would restore the leak for exactly the types that
    // reach this path — an Error out of a handler is the one that motivated the fix.
    @Override
    @SuppressWarnings("java:S1181")
    public void publishAndAwait(EventDescriptor descriptor, EventPayload payload)
            throws InterruptedException {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload,    "payload");

        List<Slot> slots = resolveSlots(descriptor.eventTypeOrdinal());
        emitJfr(descriptor.eventTypeOrdinal(), slots.size(), true);

        if (slots.isEmpty()) {
            payload.close();
            return;
        }

        int slotCount = slots.size();
        // Build one TrackingWrapper per slot so deterministic cleanup is possible on any
        // early-exit path (interrupt, RuntimeException) — every retain() is balanced.
        List<TrackingWrapper> wrappers = buildWrappers(payload, slotCount);

        // Plain list, deliberately: dispatch no longer crosses a thread boundary, so the
        // concurrent queue this used to be would buy nothing but an allocation and a CAS per add.
        List<Throwable> failures = new ArrayList<>(slotCount);
        try {
            dispatchOnCallingThread(slots, wrappers, descriptor, failures);
        } catch (InterruptedException interruptEx) {
            closeUnclosed(wrappers);
            Thread.currentThread().interrupt();
            throw interruptEx;
        } catch (Throwable escaped) { //NOPMD AvoidCatchingThrowable — release before rethrow, SPI boundary
            // Only InterruptedException was caught here, so anything else — an Error out of a handler,
            // or a failure closing one wrapper — left the remaining wrappers open and leaked the
            // payload's off-heap refcount permanently. The per-handler try-with-resources inside the
            // loop covers the handler that threw; it cannot cover the ones never reached.
            closeUnclosed(wrappers);
            throw escaped;
        }
        throwIfFailed(failures);
    }

    // =========================================================================
    // EventBus — subscribe / unsubscribe
    // =========================================================================

    @Override
    public SubscriptionToken subscribe(String eventType, EventHandler handler) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler,   "handler");

        int ordinal = registry.ordinalOf(eventType);
        if (ordinal < 0) {
            throw new EventBusException(
                    "Cannot subscribe to unregistered event type: '" + eventType + "'. "
                    + "Register it in EventRegistry before subscribing.");
        }

        long seq  = subscriptionSeq.incrementAndGet();
        Slot slot = new Slot(seq, handler);
        subscribers.computeIfAbsent(ordinal, _ -> new CopyOnWriteArrayList<>()).add(slot);
        return new SubscriptionToken(busId, seq);
    }

    @Override
    public void unsubscribe(SubscriptionToken token) {
        if (!token.isValid() || token.busId() != busId) {
            return;
        }
        long seq = token.subscriptionOrdinal();
        for (List<Slot> slots : subscribers.values()) {
            slots.removeIf(slotEntry -> slotEntry.ordinalSeq() == seq);
        }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static List<TrackingWrapper> buildWrappers(EventPayload payload, int slotCount) {
        List<TrackingWrapper> wrappers = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            if (i > 0) {
                payload.retain();
            }
            wrappers.add(new TrackingWrapper(payload));
        }
        return wrappers;
    }

    /**
     * Invokes each handler in subscription order on the caller's thread.
     *
     * <p>Interruption is honoured <em>between</em> handlers rather than inside one: a handler is an
     * application SPI callback with its own resources, so aborting mid-invocation would leave it no
     * chance to unwind. The remaining wrappers are closed by the caller's catch.
     */
    private static void dispatchOnCallingThread(List<Slot> slots,
                                                List<TrackingWrapper> wrappers,
                                                EventDescriptor descriptor,
                                                List<Throwable> failures) throws InterruptedException {
        int slotCount = slots.size();
        for (int i = 0; i < slotCount; i++) {
            if (Thread.interrupted()) {
                throw new InterruptedException("publishAndAwait interrupted after " + i + " handler(s)");
            }
            Slot slot = slots.get(i);
            try (TrackingWrapper wrapper = wrappers.get(i)) {
                slot.handler().handle(descriptor, wrapper);
            } catch (RuntimeException handlerEx) { //NOPMD AvoidCatchingGenericException — SPI boundary
                failures.add(handlerEx);
            }
        }
    }

    private static void closeUnclosed(List<TrackingWrapper> wrappers) {
        for (TrackingWrapper w : wrappers) {
            if (!w.isClosed()) {
                w.close();
            }
        }
    }

    private static void throwIfFailed(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }
        EventBusException busException =
                new EventBusException("One or more event handlers failed during publishAndAwait");
        failures.forEach(busException::addSuppressed);
        throw busException;
    }

    private List<Slot> resolveSlots(int ordinal) {
        List<Slot> slots = subscribers.get(ordinal);
        // CopyOnWriteArrayList already provides safe snapshot iteration; no copy needed.
        return slots == null ? List.of() : slots;
    }

    private static void emitJfr(int ordinal, int handlerCount, boolean awaitMode) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        EventBusPublishEvent evt = new EventBusPublishEvent();
        if (evt.isEnabled()) {
            evt.eventTypeOrdinal = ordinal;
            evt.handlerCount     = handlerCount;
            evt.awaitMode        = awaitMode;
            evt.commit();
        }
    }

    /**
     * Internal subscription slot — pairs ordinalSeq with the handler.
     * Valhalla-ready: primitive {@code long} key, no identity operations.
     */
    private record Slot(long ordinalSeq, EventHandler handler) {}

    /**
     * Delegates all {@link EventPayload} operations to the underlying ref.
     * Tracks whether {@link #close()} has been called so the bus can deterministically
     * release any ref that a handler failed to close, without risking over-release.
     */
    private static final class TrackingWrapper implements EventPayload {
        private final EventPayload delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        /* default */ TrackingWrapper(EventPayload delegate) {
            this.delegate = delegate;
        }

        /* default */ boolean isClosed() {
            return closed.get();
        }

        @Override
        public java.lang.foreign.MemorySegment segment() {
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
