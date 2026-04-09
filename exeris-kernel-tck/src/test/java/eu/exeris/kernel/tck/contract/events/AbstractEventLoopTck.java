/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link eu.exeris.kernel.spi.events.EventLoop} contract verification — SPI-only.
 *
 * <h2>Architecture note</h2>
 * <p>Tests go through {@link EventEngine} SPI exclusively. The EventLoop is exercised as
 * a composited component of the engine: publishers push via {@link eu.exeris.kernel.spi.events.EventBus},
 * persistent events are enqueued, and the loop drains them to registered
 * {@link eu.exeris.kernel.spi.events.EventBatchProcessor}s. Non-persistent events bypass the queue
 * entirely — this is the distinguishing SPI contract that this TCK verifies.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>GOLDEN — start/stop lifecycle: {@link eu.exeris.kernel.spi.events.EventLoop#start()}
 *       transitions the loop to running state ({@code isRunning()} = true).
 *       {@link eu.exeris.kernel.spi.events.EventLoop#stop()} transitions back to not-running.
 *       Double {@code start()} is idempotent; {@code stop()} on a not-started loop is a no-op.</li>
 *   <li>Dispatch reaches registered processor: a persistent event published via the bus reaches the
 *       registered {@link eu.exeris.kernel.spi.events.EventBatchProcessor} with the correct ordinal
 *       within 4 seconds.</li>
 *   <li>Non-matching processor is NOT invoked: a processor registered for type B is never called
 *       when only type C events are published.</li>
 *   <li>Non-persistent dispatch payload close: {@link EventPayload#close()} is called for a
 *       non-persistent event with no bus subscribers — no slab memory leak.</li>
 *   <li>GOLDEN — processor failure does not halt the loop: a processor that throws
 *       {@link RuntimeException} does not prevent the loop from delivering subsequent events.</li>
 *   <li>Metrics accessor: {@code processedTotal()} is NOT exposed on the
 *       {@link eu.exeris.kernel.spi.events.EventLoop} SPI — corresponding test is
 *       {@link Disabled}. Use {@code EventEngine.stats()} for aggregate metrics.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityEventLoopTckTest extends AbstractEventLoopTck {
 *     \@Override
 *     protected EventEngine createEngine() {
 *         return new CommunityEventProvider().createEngine(EventEngineConfig.communityDefaults());
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractEventLoopTck {

    // =========================================================================
    // Event type constants — ordinals in [400, 410] to avoid collisions with
    // AbstractEventBusTck [100-101] and AbstractOutboxOrchestratorTck [300-301]
    // =========================================================================

    /** Non-persistent type — routes only through the in-memory bus, never the queue. */
    private static final String TYPE_LOOP_A = "LoopEventA";
    /** Persistent type B — routes through the queue and loop. */
    private static final String TYPE_LOOP_B = "LoopEventB";
    /** Persistent type C — second persistent type used for isolation tests. */
    private static final String TYPE_LOOP_C = "LoopEventC";

    private static final int ORD_LOOP_A = 400;
    private static final int ORD_LOOP_B = 401;
    private static final int ORD_LOOP_C = 402;

    private EventEngine engine;

    /**
     * Creates a fully configured, not-yet-started {@link EventEngine} under test.
     *
     * @return non-null engine instance
     */
    protected abstract EventEngine createEngine();

    @AfterEach
    final void afterEach() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    // =========================================================================
    // Nested: Loop lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Loop lifecycle")
    class LoopLifecycle {

        @Test
        @DisplayName("start() transitions loop to running state (isRunning() = true)")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        final void startTransitionsToRunning() {
            engine = createEngine();
            engine.registry().register(EventTypeSpec.of(TYPE_LOOP_A, ORD_LOOP_A));
            engine.start();
            assertThat(engine.loop().isRunning())
                    .as("isRunning() MUST return true immediately after start()")
                    .isTrue();
        }

        @Test
        @DisplayName("stop() transitions loop to not-running state (isRunning() = false)")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        final void stopTransitionsToNotRunning() {
            engine = createEngine();
            engine.registry().register(EventTypeSpec.of(TYPE_LOOP_A, ORD_LOOP_A));
            engine.start();
            engine.loop().stop();
            assertThat(engine.loop().isRunning())
                    .as("isRunning() MUST return false after stop()")
                    .isFalse();
        }

        @Test
        @DisplayName("double start() is idempotent — no exception, loop remains running")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        final void doubleStartIsIdempotent() {
            engine = createEngine();
            engine.registry().register(EventTypeSpec.of(TYPE_LOOP_A, ORD_LOOP_A));
            engine.start();
            assertThatCode(() -> engine.loop().start())
                    .as("second start() MUST be idempotent — no exception expected")
                    .doesNotThrowAnyException();
            assertThat(engine.loop().isRunning())
                    .as("Loop MUST remain running after idempotent second start()")
                    .isTrue();
        }

        @Test
        @DisplayName("stop() on a not-started loop is a no-op (no exception)")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        final void stopOnNotStartedLoopIsNoOp() {
            engine = createEngine();
            assertThatCode(() -> engine.loop().stop())
                    .as("stop() on a not-started loop MUST be a safe no-op")
                    .doesNotThrowAnyException();
            assertThat(engine.loop().isRunning())
                    .as("isRunning() MUST remain false after stop() on a not-started loop")
                    .isFalse();
        }
    }

    // =========================================================================
    // Nested: Processor dispatch
    // =========================================================================

    @Nested
    @DisplayName("Processor dispatch")
    class ProcessorDispatch {

        @Test
        @DisplayName("registered processor is invoked for matching persistent event within 4s")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        final void processorInvokedForMatchingPersistentEvent() throws InterruptedException {
            engine = createEngine();
            engine.registry().register(EventTypeSpec.ofPersistent(TYPE_LOOP_B, ORD_LOOP_B));

            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger ordinalReceived = new AtomicInteger(-1);

            engine.loop().registerProcessor(TYPE_LOOP_B, (descriptors, payloads) -> {
                payloads.forEach(EventPayload::close);
                ordinalReceived.set(descriptors.get(0).eventTypeOrdinal());
                latch.countDown();
            });

            engine.start();
            engine.bus().publish(persistentDescriptor(ORD_LOOP_B), EventPayload.empty());

            assertThat(latch.await(4, TimeUnit.SECONDS))
                    .as("Processor MUST be invoked within 4 seconds for a persistent event")
                    .isTrue();
            assertThat(ordinalReceived.get())
                    .as("Processor MUST receive the correct eventTypeOrdinal")
                    .isEqualTo(ORD_LOOP_B);
        }

        @Test
        @DisplayName("processor registered for type B is NOT invoked when only type C is published")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        final void processorForTypeBNotInvokedWhenTypeCPublished() throws InterruptedException {
            engine = createEngine();
            engine.registry().register(EventTypeSpec.ofPersistent(TYPE_LOOP_B, ORD_LOOP_B));
            engine.registry().register(EventTypeSpec.ofPersistent(TYPE_LOOP_C, ORD_LOOP_C));

            AtomicBoolean processorBInvoked = new AtomicBoolean(false);
            engine.loop().registerProcessor(TYPE_LOOP_B, (descriptors, payloads) -> {
                payloads.forEach(EventPayload::close);
                processorBInvoked.set(true);
            });

            // Sentinel: register a processor for type C so we know the loop has processed it
            CountDownLatch typeCProcessed = new CountDownLatch(1);
            engine.loop().registerProcessor(TYPE_LOOP_C, (descriptors, payloads) -> {
                payloads.forEach(EventPayload::close);
                typeCProcessed.countDown();
            });

            engine.start();
            // Publish only type C — processor for B MUST NOT be triggered
            engine.bus().publish(persistentDescriptor(ORD_LOOP_C), EventPayload.empty());

            // Wait until the type C event has been processed; then the loop has passed through
            // the point where type B would have been dispatched if routing were wrong.
            assertThat(typeCProcessed.await(4, TimeUnit.SECONDS))
                    .as("Type C sentinel event MUST reach its processor within 4 seconds")
                    .isTrue();
            assertThat(processorBInvoked.get())
                    .as("Processor for type B MUST NOT be invoked when only type C events are published. "
                        + "Ordinal-based routing MUST be exact — no cross-type dispatch.")
                    .isFalse();
        }

        @Test
        @DisplayName("GOLDEN — processor failure does not halt loop; subsequent events still delivered")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        final void failingProcessorDoesNotHaltLoop() throws InterruptedException {
            engine = createEngine();
            engine.registry().register(EventTypeSpec.ofPersistent(TYPE_LOOP_B, ORD_LOOP_B));
            engine.registry().register(EventTypeSpec.ofPersistent(TYPE_LOOP_C, ORD_LOOP_C));

            CountDownLatch failureLatch = new CountDownLatch(1);
            CountDownLatch successLatch = new CountDownLatch(1);

            // Failing processor for type B — throws on every invocation; signals via latch
            engine.loop().registerProcessor(TYPE_LOOP_B, (descriptors, payloads) -> {
                payloads.forEach(EventPayload::close);
                failureLatch.countDown();
                throw new RuntimeException("TCK-induced processor failure");
            });

            // Success processor for type C — must be invoked despite type B failure
            engine.loop().registerProcessor(TYPE_LOOP_C, (descriptors, payloads) -> {
                payloads.forEach(EventPayload::close);
                successLatch.countDown();
            });

            engine.start();

            // DeadPayload.isAlive()=false prevents infinite requeue of the failing event
            engine.bus().publish(persistentDescriptor(ORD_LOOP_B), new DeadPayload());

            // Wait for the failure to be processed before publishing type C
            assertThat(failureLatch.await(3, TimeUnit.SECONDS))
                    .as("Failing processor for type B MUST be invoked within 3 seconds")
                    .isTrue();
            engine.bus().publish(persistentDescriptor(ORD_LOOP_C), EventPayload.empty());

            assertThat(successLatch.await(4, TimeUnit.SECONDS))
                    .as("Loop MUST continue processing events after a processor throws RuntimeException. "
                        + "If type C was not delivered, the loop halted after the type B failure.")
                    .isTrue();
        }
    }

    // =========================================================================
    // Non-persistent payload close — bus/engine integration
    // =========================================================================

    @Test
    @DisplayName("non-persistent event payload is closed after dispatch with no bus subscribers")
    final void nonPersistentEventPayloadClosedAfterDispatch() {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.of(TYPE_LOOP_A, ORD_LOOP_A));
        engine.start();

        AtomicInteger closeCalls = new AtomicInteger(0);
        TrackingPayload payload = new TrackingPayload(closeCalls);

        // Non-persistent → bypasses the queue; bus closes payload synchronously (N=0 subscribers)
        engine.bus().publish(nonPersistentDescriptor(ORD_LOOP_A), payload);

        assertThat(closeCalls.get())
                .as("Non-persistent event payload MUST be closed synchronously after dispatch "
                    + "with N=0 bus subscribers. Omitting close() causes a slab memory leak "
                    + "in the Enterprise tier.")
                .isEqualTo(1);
        assertThat(payload.isAlive())
                .as("Payload MUST NOT be alive after close()")
                .isFalse();
    }

    // =========================================================================
    // Metrics — SPI gap marker
    // =========================================================================

    @Test
    @Disabled("No metric accessor on EventLoop SPI — processedTotal() is package-private on "
              + "CommunityEventLoop. Use EventEngine.stats() for aggregate metrics.")
    @DisplayName("processedTotal() increases monotonically (no direct SPI accessor — @Disabled)")
    final void metricsProcessedTotalNotExposedOnSpi() {
        // Intentionally blank. If a metrics accessor is added to the EventLoop SPI,
        // implement here: start engine, publish N events, assert stats().processedTotal() >= N.
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static EventDescriptor nonPersistentDescriptor(int ordinal) {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ordinal,
                EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis());
    }

    private static EventDescriptor persistentDescriptor(int ordinal) {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ordinal,
                EventDescriptor.FLAG_PERSISTENT | EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis());
    }

    // =========================================================================
    // Inner helpers
    // =========================================================================

    /**
     * Minimal {@link EventPayload} that tracks {@code close()} calls for RAII verification.
     * {@link #isAlive()} returns {@code false} after the first {@link #close()} call.
     */
    private static final class TrackingPayload implements EventPayload {

        private final AtomicInteger closeCalls;
        private volatile boolean alive = true;

        TrackingPayload(AtomicInteger closeCalls) {
            this.closeCalls = closeCalls;
        }

        @Override public MemorySegment segment() { return MemorySegment.NULL; }
        @Override public int    length()   { return 0; }
        @Override public int    refCount() { return alive ? 1 : 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void   retain()   {}

        @Override
        public void close() {
            alive = false;
            closeCalls.incrementAndGet();
        }
    }

    /**
     * An {@link EventPayload} with {@link #isAlive()} permanently {@code false}.
     *
     * <p>Use in processor-failure tests to prevent the loop from re-queuing the failing event.
     * The {@link eu.exeris.kernel.spi.events.EventLoop} implementation checks {@code isAlive()}
     * before deciding to requeue a persistent event after a processor exception — a {@code false}
     * result breaks the requeue cycle and lets the loop continue cleanly.
     */
    private static final class DeadPayload implements EventPayload {

        @Override public MemorySegment segment() { return MemorySegment.NULL; }
        @Override public int    length()   { return 0; }
        @Override public int    refCount() { return 0; }
        @Override public boolean isAlive() { return false; }
        @Override public void   retain()   {}
        @Override public void   close()    {}
    }
}
