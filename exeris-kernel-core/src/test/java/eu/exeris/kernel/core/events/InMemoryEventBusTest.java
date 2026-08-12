/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import eu.exeris.kernel.core.events.projection.Projection;
import eu.exeris.kernel.core.events.projection.ProjectionEngine;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit: {@link InMemoryEventBus} + {@link ProjectionEngine} — RAII, routing, projections.
 *
 * @since 0.5.0
 */
@DisplayName("InMemoryEventBus — routing, RAII, ScopedValue, projections")
class InMemoryEventBusTest {

    private static final String TYPE_ORDER = "OrderPlaced";
    private static final int    ORD_ORDER  = 200;

    /** Subscribers in the mid-fan-out failure case: enough that the failing retain is not the last. */
    private static final int SUBSCRIBER_COUNT = 4;
    /** 1-based ordinal of the retain that throws — the third of the three the bus issues for N=4. */
    private static final int FAILING_RETAIN = 3;

    private InMemoryEventBusTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryEventBusTestFixture();
        fixture.registry().register(EventTypeSpec.of(TYPE_ORDER, ORD_ORDER));
        fixture.start();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Publish to unregistered ordinal immediately closes payload (N=0)")
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void noHandlersClosesPayloadImmediately() throws InterruptedException {
        AtomicInteger closed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        EventPayload tracking = trackingPayload(closed, latch);

        // Ordinal 999 has no subscribers
        fixture.bus().publish(descriptor(999), tracking);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Subscribe to unregistered type throws EventBusException")
    void subscribeToUnregisteredTypeThrows() {
        assertThatThrownBy(() -> fixture.bus().subscribe("UNKNOWN_TYPE", (d, p) -> p.close()))
                .isInstanceOf(eu.exeris.kernel.spi.exceptions.events.EventBusException.class);
    }

    @Test
    @DisplayName("N=3 broadcast: retain called 2 times, all 3 handlers close payload")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void broadcastRaiiProtocol() throws InterruptedException {
        AtomicInteger retains = new AtomicInteger(0);
        AtomicInteger closes  = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(3);

        EventPayload tracking = new EventPayload() {
            @Override public java.lang.foreign.MemorySegment segment() { return java.lang.foreign.MemorySegment.NULL; }
            @Override public int length() { return 0; }
            @Override public int refCount() { return Integer.MAX_VALUE; }
            @Override public boolean isAlive() { return true; }
            @Override public void retain() { retains.incrementAndGet(); }
            @Override public void close() { closes.incrementAndGet(); allDone.countDown(); }
        };

        for (int i = 0; i < 3; i++) {
            fixture.bus().subscribe(TYPE_ORDER, (d, p) -> {
                try (p) { /* process */ }
            });
        }

        fixture.bus().publish(descriptor(ORD_ORDER), tracking);
        assertThat(allDone.await(4, TimeUnit.SECONDS)).isTrue();

        assertThat(retains.get()).isEqualTo(2);
        assertThat(closes.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("an Error escaping a handler still balances every retain — publishAndAwait")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void errorEscapingHandlerDoesNotLeakPayloadRefcount() {
        AtomicInteger retains = new AtomicInteger(0);
        AtomicInteger closes  = new AtomicInteger(0);

        // Counts retain/close rather than allocating off-heap under PARANOID leak detection. The
        // claim is about the REFCOUNT balance across N wrappers, and a fake payload states that
        // directly; a LeakDetectedError would report the same fact one indirection away, and only
        // for the subset of payloads backed by a LoanedBuffer.
        EventPayload tracking = new EventPayload() {
            @Override public java.lang.foreign.MemorySegment segment() { return java.lang.foreign.MemorySegment.NULL; }
            @Override public int length() { return 0; }
            @Override public int refCount() { return Integer.MAX_VALUE; }
            @Override public boolean isAlive() { return true; }
            @Override public void retain() { retains.incrementAndGet(); }
            @Override public void close() { closes.incrementAndGet(); }
        };

        // The FIRST of three handlers throws, so two wrappers are still unreached when the stack
        // unwinds. An Error, not a RuntimeException: the loop collects those per handler, and it
        // was the types it did NOT collect that escaped past it.
        fixture.bus().subscribe(TYPE_ORDER, (d, p) -> {
            throw new StackOverflowError("handler blew up");
        });
        fixture.bus().subscribe(TYPE_ORDER, (d, p) -> {
            try (p) { /* never reached */ }
        });
        fixture.bus().subscribe(TYPE_ORDER, (d, p) -> {
            try (p) { /* never reached */ }
        });

        // Both lines must let the Error reach the publisher; they differ in how it arrives, because
        // they differ in whether dispatch is sequential. On the distributed line the handlers run
        // in-thread, so the first one's Error unwinds the publisher's own stack and arrives
        // unwrapped. Here they fork, all three run, and the failures are aggregated — so it arrives
        // suppressed inside EventBusException. What must NOT differ is that it arrives at all: this
        // case reported "expecting code to raise a throwable" on this line until the subtask states
        // were inspected after join(), because an Error kills a subtask without the fork body's
        // catch (RuntimeException) ever seeing it.
        assertThatThrownBy(() -> fixture.bus().publishAndAwait(descriptor(ORD_ORDER), tracking))
                .as("the Error must still reach the publisher — releasing the wrappers is not the "
                    + "same as swallowing what caused the unwind")
                .isInstanceOf(EventBusException.class)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .hasAtLeastOneElementOfType(StackOverflowError.class));

        assertThat(closes.get())
                .as("every wrapper is closed by its own try-with-resources, including the one whose "
                    + "handler died — the refcount balance is the claim, and it holds on both lines "
                    + "even though the number of handlers actually reached does not")
                .isEqualTo(retains.get() + 1);
        assertThat(closes.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("a retain failing mid-fan-out releases what was already retained — publish")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void retainFailingMidFanOutDoesNotLeakPayloadRefcount() {
        AtomicInteger retains = new AtomicInteger(0);
        AtomicInteger closes  = new AtomicInteger(0);

        // retain() throwing is not a contrived fixture: EventPayload specifies IllegalStateException
        // once the payload is fully released, so a racing release reaches exactly this state. The
        // THIRD retain fails, which is the interesting index — refs are already outstanding and no
        // handler thread has been started to own any of them.
        EventPayload tracking = new EventPayload() {
            @Override public java.lang.foreign.MemorySegment segment() { return java.lang.foreign.MemorySegment.NULL; }
            @Override public int length() { return 0; }
            @Override public int refCount() { return Integer.MAX_VALUE; }
            @Override public boolean isAlive() { return true; }
            @Override public void retain() {
                if (retains.incrementAndGet() == FAILING_RETAIN) {
                    throw new IllegalStateException("payload already fully released");
                }
            }
            @Override public void close() { closes.incrementAndGet(); }
        };

        for (int i = 0; i < SUBSCRIBER_COUNT; i++) {
            fixture.bus().subscribe(TYPE_ORDER, (d, p) -> {
                try (p) { /* never reached — the fan-out fails before any thread starts */ }
            });
        }

        assertThatThrownBy(() -> fixture.bus().publish(descriptor(ORD_ORDER), tracking))
                .as("publish is fire-and-forget, but a failure BEFORE any handler thread exists "
                    + "still belongs to the caller — swallowing it would hide the release too")
                .isInstanceOf(IllegalStateException.class);

        assertThat(closes.get())
                .as("two retains succeeded before the third threw, so three refs were outstanding "
                    + "(the caller's plus those two) with no handler thread to own any of them. "
                    + "The fan-out used to leave all three, pinning the payload's segment for the "
                    + "life of the process")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("publishAndAwait blocks until handler completes")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void publishAndAwaitBlocks() throws InterruptedException {
        AtomicInteger seq = new AtomicInteger(0);

        fixture.bus().subscribe(TYPE_ORDER, (d, p) -> {
            try (p) {
                java.util.concurrent.locks.LockSupport.parkNanos(30_000_000L);
                seq.set(1);
            }
        });

        fixture.bus().publishAndAwait(descriptor(ORD_ORDER), EventPayload.empty());
        assertThat(seq.get()).isEqualTo(1);
    }

    // ── Projections ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Projection folds events into cumulative state")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void projectionFoldsState() throws InterruptedException {
        ProjectionEngine engine = new ProjectionEngine(fixture.bus(), fixture.registry());

        Projection<Integer> counter = engine.register(
                "order-count", TYPE_ORDER, 0,
                (state, d, p) -> state + 1);

        CountDownLatch latch = new CountDownLatch(3);
        fixture.bus().subscribe(TYPE_ORDER, (d, p) -> { try (p) { latch.countDown(); } });

        for (int i = 0; i < 3; i++) {
            fixture.bus().publish(descriptor(ORD_ORDER), EventPayload.empty());
        }

        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        settleWindow(50_000_000L); // 50 ms — let projection VTs complete (bounded against spurious wake-ups)

        assertThat(counter.state()).isEqualTo(3);
        engine.close();
    }

    @Test
    @DisplayName("Projection.close() unsubscribes — no further state updates")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void projectionCloseUnsubscribes() throws InterruptedException {
        ProjectionEngine engine = new ProjectionEngine(fixture.bus(), fixture.registry());
        CountDownLatch firstEvent = new CountDownLatch(1);

        Projection<Integer> counter = engine.register(
                "count2", TYPE_ORDER, 0,
                (state, d, p) -> { firstEvent.countDown(); return state + 1; });

        fixture.bus().publish(descriptor(ORD_ORDER), EventPayload.empty());
        assertThat(firstEvent.await(3, TimeUnit.SECONDS)).isTrue();
        settleWindow(30_000_000L); // 30 ms — settle window before unregister (bounded against spurious wake-ups)

        engine.unregister("count2");

        fixture.bus().publish(descriptor(ORD_ORDER), EventPayload.empty());
        settleWindow(100_000_000L); // 100 ms — confirm no further state updates after unregister

        assertThat(counter.state()).isEqualTo(1);
        engine.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Bounded settle window. A straight-line {@link LockSupport#parkNanos(long)} call may return
     * early on a spurious wake-up or interrupt and shorten the wait, which would cause the next
     * assertion to fire before background virtual threads complete. Spinning until a nanoTime
     * deadline guarantees the full duration regardless of how many times {@code parkNanos}
     * returns.
     */
    private static void settleWindow(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0L) {
            LockSupport.parkNanos(remaining);
        }
    }

    private static EventDescriptor descriptor(int ordinal) {
        return new EventDescriptor(1L, 2L, 3L, 4L, ordinal, EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis());
    }

    private static EventPayload trackingPayload(AtomicInteger closes, CountDownLatch latch) {
        return new EventPayload() {
            @Override public java.lang.foreign.MemorySegment segment() { return java.lang.foreign.MemorySegment.NULL; }
            @Override public int length() { return 0; }
            @Override public int refCount() { return 1; }
            @Override public boolean isAlive() { return true; }
            @Override public void retain() { /* test stub — ref-count not tracked in this fixture */ }
            @Override public void close() { closes.incrementAndGet(); latch.countDown(); }
        };
    }

    // ── Test fixture ─────────────────────────────────────────────────────────

    /**
     * Minimal in-test registry — avoids cross-module dependency on community from core tests.
     */
    private static final class InMemoryEventBusTestFixture {
        private final EventRegistry reg = new MinimalTestRegistry();
        private final InMemoryEventBus bus = new InMemoryEventBus(reg);

        void start() { /* test fixture — no lifecycle startup needed */ }
        void close() { /* test fixture — no resource cleanup needed */ }
        InMemoryEventBus bus() { return bus; }
        EventRegistry registry() { return reg; }
    }

    private static final class MinimalTestRegistry implements EventRegistry {
        private final java.util.concurrent.ConcurrentHashMap<String, EventTypeSpec> byName =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void register(EventTypeSpec spec) {
            byName.putIfAbsent(spec.name(), spec);
        }

        @Override
        public EventTypeSpec resolve(String eventType) {
            return byName.get(eventType);
        }

        @Override
        public java.util.Set<String> registeredTypes() {
            return java.util.Set.copyOf(byName.keySet());
        }

        @Override
        public int size() {
            return byName.size();
        }
    }
}


