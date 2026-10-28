/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.memory.MemoryMaintenanceTask;
import eu.exeris.kernel.core.memory.WatermarkLevel;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.time.TimeKernelProviders;
import eu.exeris.kernel.spi.time.TimeSource;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A server-mode carrier turns the pressure its allocator reports into admission: the level is sampled
 * when the carrier starts and then on a timer, the arbiter's startup grace is timed by the clock bound
 * at start, and a shed connection is closed with no response.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpCarrier — watermark refresh drives admission")
class NativeTcpCarrierWatermarkAdmissionTest {

    private static final long BUDGET = 1_024L * 1_024L;
    private static final long PAST_GRACE_SECONDS = 31L;
    private static final long NEGATIVE_WAIT_MS = 300L;
    private static final long EVENT_WAIT_SECONDS = 10L;
    private static final int REFRESH_PERMITS = 3;
    private static final long FAST_REFRESH_MS = 5L;

    /** A source the test moves by hand. */
    private static final class MovableSource implements TimeSource {
        private final AtomicLong nowNs = new AtomicLong();

        void advanceSeconds(long seconds) {
            nowNs.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }

        @Override
        public long nanoTime() {
            return nowNs.get();
        }

        @Override
        public Instant wallTime() {
            return Instant.EPOCH;
        }
    }

    /** Delegates to a real allocator and signals one permit per {@code stats()} the refresh thread serves. */
    private static final class SignallingAllocator implements MemoryAllocator {
        private final MemoryAllocator delegate;
        private final Semaphore refreshes = new Semaphore(0);

        SignallingAllocator(MemoryAllocator delegate) {
            this.delegate = delegate;
        }

        @Override
        public MemoryStats stats() {
            MemoryStats stats = delegate.stats();
            if (MemoryMaintenanceTask.WATERMARK_REFRESH_THREAD_NAME.equals(Thread.currentThread().getName())) {
                refreshes.release();
            }
            return stats;
        }

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return delegate.allocate(hint);
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            return delegate.allocateNetwork(estimatedBytes);
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            return delegate.allocateCarrierSlab(carrierIndex);
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            return delegate.allocateInfrastructure(sizeBytes);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private final List<LoanedBuffer> held = new ArrayList<>();
    private MemoryAllocator allocator;
    private NativeTcpCarrier carrier;

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        if (carrier != null) {
            carrier.close();
        }
        held.forEach(LoanedBuffer::close);
        if (allocator != null) {
            allocator.close();
        }
    }

    private static MemoryAllocator budgeted(long budgetBytes) {
        MemoryProviderConfig defaults = MemoryProviderConfig.defaults();
        return new CommunityMemoryProvider().createAllocator(new MemoryProviderConfig(
                budgetBytes, defaults.networkOffHeapThreshold(), defaults.carrierCount(),
                defaults.leakDetection(), defaults.jfrEnabled()));
    }

    private void hold(long bytes) {
        held.add(allocator.allocateInfrastructure(bytes));
    }

    private NativeTcpCarrier newCarrier(int port, CountDownLatch handled) {
        TransportConfig config = new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 1_024, 30_000);
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(config));
        holder[0].setStreamHandler(stream -> handled.countDown());
        carrier = (NativeTcpCarrier) holder[0];
        return carrier;
    }

    private static void startUnder(NativeTcpCarrier target, MovableSource source) {
        ScopedValue.where(TimeKernelProviders.TIME_SOURCE, source).run(target::start);
    }

    private static Socket open(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(EVENT_WAIT_SECONDS));
        return socket;
    }

    /** Connects and asserts the handler never ran and the peer was closed with no byte written to it. */
    private static void assertShed(int port, CountDownLatch handled) throws Exception {
        try (Socket peer = open(port)) {
            assertThat(handled.await(NEGATIVE_WAIT_MS, TimeUnit.MILLISECONDS))
                    .as("the stream handler is not run for a shed connection").isFalse();
            assertThat(peer.getInputStream().read()).as("the peer sees a close, not a response").isEqualTo(-1);
        }
    }

    private static void assertAdmitted(int port, CountDownLatch handled) throws Exception {
        try (Socket peer = open(port)) {
            assertThat(handled.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the stream handler is run for an admitted connection").isTrue();
        }
    }

    @ParameterizedTest(name = "holding {0}% of the budget sheds, at level {1}")
    @CsvSource({"75, WARNING", "90, CRITICAL", "97, SHEDDING"})
    @DisplayName("a carrier started under pressure sheds from its first connection")
    void startedUnderPressureSheds(int percent, WatermarkLevel expected) throws Exception {
        allocator = budgeted(BUDGET);
        hold(BUDGET / 100L * percent);
        int port = CommunityTransportTestHarness.nextFreePort();
        CountDownLatch handled = new CountDownLatch(1);
        MovableSource source = new MovableSource();
        NativeTcpCarrier target = newCarrier(port, handled);
        startUnder(target, source);
        source.advanceSeconds(PAST_GRACE_SECONDS);

        assertThat(target.watermarkLevel()).isEqualTo(expected);
        assertShed(port, handled);
    }

    @Test
    @DisplayName("60% of the budget is below every watermark: the connection is admitted")
    void belowTheWatermarksAdmits() throws Exception {
        allocator = budgeted(BUDGET);
        hold(BUDGET / 100L * 60L);
        int port = CommunityTransportTestHarness.nextFreePort();
        CountDownLatch handled = new CountDownLatch(1);
        MovableSource source = new MovableSource();
        NativeTcpCarrier target = newCarrier(port, handled);
        startUnder(target, source);
        source.advanceSeconds(PAST_GRACE_SECONDS);

        assertThat(target.watermarkLevel()).isEqualTo(WatermarkLevel.NORMAL);
        assertAdmitted(port, handled);
    }

    @Test
    @DisplayName("with no budget the level stays NORMAL however much is held")
    void noBudgetAdmitsUnderLoad() throws Exception {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        hold(4L * 1_024L * 1_024L);
        int port = CommunityTransportTestHarness.nextFreePort();
        CountDownLatch handled = new CountDownLatch(1);
        MovableSource source = new MovableSource();
        NativeTcpCarrier target = newCarrier(port, handled);
        startUnder(target, source);
        source.advanceSeconds(PAST_GRACE_SECONDS);

        assertThat(target.watermarkLevel()).isEqualTo(WatermarkLevel.NORMAL);
        assertAdmitted(port, handled);
    }

    @Test
    @DisplayName("inside the startup grace a carrier under pressure still admits")
    void graceAdmitsUnderPressure() throws Exception {
        allocator = budgeted(BUDGET);
        hold(BUDGET / 100L * 97L);
        int port = CommunityTransportTestHarness.nextFreePort();
        CountDownLatch handled = new CountDownLatch(1);
        NativeTcpCarrier target = newCarrier(port, handled);
        startUnder(target, new MovableSource());

        assertThat(target.watermarkLevel()).isEqualTo(WatermarkLevel.SHEDDING);
        assertAdmitted(port, handled);
    }

    @Test
    @DisplayName("pressure that arrives after start is picked up by the periodic refresh")
    void periodicRefreshPicksUpLaterPressure() throws Exception {
        MemoryAllocator real = budgeted(BUDGET);
        SignallingAllocator signalling = new SignallingAllocator(real);
        allocator = signalling;
        int port = CommunityTransportTestHarness.nextFreePort();
        CountDownLatch handled = new CountDownLatch(1);
        MovableSource source = new MovableSource();
        NativeTcpCarrier target = newCarrier(port, handled);
        target.watermarkRefreshIntervalMs(FAST_REFRESH_MS);
        startUnder(target, source);
        source.advanceSeconds(PAST_GRACE_SECONDS);
        assertThat(target.watermarkLevel()).isEqualTo(WatermarkLevel.NORMAL);

        hold(BUDGET / 100L * 97L);
        signalling.refreshes.drainPermits();

        assertThat(signalling.refreshes.tryAcquire(REFRESH_PERMITS, EVENT_WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the refresh thread sampled the allocator again").isTrue();
        assertThat(target.watermarkLevel()).isEqualTo(WatermarkLevel.SHEDDING);
        assertShed(port, handled);
    }

    @Test
    @DisplayName("the refresh runs from start() to stop(), restarts with the carrier, and ends at close()")
    void refreshFollowsTheLifecycle() {
        allocator = budgeted(BUDGET);
        int port = CommunityTransportTestHarness.nextFreePort();
        NativeTcpCarrier target = newCarrier(port, new CountDownLatch(1));
        assertThat(target.watermarkRefreshRunning()).isFalse();

        target.start();
        assertThat(target.watermarkRefreshRunning()).isTrue();

        target.stop();
        assertThat(target.watermarkRefreshRunning()).isFalse();

        target.start();
        assertThat(target.watermarkRefreshRunning()).isTrue();

        target.close();
        assertThat(target.watermarkRefreshRunning()).isFalse();
    }

    @Test
    @DisplayName("a start that cannot bind leaves no refresh running")
    void failedStartLeavesNoRefresh() throws Exception {
        allocator = budgeted(BUDGET);
        try (ServerSocket occupant = new ServerSocket()) {
            occupant.bind(new InetSocketAddress("127.0.0.1", 0));
            NativeTcpCarrier target = newCarrier(occupant.getLocalPort(), new CountDownLatch(1));

            assertThatThrownBy(target::start).isInstanceOf(TransportException.class);

            assertThat(target.watermarkRefreshRunning()).isFalse();
        }
    }
}
