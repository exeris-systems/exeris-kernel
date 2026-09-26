/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * TCK: Abstract base for {@link EventBus} contract verification.
 *
 * <h2>Front 2 — The L3 Event Bus</h2>
 * <p>The golden test of this suite is the <b>RAII payload close audit</b> and the
 * <b>ScopedValue propagation</b> verification. These two tests together ensure that
 * no slab memory is leaked and that cross-cutting context (tenant, trace ID) propagates
 * correctly to all event handlers without ThreadLocal.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>{@code publish()} dispatches purely by the descriptor's {@code eventTypeOrdinal} — the
 *       descriptors this suite publishes carry no event-type name, and the handler registered
 *       against that ordinal's type still receives the event. Algorithmic complexity (O(1), no
 *       {@code String} comparison) is an SPI contract claim this suite does not measure.</li>
 *   <li>{@code subscribe()} returns a valid, non-{@code INVALID} token, and {@code unsubscribe()}
 *       does not throw for either a live token or {@link SubscriptionToken#INVALID}.</li>
 *   <li>publish() with N=0 handlers calls payload.close() immediately (no leak).</li>
 *   <li>publish() with N=1 handler: handler receives exactly 1 payload, closes it.</li>
 *   <li>publish() with N=3 handlers: retain() called (N-1)=2 times, total refCount=3,
 *       every handler closes — refCount reaches 0.</li>
 *   <li><b>Golden Test:</b> on the blocking dispatch path ({@code publishAndAwait}), a handler
 *       reads a caller-defined {@code ScopedValue} unknown to the kernel that was bound in the
 *       publish scope — proof that inheritance is structural (JEP 506), not special-cased for
 *       kernel-known bindings.</li>
 *   <li>Broadcast fan-out: with N=3 handlers, the payload's total close-call count reaches 3
 *       only once every handler has closed its own reference; this suite does not simulate a
 *       handler that omits {@code close()} to verify that a leak is separately flagged.</li>
 *   <li>publishAndAwait() blocks until all handlers complete.</li>
 *   <li>publishAndAwait() with handlers that throw: every subscribed handler still runs, and the
 *       caller receives one {@link EventBusException} carrying
 *       {@value KernelErrorCodes#EX_EVENT_6010}, {@code rawArgs [eventTypeOrdinal,
 *       failedHandlerCount]}, no cause, and each handler's exception as a suppressed exception;
 *       every payload reference is still released. The suppressed exceptions are compared as a
 *       set, since the bus promises no delivery order.</li>
 *   <li>A type the registry does not know. {@code subscribe()} to it either returns a valid
 *       token or refuses with {@value KernelErrorCodes#EX_EVENT_6011} and {@code rawArgs
 *       [eventType]}. {@code publish()} and {@code publishAndAwait()} of its ordinal either accept
 *       the event or refuse it with {@value KernelErrorCodes#EX_EVENT_6009} and {@code rawArgs
 *       [eventTypeOrdinal, String reason]}, and release the payload exactly once either way. The
 *       SPI promises the shape of a refusal, not that there is one: {@code EventBus} documents
 *       rejecting an unregistered subscription as the in-memory binding's behaviour, and names no
 *       {@code reason} value a binding must use.</li>
 * </ol>
 *
 * <h2>No-Ordering by Design (ADR-049)</h2>
 * <p>The {@link EventBus} is the <b>transient</b> pub/sub path and makes <b>no</b> per-key /
 * per-aggregate ordering promise — {@code publish()} fans out concurrently (one virtual thread per
 * handler). Per ADR-049, per-stream total ordering is a property of the durable-log surface
 * ({@code EventStreamAppender} / {@code EventStreamReader}), <b>not</b> the bus. This suite
 * therefore deliberately asserts no delivery order; callers needing ordering use the durable log.
 *
 * <h2>Topic-Blind by Design (ADR-050)</h2>
 * <p>An event type's binding-agnostic {@code topic} ({@link EventTypeSpec#topic()}) is
 * <b>advisory</b> for the in-memory {@link EventBus}: routing is by {@code eventTypeOrdinal}
 * only, so the bus does not consult {@code topic} and delivery is unaffected by whether a type
 * carries one. Broker bindings (e.g. Kafka) map {@code topic} to a concrete broker topic; the
 * in-memory bus does not. This suite therefore makes no topic-based routing assertion — that the
 * value round-trips through the registry is covered by {@code AbstractEventRegistryTck}.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * class CommunityEventBusTest extends AbstractEventBusTck {
 *     @Override protected EventEngine createEngine() {
 *         return new CommunityEventProvider().createEngine(EventEngineConfig.defaults());
 *     }
 * }
 * }
 *
 * @since 0.5
 */
public abstract class AbstractEventBusTck {

    // =========================================================================
    // Template method
    // =========================================================================

    /**
     * Creates a fully configured, but not yet started, {@link EventEngine}.
     *
     * @return a fresh {@link EventEngine}
     */
    protected abstract EventEngine createEngine();

    private static final int  ORDINAL_USER_CREATED  = 100;
    private static final int  ORDINAL_ORDER_PLACED  = 101;
    private static final String TYPE_USER_CREATED   = "UserCreated";
    private static final String TYPE_ORDER_PLACED   = "OrderPlaced";
    private static final int    ORDINAL_NEVER_REGISTERED = 199;
    private static final String TYPE_NEVER_REGISTERED    = "TckNeverRegistered";

    private EventEngine engine;

    /**
     * Creates the contract; subclasses supply the {@link EventEngine} under test via
     * {@link #createEngine()}.
     */
    public AbstractEventBusTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUp() {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.of(TYPE_USER_CREATED, ORDINAL_USER_CREATED));
        engine.registry().register(EventTypeSpec.of(TYPE_ORDER_PLACED, ORDINAL_ORDER_PLACED));
        engine.start();
    }

    @AfterEach
    final void tearDown() {
        engine.close();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private EventDescriptor descriptor(int ordinal) {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ordinal, 0x02 /* ASYNC */, System.currentTimeMillis());
    }

    // =========================================================================
    // Subscription lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Subscription lifecycle")
    class SubscriptionLifecycle {

        @Test
        @DisplayName("subscribe() returns a valid, non-INVALID token")
        void subscribeReturnsValidToken() {
            SubscriptionToken token = engine.bus().subscribe(TYPE_USER_CREATED, (d, p) -> {
                try (p) { /* no-op */ }  // NOPMD EmptyControlStatement - closing the payload IS the contract
            });

            assertThat(token)
                    .as("subscribe() MUST return a non-null token").isNotNull();
            assertThat(token.isValid())
                    .as("subscribe() MUST return a VALID token (not the INVALID sentinel)")
                    .isTrue();
        }

        @Test
        @DisplayName("unsubscribe() with a valid token does not throw")
        void unsubscribeDoesNotThrow() {
            SubscriptionToken token = engine.bus().subscribe(TYPE_USER_CREATED, (d, p) -> {
                try (p) { /* no-op */ }  // NOPMD EmptyControlStatement - closing the payload IS the contract
            });
            assertThatCode(() -> engine.bus().unsubscribe(token))
                    .as("unsubscribe() with valid token MUST not throw")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("unsubscribe() with INVALID token is a safe no-op")
        void unsubscribeWithInvalidTokenIsNoOp() {
            assertThatCode(() -> engine.bus().unsubscribe(SubscriptionToken.INVALID))
                    .as("unsubscribe(INVALID) MUST be a safe no-op per O(1) contract")
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // RAII payload protocol
    // =========================================================================

    @Nested
    @DisplayName("RAII payload close protocol — zero-leak verification")
    class RaiiPayloadProtocol {

        @Test
        @DisplayName("N=0 handlers: payload.close() called immediately after publish()")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void zeroHandlersPayloadClosedImmediately() throws Exception {
            // No subscribers for ORDER_PLACED — bus must close the payload itself
            AtomicInteger closeCalls = new AtomicInteger(0);
            CountDownLatch closeLatch = new CountDownLatch(1);
            EventPayload trackingPayload = new TrackingPayload(closeCalls, closeLatch);

            engine.bus().publish(descriptor(ORDINAL_ORDER_PLACED), trackingPayload);

            // Wait deterministically for the bus to call close() — no Thread.sleep()
            assertThat(closeLatch.await(5, TimeUnit.SECONDS))
                    .as("Timed out waiting for payload.close() with N=0 handlers. " +
                        "EventBus MUST call payload.close() immediately. " +
                        "Omitting this causes a slab memory leak in Enterprise tier.")
                    .isTrue();
            assertThat(closeCalls.get())
                    .as("With N=0 handlers, EventBus MUST call payload.close() exactly once.")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("N=1 handler: handler receives payload, close() reaches refCount=0")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void singleHandlerPayloadClosed() throws Exception {
            AtomicInteger closeCalls = new AtomicInteger(0);
            CountDownLatch handled = new CountDownLatch(1);

            engine.bus().subscribe(TYPE_USER_CREATED, (d, payload) -> {
                try (payload) {  // NOPMD EmptyControlStatement - closing the payload IS the contract
                    // handler work is intentionally empty
                } finally {
                    handled.countDown();
                }
            });

            engine.bus().publish(descriptor(ORDINAL_USER_CREATED), new TrackingPayload(closeCalls));
            assertThat(handled.await(5, TimeUnit.SECONDS))
                    .as("Handler must be invoked within 5 seconds").isTrue();
            assertThat(closeCalls.get())
                    .as("With N=1 handler, exactly 1 close() call expected (refCount → 0)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("N=3 handlers broadcast: retain() called (N-1)=2 times, all handlers close()")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void broadcastThreeHandlersAllClose() throws Exception {
            AtomicInteger closeCalls = new AtomicInteger(0);
            CountDownLatch allHandled = new CountDownLatch(3);
            CountDownLatch allClosed = new CountDownLatch(3);

            for (int i = 0; i < 3; i++) {
                engine.bus().subscribe(TYPE_USER_CREATED, (d, payload) -> {
                    try (payload) {  // NOPMD EmptyControlStatement - closing the payload IS the contract
                        // handler work is intentionally empty
                    } finally {
                        allHandled.countDown();
                    }
                });
            }

            TrackingPayload payload = new TrackingPayload(closeCalls, allClosed);
            engine.bus().publish(descriptor(ORDINAL_USER_CREATED), payload);

            assertThat(allHandled.await(5, TimeUnit.SECONDS))
                    .as("All 3 handlers must be invoked within 5 seconds").isTrue();
            assertThat(allClosed.await(5, TimeUnit.SECONDS))
                    .as("All 3 handler-owned payload references must be closed within 5 seconds")
                    .isTrue();

            assertThat(payload.retainCalls())
                    .as("Bus MUST call retain() exactly (N-1)=2 times before forking handlers")
                    .isEqualTo(2);
            assertThat(closeCalls.get())
                    .as("All 3 handlers must call close() — total close calls = 3")
                    .isEqualTo(3);
        }
    }

    // =========================================================================
    // ScopedValue propagation — the golden test
    // =========================================================================

    @Nested
    @DisplayName("ScopedValue propagation — KernelProviders context in handlers")
    class ScopedValuePropagation {

        @Test
        @DisplayName("GOLDEN: Handler reads ScopedValue bound in structured publish scope (JEP 506)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void handlerInheritsPublishScopeScopedValue() throws Exception {
            // A custom ScopedValue, created here and unknown to the kernel — that is the point of
            // the case, not an incidental detail. A binding can only be carried onto another thread
            // by naming it, so an implementation that dispatches handlers elsewhere cannot deliver
            // this one. The in-memory binding satisfies it by running handlers on the publisher's
            // thread (ADR-066); a StructuredTaskScope-based one satisfied it by inheritance.
            // ThreadLocal remains banned either way.
            ScopedValue<String> traceId = ScopedValue.newInstance();
            String expectedTrace = "trace-" + UUID.randomUUID();

            AtomicReference<String> capturedTrace = new AtomicReference<>(null);
            CountDownLatch handled = new CountDownLatch(1);

            engine.bus().subscribe(TYPE_USER_CREATED, (d, payload) -> {
                try (payload) {
                    // Reads the publisher's binding, however the implementation arranges it.
                    capturedTrace.set(traceId.orElse("NOT_PROPAGATED"));
                    handled.countDown();
                }
            });

                ScopedValue.where(traceId, expectedTrace).run(() -> {
                    try {
                        engine.bus().publishAndAwait(descriptor(ORDINAL_USER_CREATED), EventPayload.empty());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });

            assertThat(handled.await(5, TimeUnit.SECONDS))
                    .as("Handler must be invoked within 5 seconds").isTrue();

            // NOTE: This assertion targets the blocking dispatch path (publishAndAwait). An
            // implementation that runs handlers on the publisher's thread, or forks them somewhere
            // that inherits bindings, passes. One that hands them to a background pool
            // (ThreadLocal, ExecutorService) fails — the trace reads "NOT_PROPAGATED".
            assertThat(capturedTrace.get())
                    .as("Handler MUST receive the ScopedValue bound in the publish scope. " +
                        "Failure = the implementation uses a banned background ThreadPool " +
                        // CHECKSTYLE:OFF: this string NAMES the banned type as the thing the contract forbids;
                        //                 the L0 regex cannot tell a message about a ban from a use of it.
                        "or ThreadLocal instead of ScopedValue-inheriting virtual threads. " +
                        // CHECKSTYLE:ON
                        "Expected: '%s', got: '%s'", expectedTrace, capturedTrace.get())
                    .isEqualTo(expectedTrace);
        }
    }

    // =========================================================================
    // publishAndAwait — blocking contract
    // =========================================================================

    @Test
    @DisplayName("publishAndAwait() blocks until all handlers have completed")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void publishAndAwaitBlocksUntilAllHandlersComplete() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();

        engine.bus().subscribe(TYPE_ORDER_PLACED, (d, payload) -> {
            try (payload) {
                // Simulate handler work without Thread.sleep() — park for 50 ms
                java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                }
                log.add("handler-done");
            }
        });

        engine.bus().publishAndAwait(descriptor(ORDINAL_ORDER_PLACED), EventPayload.empty());

        // publishAndAwait() must NOT return before all handlers complete
        assertThat(log)
                .as("publishAndAwait() MUST block until all handlers have completed. " +
                    "If 'handler-done' is missing, the implementation returned too early.")
                .containsExactly("handler-done");
    }

    @Test
    @DisplayName("publishAndAwait() with failing handlers runs every handler, then throws EX-EVENT-6010")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void publishAndAwaitReportsEveryHandlerFailureAfterAllHandlersRan() {
        EventBus bus = engine.bus();
        Set<String> ran = ConcurrentHashMap.newKeySet();
        RuntimeException firstFailure  = new IllegalStateException("TCK handler failure A");
        RuntimeException secondFailure = new IllegalStateException("TCK handler failure B");

        // Two failing handlers and one that succeeds: a count of 2 is not what a bus that stops
        // at the first failure reports, and the succeeding handler is the one such a bus skips
        // whichever order it dispatches in.
        bus.subscribe(TYPE_USER_CREATED, failingHandler("failing-a", ran, firstFailure));
        bus.subscribe(TYPE_USER_CREATED, (d, payload) -> {
            try (payload) {
                ran.add("succeeding");
            }
        });
        bus.subscribe(TYPE_USER_CREATED, failingHandler("failing-b", ran, secondFailure));

        EventDescriptor published = descriptor(ORDINAL_USER_CREATED);
        AtomicInteger closeCalls = new AtomicInteger(0);
        TrackingPayload payload = new TrackingPayload(closeCalls);

        assertThatThrownBy(() -> bus.publishAndAwait(published, payload))
                .as("publishAndAwait() MUST report handler failures as one EventBusException, "
                    + "not rethrow a handler's own exception")
                .isInstanceOfSatisfying(EventBusException.class, ex -> {
                    assertThat(ex.errorCode())
                            .as("handler failures after delivery MUST carry EX-EVENT-6010")
                            .isEqualTo(KernelErrorCodes.EX_EVENT_6010);
                    assertThat(ex.rawArgs())
                            .as("EX-EVENT-6010 rawArgs MUST be [eventTypeOrdinal, failedHandlerCount]")
                            .containsExactly(ORDINAL_USER_CREATED, 2);
                    assertThat(ex.getCause())
                            .as("EX-EVENT-6010 carries no cause; the failures are suppressed")
                            .isNull();
                    assertThat(ex.getSuppressed())
                            .as("each handler's exception MUST be attached as suppressed")
                            .containsExactlyInAnyOrder(firstFailure, secondFailure);
                });

        assertThat(ran)
                .as("a failing handler MUST NOT stop the handlers subscribed alongside it")
                .containsExactlyInAnyOrder("failing-a", "succeeding", "failing-b");
        assertThat(closeCalls.get())
                .as("every payload reference MUST be released on the failure path — 3 handlers, "
                    + "3 close() calls")
                .isEqualTo(3);
    }

    private static EventHandler failingHandler(String name, Set<String> ran, RuntimeException failure) {
        return (d, payload) -> {
            try (payload) {
                ran.add(name);
                throw failure;
            }
        };
    }

    // =========================================================================
    // Unregistered event types — the shape of a refusal
    // =========================================================================

    @Nested
    @DisplayName("Unregistered event types — a refusal carries its documented code and rawArgs")
    class UnregisteredEventTypes {

        @Test
        @DisplayName("subscribe() to an unregistered type returns a valid token or throws EX-EVENT-6011 [eventType]")
        void subscribeToUnregisteredTypeIsAcceptedOrRejectedWith6011() {
            EventBus bus = engine.bus();
            AtomicReference<SubscriptionToken> accepted = new AtomicReference<>(null);

            Throwable thrown = catchThrowable(() -> accepted.set(bus.subscribe(TYPE_NEVER_REGISTERED,
                    (d, payload) -> {
                        try (payload) { /* no-op */ }  // NOPMD EmptyControlStatement - closing the payload IS the contract
                    })));

            if (thrown == null) {
                // The SPI does not require a refusal here; a binding that accepts must still
                // hand back a token that revokes the subscription.
                assertThat(accepted.get())
                        .as("an accepted subscribe() MUST return a non-null token").isNotNull();
                assertThat(accepted.get().isValid())
                        .as("an accepted subscribe() MUST return a VALID token").isTrue();
                bus.unsubscribe(accepted.get());
            } else {
                assertThat(thrown)
                        .as("a rejected subscribe() MUST throw EventBusException")
                        .isInstanceOfSatisfying(EventBusException.class, ex -> {
                            assertThat(ex.errorCode())
                                    .as("a rejected subscription MUST carry EX-EVENT-6011")
                                    .isEqualTo(KernelErrorCodes.EX_EVENT_6011);
                            assertThat(ex.rawArgs())
                                    .as("EX-EVENT-6011 rawArgs MUST be [eventType]")
                                    .containsExactly(TYPE_NEVER_REGISTERED);
                        });
            }
        }

        @Test
        @DisplayName("publish() of an unregistered ordinal is accepted or refused with EX-EVENT-6009; payload released once")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void publishOfUnregisteredOrdinalIsAcceptedOrRefusedWith6009() throws Exception {
            EventBus bus = engine.bus();
            EventDescriptor published = descriptor(ORDINAL_NEVER_REGISTERED);
            AtomicInteger closeCalls = new AtomicInteger(0);
            CountDownLatch closed = new CountDownLatch(1);
            TrackingPayload payload = new TrackingPayload(closeCalls, closed);

            Throwable thrown = catchThrowable(() -> bus.publish(published, payload));

            assertAcceptedOrRefusedWith6009(thrown, closeCalls, closed);
        }

        @Test
        @DisplayName("publishAndAwait() of an unregistered ordinal is accepted or refused with EX-EVENT-6009; payload released once")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void publishAndAwaitOfUnregisteredOrdinalIsAcceptedOrRefusedWith6009() throws Exception {
            EventBus bus = engine.bus();
            EventDescriptor published = descriptor(ORDINAL_NEVER_REGISTERED);
            AtomicInteger closeCalls = new AtomicInteger(0);
            CountDownLatch closed = new CountDownLatch(1);
            TrackingPayload payload = new TrackingPayload(closeCalls, closed);

            Throwable thrown = catchThrowable(() -> bus.publishAndAwait(published, payload));

            assertAcceptedOrRefusedWith6009(thrown, closeCalls, closed);
        }

        private static void assertAcceptedOrRefusedWith6009(Throwable thrown,
                                                            AtomicInteger closeCalls,
                                                            CountDownLatch closed)
                throws InterruptedException {
            if (thrown != null) {
                assertThat(thrown)
                        .as("a refused publish MUST throw EventBusException, not a raw exception")
                        .isInstanceOfSatisfying(EventBusException.class, ex -> {
                            assertThat(ex.errorCode())
                                    .as("a publish refused for a reason other than a full queue "
                                        + "MUST carry EX-EVENT-6009")
                                    .isEqualTo(KernelErrorCodes.EX_EVENT_6009);
                            assertThat(ex.rawArgs())
                                    .as("EX-EVENT-6009 rawArgs MUST be [eventTypeOrdinal, String reason]")
                                    .hasSize(2);
                            assertThat(ex.rawArgs()[0])
                                    .as("EX-EVENT-6009 rawArgs[0] MUST be the refused ordinal")
                                    .isEqualTo(ORDINAL_NEVER_REGISTERED);
                            assertThat(ex.rawArgs()[1])
                                    .as("EX-EVENT-6009 rawArgs[1] MUST be the String reason")
                                    .isInstanceOf(String.class);
                        });
            }
            // Ownership passed to the bus on entry, on success or on failure — so the bus
            // releases the caller's reference whichever way it answered.
            assertThat(closed.await(5, TimeUnit.SECONDS))
                    .as("the bus MUST release a payload it was handed, accepted or refused")
                    .isTrue();
            assertThat(closeCalls.get())
                    .as("the payload MUST be released exactly once").isEqualTo(1);
        }
    }

    // =========================================================================
    // Inner helper — TrackingPayload
    // =========================================================================

    /**
     * A minimal {@link EventPayload} implementation that tracks retain() and close() calls
     * for RAII protocol verification. Community (heap) tier suitable only.
     */
    private static final class TrackingPayload implements EventPayload {

        private final AtomicInteger closeCalls;
        private final AtomicInteger retainCalls = new AtomicInteger(0);
        private final CountDownLatch closeLatch;
        private volatile boolean alive = true;

        TrackingPayload(AtomicInteger closeCalls) {
            this(closeCalls, null);
        }

        TrackingPayload(AtomicInteger closeCalls, CountDownLatch closeLatch) {
            this.closeCalls = closeCalls;
            this.closeLatch = closeLatch;
        }

        int retainCalls() { return retainCalls.get(); }

        @Override
        public java.lang.foreign.MemorySegment segment() {
            return java.lang.foreign.MemorySegment.NULL;
        }

        @Override public int    length()    { return 0; }
        @Override public int    refCount()  { return alive ? 1 : 0; }
        @Override public boolean isAlive()  { return alive; }

        @Override
        public void retain() { retainCalls.incrementAndGet(); }

        @Override
        public void close() {
            alive = false;
            closeCalls.incrementAndGet();
            if (closeLatch != null) {
                closeLatch.countDown();
            }
        }
    }
}


