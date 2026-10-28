/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.core.events.jfr.EventBusPublishEvent;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.IsolationKeyRegistry;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import jdk.jfr.FlightRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

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
 * <h2>Concurrency (Java 26)</h2>
 * <p>{@link #publish} is fire-and-forget: after retain normalization, the calling thread
 * starts one virtual thread per handler and returns immediately without waiting.
 *
 * <p>{@link #publishAndAwait} opens the scope directly on the <em>calling</em> thread
 * (which is the owner), forks all handlers, joins, and returns — blocking until all
 * handlers complete. This is the preferred pattern when the caller needs delivery confirmation.
 *
 * <h2>Isolation Scoping (ADR-101)</h2>
 * <p>A subscriber made with {@link #subscribeScoped} receives only the publications whose
 * {@link EventDescriptor#isolationOrdinal()} equals its own; an unscoped subscriber receives every
 * publication. This bus does not stamp the ordinal: the publisher side resolves it from the bound
 * storage context before the descriptor reaches here, so the ordinal is taken as given.
 *
 * <h2>ScopedValue Propagation (JEP 506)</h2>
 * <p>Virtual threads started by {@code publish()} and {@code publishAndAwait()} are created
 * from the calling thread's execution context, so they inherit active {@code ScopedValue}
 * bindings at call time — zero {@code ThreadLocal}, zero manual propagation.
 *
 * @since 0.5
 */
// CouplingBetweenObjects: EventBus coordinates registry, handlers and JFR — inherent to the bus role
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.CloseResource", "PMD.GodClass", "PMD.TooManyMethods"})
// CyclomaticComplexity: aggregate inflated by helpers; no single method exceeds threshold.
// CloseResource: TrackingWrapper ownership transferred across lambda/loop boundaries —
//   closed deterministically by TWR in fork lambda or closeUnclosed() on interrupt path.
// GodClass, TooManyMethods: the bus is the one routing table; its matching and dispatch helpers
//   read and write the same subscriber state, so they stay beside it rather than in a second class.
public final class InMemoryEventBus implements EventBus {

    /** Unique bus ID embedded in each {@link SubscriptionToken}. */
    private static final AtomicInteger BUS_ID_SEQ = new AtomicInteger(0);

    /** Minimum subscriber count that requires explicit retain before dispatch. */
    private static final int MULTI_SUBSCRIBER_THRESHOLD = 1;

    private final int           busId;
    private final EventRegistry registry;

    /** Resolves a subscriber's bound isolation key; {@code null} when this bus does not scope subscribers. */
    private final IsolationKeyRegistry isolationRegistry;

    /** Subscriber table: eventTypeOrdinal → ordered list of Slot. */
    private final Map<Integer, List<Slot>> subscribers =
            new ConcurrentHashMap<>();

    /** Monotonically-increasing sequence for subscription ordinals — O(1) token generation. */
    private final AtomicLong subscriptionSeq = new AtomicLong(0L);

    /**
     * Creates a bus that does not support scoped subscriptions: {@link #subscribeScoped} always refuses.
     *
     * @param registry the active event type registry — used to resolve ordinals on subscribe
     */
    public InMemoryEventBus(EventRegistry registry) {
        this.busId    = BUS_ID_SEQ.incrementAndGet();
        this.registry = Objects.requireNonNull(registry, "registry");
        this.isolationRegistry = null;
    }

    /**
     * Creates a bus that supports scoped subscriptions.
     *
     * @param registry          the active event type registry — used to resolve ordinals on subscribe
     * @param isolationRegistry the registry that resolves a subscriber's bound isolation key
     */
    public InMemoryEventBus(EventRegistry registry, IsolationKeyRegistry isolationRegistry) {
        this.busId             = BUS_ID_SEQ.incrementAndGet();
        this.registry          = Objects.requireNonNull(registry, "registry");
        this.isolationRegistry = Objects.requireNonNull(isolationRegistry, "isolationRegistry");
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
     *
     * <p>If the fan-out itself fails part-way, the refs no handler thread will ever own are released
     * before the failure is rethrown. Fire-and-forget applies to the handlers, not to setting them
     * up: a caller that never learns the dispatch failed also never learns its payload is stranded.
     */
    // java:S1181 — same exemption as publishAndAwait: the Throwable is rethrown unchanged and the
    // catch exists only to release refs no handler will reach. Narrowing it restores the leak for
    // exactly the types that reach this path.
    @Override
    @SuppressWarnings("java:S1181")
    public void publish(EventDescriptor descriptor, EventPayload payload) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload,    "payload");

        List<Slot> slots = resolveSlots(descriptor.eventTypeOrdinal());
        int isolationOrdinal = descriptor.isolationOrdinal();
        int match = countMatches(slots, isolationOrdinal);
        emitJfr(descriptor.eventTypeOrdinal(), match, false);

        if (match == 0) {
            payload.close();
            return;
        }

        // unowned = refs currently held that no handler will ever close. It starts at one (the
        // caller's), rises with each retain(), and falls as each successful start() transfers
        // ownership to that thread; whatever it holds when something throws is exactly what the bus
        // must release. Both throwing sites are real: retain() is specified to throw once the
        // payload is fully released, and start() throws OutOfMemoryError when a thread cannot be
        // allocated — precisely the moment an off-heap leak matters most.
        int unowned   = 1;
        try {
            if (match > MULTI_SUBSCRIBER_THRESHOLD) {
                for (int i = 1; i < match; i++) {
                    payload.retain();
                    unowned++;
                }
            }
            for (Slot slot : slots) {
                if (!slot.matches(isolationOrdinal)) {
                    continue;
                }
                EventPayload slotPayload = payload;
                Thread.ofVirtual().start(() -> slot.handler().handle(descriptor, slotPayload));
                unowned--;
            }
        } catch (Throwable escaped) { //NOPMD AvoidCatchingThrowable — release before rethrow
            // Each close() is guarded on its own: a throw here would replace the caller's original
            // failure with a secondary one AND abandon the refs still outstanding, so the loop has
            // to finish whatever any single release does.
            for (int i = 0; i < unowned; i++) {
                try {
                    payload.close();
                } catch (Throwable _) { //NOPMD AvoidCatchingThrowable — best-effort release
                    // Deliberately empty: one failed release must not strand the refs still owed.
                }
            }
            throw escaped;
        }
    }

    // =========================================================================
    // EventBus — publishAndAwait (blocks calling thread until all handlers done)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>The calling thread opens the scope and is the owner — it forks all handlers,
     * joins (blocks), and closes. ScopedValue bindings are inherited by all handler VTs.
     */
    // java:S1181 — the same exemption the five other release-before-rethrow sites carry (see
    // NativeCipherContext, SecurityInterceptor, PaqsScheduler). The Throwable is not handled here:
    // it is rethrown unchanged, and the catch exists only so the wrappers no handler will ever
    // reach are released first. Narrowing it would restore the leak for exactly the types that
    // reach this path — an Error out of a handler is the case it exists for.
    @Override
    @SuppressWarnings("java:S1181")
    public void publishAndAwait(EventDescriptor descriptor, EventPayload payload)
            throws InterruptedException {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload,    "payload");

        List<Slot> slots = resolveSlots(descriptor.eventTypeOrdinal());
        int isolationOrdinal = descriptor.isolationOrdinal();
        int match = countMatches(slots, isolationOrdinal);
        emitJfr(descriptor.eventTypeOrdinal(), match, true);

        if (match == 0) {
            payload.close();
            return;
        }

        // The matching subset, or the snapshot itself when every slot matches, so the common path
        // builds no list of its own.
        List<Slot> targets = match == slots.size() ? slots : selectMatching(slots, isolationOrdinal, match);

        // Build one TrackingWrapper per matching slot so deterministic cleanup is possible on any
        // early-exit path (interrupt, RuntimeException) — every retain() is balanced.
        List<TrackingWrapper> wrappers = buildWrappers(payload, match);

        // Indexed by slot rather than appended as forks finish, so the failures attached to the
        // caller's exception are in subscription order whatever order the handlers completed in.
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(match);
        // JDK 28 changed this shape twice: StructuredTaskScope gained a third type parameter
        // (the exception join() throws), and Joiner.awaitAll() was REMOVED. allUntil with a
        // never-true predicate is the same policy — every subtask runs to completion,
        // failures included — which is what this dispatch has always required.
        try {
            try (StructuredTaskScope<Void, List<StructuredTaskScope.Subtask<Void>>, RuntimeException> scope =
                         StructuredTaskScope.open(
                                 StructuredTaskScope.Joiner.<Void>allUntil(_ -> false))) {
                forkHandlers(scope, targets, wrappers, descriptor, failures);
                List<StructuredTaskScope.Subtask<Void>> tasks;
                try {
                    tasks = scope.join();
                } catch (InterruptedException interruptEx) {
                    closeUnclosed(wrappers);
                    // The handlers that already ran and threw are not un-run by the interrupt.
                    // throwIfFailed is only reachable on the normal exit, so without this the caller is
                    // told delivery was cut short and never told that part of what was delivered also
                    // failed — the more actionable of the two facts, and the one it cannot recover by
                    // retrying.
                    forEachFailure(failures, interruptEx::addSuppressed);
                    Thread.currentThread().interrupt();
                    throw interruptEx;
                }
                // allUntil is await-all: join() returns normally whether a subtask finished or died, and
                // the caller inspects state(). The fork body collects a handler's RuntimeException
                // itself, so the only thing that reaches this loop is what it does NOT catch — an Error
                // out of a handler killed its subtask silently, leaving `failures` empty and
                // publishAndAwait returning as though delivery had succeeded.
                // join() lists the subtasks in fork order, which is slot order.
                for (int i = 0; i < tasks.size(); i++) {
                    StructuredTaskScope.Subtask<Void> task = tasks.get(i);
                    if (task.state() == StructuredTaskScope.Subtask.State.FAILED) {
                        failures.compareAndSet(i, null, task.exception());
                    }
                }
            }
        } catch (Throwable escaped) { //NOPMD AvoidCatchingThrowable — release before rethrow, SPI boundary
            // Anything that leaves the scope — an Error from a fork, or a failure closing one
            // wrapper — arrives here after the scope's close has waited for every subtask, so no
            // handler still holds a wrapper. The ones no handler reached are released before the
            // rethrow; otherwise the payload's off-heap refcount leaks permanently.
            closeUnclosed(wrappers);
            throw escaped;
        }
        throwIfFailed(descriptor.eventTypeOrdinal(), failures);
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
            throw EventBusException.subscriptionRejected(eventType);
        }

        long seq  = subscriptionSeq.incrementAndGet();
        Slot slot = new Slot(seq, handler, 0);
        subscribers.computeIfAbsent(ordinal, _ -> new CopyOnWriteArrayList<>()).add(slot);
        return new SubscriptionToken(busId, seq);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The subscriber's scope is the isolation key bound on the calling thread, captured here. A
     * subscription with no bound context, an empty key, or a key the registry does not hold is
     * refused, as is any call on a bus built without an isolation registry.
     */
    @Override
    public SubscriptionToken subscribeScoped(String eventType, EventHandler handler) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler,   "handler");

        int ordinal = registry.ordinalOf(eventType);
        if (ordinal < 0) {
            throw EventBusException.subscriptionRejected(eventType);
        }

        int isolationOrdinal = boundIsolationOrdinal(eventType);
        long seq  = subscriptionSeq.incrementAndGet();
        Slot slot = new Slot(seq, handler, isolationOrdinal);
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

    /**
     * Resolves the ordinal of the isolation key bound on the calling thread, refusing when there is
     * no scope to subscribe under.
     */
    private int boundIsolationOrdinal(String eventType) {
        if (isolationRegistry == null || !KernelProviders.STORAGE_CONTEXT.isBound()) {
            throw EventBusException.subscriptionRejected(eventType);
        }
        Optional<String> key = KernelProviders.STORAGE_CONTEXT.get().isolationKey();
        if (key.isEmpty()) {
            throw EventBusException.subscriptionRejected(eventType);
        }
        int ordinal = isolationRegistry.ordinalOf(key.get());
        if (ordinal < 0) {
            throw EventBusException.subscriptionRejected(eventType);
        }
        return ordinal;
    }

    // Indexed on purpose: the publish path walks the snapshot by position, and an iterator is an
    // allocation this path is measured against.
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    private static int countMatches(List<Slot> slots, int isolationOrdinal) {
        int matched = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).matches(isolationOrdinal)) {
                matched++;
            }
        }
        return matched;
    }

    @SuppressWarnings("PMD.ForLoopCanBeForeach") // indexed, for the same reason as countMatches
    private static List<Slot> selectMatching(List<Slot> slots, int isolationOrdinal, int match) {
        List<Slot> selected = new ArrayList<>(match);
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.matches(isolationOrdinal)) {
                selected.add(slot);
            }
        }
        return selected;
    }

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

    private static void forkHandlers(
            StructuredTaskScope<Void, List<StructuredTaskScope.Subtask<Void>>, RuntimeException> scope,
                                     List<Slot> slots,
                                     List<TrackingWrapper> wrappers,
                                     EventDescriptor descriptor,
                                     AtomicReferenceArray<Throwable> failures) {
        int slotCount = slots.size();
        for (int i = 0; i < slotCount; i++) {
            Slot slot = slots.get(i);
            TrackingWrapper wrapper = wrappers.get(i);
            int index = i;
            scope.fork(() -> {
                try (TrackingWrapper twrClose = wrapper) {
                    slot.handler().handle(descriptor, twrClose);
                } catch (RuntimeException handlerEx) { //NOPMD AvoidCatchingGenericException — SPI boundary
                    failures.set(index, handlerEx);
                }
                return null;
            });
        }
    }

    private static void closeUnclosed(List<TrackingWrapper> wrappers) {
        for (TrackingWrapper w : wrappers) {
            if (!w.isClosed()) {
                w.close();
            }
        }
    }

    private static void throwIfFailed(int eventTypeOrdinal, AtomicReferenceArray<Throwable> failures) {
        int failed = 0;
        for (int i = 0; i < failures.length(); i++) {
            if (failures.get(i) != null) {
                failed++;
            }
        }
        if (failed == 0) {
            return;
        }
        EventBusException busException = EventBusException.handlersFailed(eventTypeOrdinal, failed);
        forEachFailure(failures, busException::addSuppressed);
        throw busException;
    }

    private static void forEachFailure(AtomicReferenceArray<Throwable> failures,
                                       Consumer<Throwable> action) {
        for (int i = 0; i < failures.length(); i++) {
            Throwable failure = failures.get(i);
            if (failure != null) {
                action.accept(failure);
            }
        }
    }

    private List<Slot> resolveSlots(int ordinal) {
        List<Slot> slots = subscribers.get(ordinal);
        // A snapshot, not the live list. CopyOnWriteArrayList makes a single ITERATION safe, and
        // that was the whole argument for handing the live list out — but neither publish path
        // iterates only once. Both read size() to decide how many refs to hold, then walk the list
        // separately, and a subscribe or unsubscribe landing between those two reads makes the
        // count describe a different list than the one dispatched to.
        //
        // publish(): three handler threads against two retained refs means the third close() is an
        // over-release — a SIGSEGV in the allocator, arriving where the memory policy exists to
        // prevent it. publishAndAwait(): more slots than wrappers is an IndexOutOfBoundsException
        // thrown at the publisher; fewer is a wrapper nobody closes and a refcount that never
        // reaches zero.
        //
        // One copy of a list that is typically empty or a single element, and the empty case
        // returns the shared List.of() without copying anything.
        return slots == null ? List.of() : List.copyOf(slots);
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
     * Internal subscription slot — pairs ordinalSeq with the handler and the subscriber's isolation
     * ordinal ({@code 0} for an unscoped subscriber).
     * Valhalla-ready: primitive {@code long} key, no identity operations.
     */
    private value record Slot(long ordinalSeq, EventHandler handler, int isolationOrdinal) {

        /**
         * An unscoped slot receives every publication; a scoped slot receives only publications
         * carrying its own ordinal.
         */
        /* default */ boolean matches(int publicationIsolationOrdinal) {
            return isolationOrdinal == 0 || isolationOrdinal == publicationIsolationOrdinal;
        }
    }

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
