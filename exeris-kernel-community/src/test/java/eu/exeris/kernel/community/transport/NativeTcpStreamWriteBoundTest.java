/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stream with a bounded outbound queue parks its writer at the bound, wakes it when the peer has
 * taken enough, never parks a reactor thread, and keeps an exact count of the bytes it holds.
 *
 * <p>Each case drives a real loopback channel with no reactor: the test supplies the drain by
 * flushing the stream from a pump that also reads the peer, so a case decides exactly when the
 * peer takes bytes. The peer's socket buffers are shrunk so a few kilobytes fill them.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpStream — a bounded outbound queue")
class NativeTcpStreamWriteBoundTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final int BOUND = 64 * 1024;
    private static final int MIB = 1024 * 1024;
    private static final int FILL_BLOCK = 8 * 1024;
    private static final int FILL_ATTEMPTS = 100_000;
    private static final long AWAIT_SECONDS = 10L;
    private static final long QUIET_MILLIS = 300L;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("a write above the bound parks with at most the bound queued, and resumes in order once the peer drains")
    void writeParksAtTheBoundAndResumesWhenThePeerDrains() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            long total = rig.sent.get() + MIB;
            WriterRun writer = rig.startWriter(MIB);
            writer.awaitParked();

            assertThat(rig.stream.outboundQueuedBytesForTest())
                    .as("a write queues at most the bound before it parks")
                    .isLessThanOrEqualTo(BOUND);
            assertThat(writer.finishedWithin(QUIET_MILLIS)).as("the peer has taken nothing").isFalse();

            try (Pump pump = rig.startPump(total, true)) {
                assertThat(writer.outcome()).as("the write returns once the peer drains").isNull();
                pump.awaitReceived();
                assertThat(pump.problem()).as("every byte arrives in order").isNull();
            }
            assertThat(rig.stream.outboundQueuedBytesForTest()).isZero();
        }
    }

    @Test
    @DisplayName("queueWrite goes past the bound without waiting, and the next write parks until the drain crosses the resume mark")
    void queueWriteGoesPastTheBoundAndTheNextWriteParks() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            Thread queuer = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 3; i++) {
                    rig.queueLoan(BOUND);
                }
            });
            queuer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertThat(queuer.isAlive()).as("queueWrite does not wait on the bound").isFalse();
            assertThat(rig.stream.outboundQueuedBytesForTest())
                    .as("queueWrite is accounted and may exceed the bound")
                    .isGreaterThan(BOUND);

            long total = rig.sent.get() + 100;
            WriterRun writer = rig.startWriter(100);
            writer.awaitParked();

            try (Pump pump = rig.startPump(total, true)) {
                assertThat(writer.outcome()).isNull();
                pump.awaitReceived();
                assertThat(pump.problem()).isNull();
            }
            assertThat(rig.stream.outboundQueuedBytesForTest()).as("everything drained").isZero();
        }
    }

    @Test
    @DisplayName("a parked writer is released by the stream's close() with the locally-closed signal")
    void parkedWriterIsReleasedByClose() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            WriterRun writer = rig.parkWriter();

            rig.stream.close();

            assertClosedWith(writer.outcome(), KernelErrorCodes.EX_NET_4009);
        }
    }

    @Test
    @DisplayName("a parked writer is released by the stream's reset() with the locally-closed signal")
    void parkedWriterIsReleasedByReset() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            WriterRun writer = rig.parkWriter();

            rig.stream.reset(0L);

            assertClosedWith(writer.outcome(), KernelErrorCodes.EX_NET_4009);
        }
    }

    @Test
    @DisplayName("a parked writer is released by the peer's abortive close with the peer-closed signal")
    void parkedWriterIsReleasedByThePeersReset() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            WriterRun writer = rig.parkWriter();

            rig.serverChannel.setOption(StandardSocketOptions.SO_LINGER, 0);
            rig.serverChannel.close();
            try (Pump pump = rig.startPump(Long.MAX_VALUE, false)) {
                assertClosedWith(writer.outcome(), KernelErrorCodes.EX_NET_4008);
                assertThat(pump.problem()).isNull();
            }
        }
    }

    @Test
    @DisplayName("a parked writer is released by a carrier teardown with the peer-closed signal")
    void parkedWriterIsReleasedByCarrierTeardown() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            WriterRun writer = rig.parkWriter();

            rig.stream.markRemoteClosed();
            rig.stream.reset(0L);

            assertClosedWith(writer.outcome(), KernelErrorCodes.EX_NET_4008);
        }
    }

    @Test
    @DisplayName("the peer's half-close ends the stream's reader but not the parked writer, which completes once the peer reads")
    void peerHalfCloseDoesNotEndAParkedWriter() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            long total = rig.sent.get() + MIB;
            WriterRun writer = rig.startWriter(MIB);
            writer.awaitParked();

            rig.serverChannel.shutdownOutput();
            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                assertThat(rig.stream.readPlainIngress(sink.segment(), 64))
                        .as("the carrier's read sees the peer's FIN")
                        .isEqualTo(-1);
                rig.stream.markRemoteClosed();
                assertThat(rig.stream.read(sink.segment(), 64)).as("the reader reports end of stream").isEqualTo(-1);
            }

            assertThat(writer.finishedWithin(QUIET_MILLIS))
                    .as("a FIN is a half-close: the writer stays parked")
                    .isFalse();
            try (Pump pump = rig.startPump(total, true)) {
                assertThat(writer.outcome()).as("the write completes once the peer reads").isNull();
                pump.awaitReceived();
                assertThat(pump.problem()).isNull();
            }
        }
    }

    @Test
    @DisplayName("a write made on a reactor thread queues past the bound instead of parking, and the same write elsewhere parks")
    void reactorThreadNeverParks() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            Thread reactor = Thread.ofVirtual().start(() ->
                    ScopedValue.where(NativeTcpReactor.REACTOR_THREAD, Boolean.TRUE).run(() -> rig.write(MIB)));
            reactor.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertThat(reactor.isAlive()).as("a reactor thread does not park").isFalse();
            assertThat(rig.stream.outboundQueuedBytesForTest())
                    .as("the write went past the bound")
                    .isGreaterThan(BOUND);

            long total = rig.sent.get() + 1;
            WriterRun ordinary = rig.startWriter(1);
            ordinary.awaitParked();

            try (Pump pump = rig.startPump(total, true)) {
                assertThat(ordinary.outcome()).isNull();
                pump.awaitReceived();
                assertThat(pump.problem()).isNull();
            }
        }
    }

    @Test
    @DisplayName("a second writer that would park while one is parked is refused, and sequential writers succeed")
    void secondParkingWriterIsRefused() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            long total = rig.sent.get() + MIB;
            WriterRun first = rig.startWriter(MIB);
            first.awaitParked();

            WriterRun second = rig.startWriter(100);

            assertThat(second.outcome())
                    .as("a second blocking writer is refused rather than parked beside the first")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Concurrent blocking writers");
            try (Pump pump = rig.startPump(total, true)) {
                assertThat(first.outcome()).isNull();
                pump.awaitReceived();
                assertThat(pump.problem()).isNull();
            }
            assertThat(rig.startWriter(100).outcome()).as("a sequential writer succeeds").isNull();
            assertThat(rig.startWriter(100).outcome()).as("and so does the next").isNull();
        }
    }

    @Test
    @DisplayName("with no bound a write that finds the peer not reading returns, and what it queued is accounted")
    void unboundedWriteNeverParks() throws Exception {
        try (Rig rig = new Rig(TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES)) {
            rig.fillUntilPending();
            long queuedBefore = rig.stream.outboundQueuedBytesForTest();
            long total = rig.sent.get() + MIB;

            WriterRun writer = rig.startWriter(MIB);

            assertThat(writer.outcome()).as("an unbounded write does not park").isNull();
            assertThat(rig.stream.outboundQueuedBytesForTest()).isEqualTo(queuedBefore + MIB);
            try (Pump pump = rig.startPump(total, true)) {
                pump.awaitReceived();
                assertThat(pump.problem()).isNull();
            }
            assertThat(rig.stream.outboundQueuedBytesForTest()).isZero();
        }
    }

    @Test
    @DisplayName("an interrupted parked writer fails with IllegalStateException and keeps its interrupt status")
    void interruptedParkedWriterKeepsItsInterruptStatus() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            WriterRun writer = rig.parkWriter();

            writer.thread.interrupt();

            assertThat(writer.outcome()).isInstanceOf(IllegalStateException.class);
            assertThat(writer.interruptStatusAfterFailure.get()).as("the interrupt is not swallowed").isTrue();
        }
    }

    @Test
    @DisplayName("the byte count is exactly zero after a drain, a reset and an abort")
    void byteCountReturnsToZero() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            rig.queueLoan(BOUND);
            assertThat(rig.stream.outboundQueuedBytesForTest()).isPositive();

            rig.stream.reset(0L);

            assertThat(rig.stream.hasPendingData()).isFalse();
            assertThat(rig.stream.outboundQueuedBytesForTest()).as("a reset abandons the queue").isZero();
        }
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            rig.queueLoan(BOUND);
            rig.serverChannel.setOption(StandardSocketOptions.SO_LINGER, 0);
            rig.serverChannel.close();

            try (Pump pump = rig.startPump(Long.MAX_VALUE, false)) {
                pump.awaitEnded();
            }

            assertThat(rig.stream.outboundQueuedBytesForTest()).as("an abort abandons the queue").isZero();
        }
        try (Rig rig = new Rig(BOUND)) {
            rig.fillUntilPending();
            long total = rig.sent.get();
            rig.stream.close();
            try (Pump pump = rig.startPump(total, true)) {
                pump.awaitReceived();
                assertThat(pump.problem()).isNull();
            }
            assertThat(rig.stream.outboundQueuedBytesForTest()).as("a close drains the queue").isZero();
        }
    }

    @Test
    @DisplayName("the test seams that publish a write count its bytes, so a reset leaves the counter at zero")
    void publishedWriteIsCounted() throws Exception {
        try (Rig rig = new Rig(BOUND)) {
            rig.stream.reserveOutboundWriteForTest();
            LoanedBuffer buffer = ALLOCATOR.allocateNetwork(16);
            buffer.setSize(16);
            assertThat(rig.stream.publishReservedWriteForTest(buffer, 16)).isTrue();
            assertThat(rig.stream.outboundQueuedBytesForTest()).isEqualTo(16);

            rig.stream.reset(0L);

            assertThat(rig.stream.outboundQueuedBytesForTest()).isZero();
            assertThat(buffer.isAlive()).isFalse();
        }
    }

    @Test
    @DisplayName("a stream refuses a bound that is neither positive nor the unbounded sentinel")
    void streamRefusesAnInvalidBound() {
        for (int invalid : new int[] {0, -2}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Rig(invalid).close())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxQueuedWriteBytes");
        }
    }

    private static void assertClosedWith(Throwable failure, String code) {
        assertThat(failure).isInstanceOfSatisfying(TransportStreamClosedException.class,
                closed -> assertThat(closed.errorCode()).isEqualTo(code));
    }

    private static byte pattern(long offset) {
        return (byte) (offset % 251);
    }

    /** One stream over a real loopback connection whose peer is read only when a case says so. */
    private static final class Rig implements AutoCloseable {

        private final ServerSocketChannel listener;
        private final SocketChannel clientChannel;
        private final SocketChannel serverChannel;
        private final NativeTcpStream stream;
        private final AtomicLong sent = new AtomicLong();

        private Rig(int bound) throws IOException {
            listener = ServerSocketChannel.open();
            listener.setOption(StandardSocketOptions.SO_RCVBUF, 4096);
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            clientChannel = SocketChannel.open();
            clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 4096);
            clientChannel.connect(new InetSocketAddress("127.0.0.1", port));
            serverChannel = listener.accept();
            serverChannel.configureBlocking(false);
            clientChannel.configureBlocking(false);
            NativeTcpConnection connection = new NativeTcpConnection(1L, "127.0.0.1", port);
            NativeTcpStream created;
            try {
                created = new NativeTcpStream("test-engine", 1L, clientChannel, connection, ALLOCATOR, null,
                        () -> { }, () -> { }, null, NativeTcpHandshakeBackoff.PARK, bound);
            } catch (RuntimeException refused) {
                close(null);
                throw refused;
            }
            stream = created;
            connection.bindSingleStream(stream);
        }

        /** Writes {@code length} bytes of the continuing pattern from the calling thread. */
        private void write(int length) {
            try (LoanedBuffer buffer = ALLOCATOR.allocateNetwork(length)) {
                MemorySegment segment = buffer.segment();
                long base = sent.get();
                for (int i = 0; i < length; i++) {
                    segment.set(ValueLayout.JAVA_BYTE, i, pattern(base + i));
                }
                stream.write(segment, length);
                sent.addAndGet(length);
            }
        }

        /** Hands the stream a loan of the continuing pattern through {@code queueWrite}. */
        private void queueLoan(int length) {
            LoanedBuffer buffer = ALLOCATOR.allocateNetwork(length);
            long base = sent.get();
            for (int i = 0; i < length; i++) {
                buffer.segment().set(ValueLayout.JAVA_BYTE, i, pattern(base + i));
            }
            buffer.setSize(length);
            stream.queueWrite(buffer, length);
            sent.addAndGet(length);
        }

        /** Writes until the socket refuses and the stream holds queued bytes; never parks. */
        private void fillUntilPending() {
            for (int attempt = 0; !stream.hasPendingData(); attempt++) {
                assertThat(attempt).as("the socket refuses writes while the peer does not read")
                        .isLessThan(FILL_ATTEMPTS);
                write(FILL_BLOCK);
            }
        }

        private WriterRun startWriter(int length) {
            return new WriterRun(this, length);
        }

        private WriterRun parkWriter() {
            fillUntilPending();
            WriterRun writer = startWriter(MIB);
            writer.awaitParked();
            return writer;
        }

        private Pump startPump(long expectedTotal, boolean drainPeer) {
            return new Pump(this, expectedTotal, drainPeer);
        }

        @Override
        public void close() throws IOException {
            close(stream);
        }

        private void close(NativeTcpStream toClose) {
            try {
                if (toClose != null) {
                    toClose.close();
                }
            } finally {
                closeQuietly(serverChannel);
                closeQuietly(clientChannel);
                closeQuietly(listener);
            }
        }

        private static void closeQuietly(java.nio.channels.Channel channel) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException _) {
                // best effort teardown of a test fixture
            }
        }
    }

    /** A virtual thread writing the pattern, with the way it ended exposed. */
    private static final class WriterRun {

        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean interruptStatusAfterFailure = new AtomicBoolean();
        private final Thread thread;

        private WriterRun(Rig rig, int length) {
            thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    rig.write(length);
                } catch (RuntimeException | Error ended) {
                    interruptStatusAfterFailure.set(Thread.currentThread().isInterrupted());
                    failure.set(ended);
                }
            });
            thread.start();
        }

        /** Spins on the thread state, the transport suite's idiom for "the thread is parked". */
        private void awaitParked() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(thread.getState()).as("the writer parks at the bound").isEqualTo(Thread.State.WAITING);
        }

        private boolean finishedWithin(long millis) throws InterruptedException {
            thread.join(millis);
            return !thread.isAlive();
        }

        /** How the write ended: {@code null} when it returned, otherwise its failure. */
        private Throwable outcome() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertThat(thread.isAlive()).as("the writer was released").isFalse();
            return failure.get();
        }
    }

    /**
     * The peer and the drain: reads the peer's socket, checking the pattern, and flushes the stream
     * after every turn, standing in for the reactor.
     */
    private static final class Pump implements AutoCloseable {

        private final AtomicLong received = new AtomicLong();
        private final AtomicReference<String> problem = new AtomicReference<>();
        private final AtomicBoolean stop = new AtomicBoolean();
        private final Thread thread;

        private Pump(Rig rig, long expectedTotal, boolean drainPeer) {
            thread = Thread.ofPlatform().daemon().unstarted(() -> run(rig, expectedTotal, drainPeer));
            thread.start();
        }

        private void run(Rig rig, long expectedTotal, boolean drainPeer) {
            ByteBuffer buffer = ByteBuffer.allocate(FILL_BLOCK);
            while (!stop.get() && received.get() < expectedTotal) {
                try {
                    buffer.clear();
                    int read = drainPeer ? rig.serverChannel.read(buffer) : 0;
                    for (int i = 0; i < read; i++) {
                        if (buffer.get(i) != pattern(received.get() + i)) {
                            problem.compareAndSet(null, "byte " + (received.get() + i) + " is out of order");
                        }
                    }
                    received.addAndGet(Math.max(read, 0));
                    rig.stream.flushPendingWrites();
                    if (read < 0) {
                        return;
                    }
                } catch (TransportStreamClosedException _) {
                    stop.set(true);
                } catch (IOException | RuntimeException failure) {
                    problem.compareAndSet(null, "the pump failed: " + failure);
                    return;
                }
                Thread.onSpinWait();
            }
        }

        /** Waits for the peer to have read everything the case expects. */
        private void awaitReceived() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertThat(thread.isAlive()).as("the peer read everything the case sent").isFalse();
        }

        /** Waits for a flush to meet the stream's end. */
        private void awaitEnded() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertThat(thread.isAlive()).as("a flush met the end of the stream").isFalse();
        }

        private String problem() {
            return problem.get();
        }

        @Override
        public void close() throws InterruptedException {
            stop.set(true);
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
    }
}
