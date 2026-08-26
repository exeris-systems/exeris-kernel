/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AsyncTelemetrySink")
class AsyncTelemetrySinkTest {

    private static final long DELIVERY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2L);

    @Test
    @DisplayName("emit() forwards events to all wrapped sinks asynchronously")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void emitFansOutToWrappedSinks() throws InterruptedException {
        CountDownLatch deliveredA = new CountDownLatch(1);
        CountDownLatch deliveredB = new CountDownLatch(1);
        CapturingSink sinkA = new CapturingSink(deliveredA);
        CapturingSink sinkB = new CapturingSink(deliveredB);

        try (AsyncTelemetrySink async = AsyncTelemetrySink.start(List.of(sinkA, sinkB))) {
            async.emit(KernelEvent.info("EX-TEL-1001", "AsyncTest"));

            assertThat(deliveredA.await(DELIVERY_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)).isTrue();
            assertThat(deliveredB.await(DELIVERY_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)).isTrue();
        }

        assertThat(sinkA.eventCodes).containsExactly("EX-TEL-1001");
        assertThat(sinkB.eventCodes).containsExactly("EX-TEL-1001");
    }

    @Test
    @DisplayName("emit() drops events when the ring is full and increments drop counter")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void overflowDropsNewestAndIncrementsCounter() throws InterruptedException {
        CountDownLatch consumerBlocked = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        BlockingSink slowSink = new BlockingSink(consumerBlocked, unblock);

        // capacity=2 → after the consumer pulls 1 event and blocks, two more can sit in the ring,
        // and any further emits must be dropped.
        try (AsyncTelemetrySink async = AsyncTelemetrySink.start(List.of(slowSink), 2,
                Duration.ofMillis(500L))) {
            async.emit(KernelEvent.info("EX-TEL-2001", "AsyncTest"));
            // Wait until the consumer has actually picked up the first event and is blocked.
            assertThat(consumerBlocked.await(2L, TimeUnit.SECONDS)).isTrue();

            // Fill the ring (capacity=2) and then over-fill it.
            async.emit(KernelEvent.info("EX-TEL-2002", "AsyncTest"));
            async.emit(KernelEvent.info("EX-TEL-2003", "AsyncTest"));
            async.emit(KernelEvent.info("EX-TEL-2004", "AsyncTest")); // dropped
            async.emit(KernelEvent.info("EX-TEL-2005", "AsyncTest")); // dropped

            assertThat(async.droppedCount())
                    .as("two events should have been dropped after the ring filled")
                    .isEqualTo(2L);

            unblock.countDown();
        }

        assertThat(slowSink.processedCount.get())
                .as("non-dropped events must have been delivered before close()")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("close() drains pending events before returning")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void closeDrainsPendingEvents() {
        CapturingSink sink = new CapturingSink(null);

        try (AsyncTelemetrySink async = AsyncTelemetrySink.start(List.of(sink), 1024,
                Duration.ofSeconds(2L))) {
            for (int i = 0; i < 100; i++) {
                async.emit(KernelEvent.info("EX-TEL-3" + String.format("%03d", i), "AsyncTest"));
            }
            // Implicit close() inside try-with-resources must drain the 100 pending events.
        }

        assertThat(sink.eventCodes).hasSize(100);
        assertThat(sink.closed).isTrue();
    }

    @Test
    @DisplayName("Metrics calls are forwarded to wrapped sinks synchronously")
    void metricsAreForwardedSynchronously() {
        RecordingMetricsSink sink = new RecordingMetricsSink();

        try (AsyncTelemetrySink async = AsyncTelemetrySink.start(List.of(sink))) {
            async.increment("kernel.bootstrap", 1L);
            async.gauge("kernel.heap.bytes", 4096L);
            async.latency("kernel.flush.nanos", 12345L);

            // No await needed — metrics are synchronous pass-through.
            assertThat(sink.increments).containsExactly("kernel.bootstrap=1");
            assertThat(sink.gauges).containsExactly("kernel.heap.bytes=4096");
            assertThat(sink.latencies).containsExactly("kernel.flush.nanos=12345");
        }
    }

    @Test
    @DisplayName("emit() after close() is a no-op")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void emitAfterCloseIsNoop() {
        CapturingSink sink = new CapturingSink(null);
        AsyncTelemetrySink async = AsyncTelemetrySink.start(List.of(sink));
        async.close();
        // After close() returns, the consumer VT has stopped (drain awaited via the
        // internal CountDownLatch with a 2 s timeout). Any subsequent emit() must
        // therefore be observed as a no-op without us having to wait further.
        async.emit(KernelEvent.info("EX-TEL-4001", "AsyncTest"));

        assertThat(sink.eventCodes).isEmpty();
    }

    @Test
    @DisplayName("Constructor rejects empty downstream list and non-positive capacity")
    void constructorRejectsInvalidArgs() {
        assertThatThrownBy(() -> AsyncTelemetrySink.start(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("downstream");
        assertThatThrownBy(() -> AsyncTelemetrySink.start(List.of(new CapturingSink(null)), 0,
                Duration.ofMillis(100L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    @DisplayName("A throwing downstream sink does not starve other sinks")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void throwingSinkDoesNotStarveOthers() throws InterruptedException {
        CountDownLatch deliveredB = new CountDownLatch(1);
        CapturingSink sinkB = new CapturingSink(deliveredB);
        TelemetrySink sinkA = new ThrowingSink();

        try (AsyncTelemetrySink async = AsyncTelemetrySink.start(List.of(sinkA, sinkB))) {
            async.emit(KernelEvent.info("EX-TEL-5001", "AsyncTest"));

            assertThat(deliveredB.await(DELIVERY_TIMEOUT_NANOS, TimeUnit.NANOSECONDS))
                    .as("sinkB must receive the event even when sinkA throws")
                    .isTrue();
        }

        assertThat(sinkB.eventCodes).containsExactly("EX-TEL-5001");
    }

    private static final class CapturingSink implements TelemetrySink {

        private final List<String> eventCodes = new ArrayList<>();
        private final CountDownLatch deliveryLatch;
        private boolean closed;

        CapturingSink(CountDownLatch deliveryLatch) {
            this.deliveryLatch = deliveryLatch;
        }

        @Override
        public synchronized void emit(KernelEvent event) {
            eventCodes.add(event.code());
            if (deliveryLatch != null) {
                deliveryLatch.countDown();
            }
        }

        @Override
        public void increment(String name, long delta) {
            // not used in these tests
        }

        @Override
        public void gauge(String name, long value) {
            // not used in these tests
        }

        @Override
        public void latency(String name, long nanoseconds) {
            // not used in these tests
        }

        @Override
        public String sinkName() {
            return "CapturingSink";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class BlockingSink implements TelemetrySink {

        private final CountDownLatch consumerEnteredEmit;
        private final CountDownLatch unblock;
        private final AtomicLong processedCount = new AtomicLong();

        BlockingSink(CountDownLatch consumerEnteredEmit, CountDownLatch unblock) {
            this.consumerEnteredEmit = consumerEnteredEmit;
            this.unblock = unblock;
        }

        @Override
        public void emit(KernelEvent event) {
            // Signal that the consumer is in our hands so the test can fill the ring beyond it.
            consumerEnteredEmit.countDown();
            try {
                unblock.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            processedCount.incrementAndGet();
        }

        @Override
        public void increment(String name, long delta) {
            // not used
        }

        @Override
        public void gauge(String name, long value) {
            // not used
        }

        @Override
        public void latency(String name, long nanoseconds) {
            // not used
        }

        @Override
        public String sinkName() {
            return "BlockingSink";
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class RecordingMetricsSink implements TelemetrySink {

        private final List<String> increments = new ArrayList<>();
        private final List<String> gauges = new ArrayList<>();
        private final List<String> latencies = new ArrayList<>();

        @Override
        public void emit(KernelEvent event) {
            // not used
        }

        @Override
        public void increment(String name, long delta) {
            increments.add(name + "=" + delta);
        }

        @Override
        public void gauge(String name, long value) {
            gauges.add(name + "=" + value);
        }

        @Override
        public void latency(String name, long nanoseconds) {
            latencies.add(name + "=" + nanoseconds);
        }

        @Override
        public String sinkName() {
            return "RecordingMetricsSink";
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class ThrowingSink implements TelemetrySink {

        @Override
        public void emit(KernelEvent event) {
            throw new IllegalStateException("intentional test failure");
        }

        @Override
        public void increment(String name, long delta) {
            // not used
        }

        @Override
        public void gauge(String name, long value) {
            // not used
        }

        @Override
        public void latency(String name, long nanoseconds) {
            // not used
        }

        @Override
        public String sinkName() {
            return "ThrowingSink";
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
