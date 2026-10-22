/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityTlsEngine;
import eu.exeris.kernel.community.crypto.CommunityTlsEngineTestAccess;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.ScriptedOpenSsl;
import eu.exeris.kernel.core.crypto.openssl.ScriptedOpenSsl.WriteCall;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.time.TimeKernelProviders;
import eu.exeris.kernel.spi.time.TimeSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A TLS write that meets a full send buffer waits for space instead of ending the stream, and a
 * fatal wrap status ends it without losing the write silently or releasing a slot it does not own.
 *
 * <p>The engine is a real {@code CommunityTlsEngine} over {@link ScriptedOpenSsl}, which scripts
 * each {@code SSL_write} result and records the address and length of each call. The socket pair is
 * real; no byte crosses it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpStream — a TLS write on a full send buffer stalls instead of aborting")
class NativeTcpStreamTlsBackpressureTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final int PAYLOAD_BYTES = 64;
    private static final long AWAIT_SECONDS = 5L;
    private static final long QUIET_MILLIS = 200L;

    private final AtomicInteger interestCalls = new AtomicInteger();
    private final AtomicInteger closeCalls = new AtomicInteger();

    private ServerSocketChannel listener;
    private SocketChannel clientChannel;
    private SocketChannel serverChannel;
    private ScriptedOpenSsl openSsl;
    private NativeTcpStream stream;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @BeforeEach
    void connectLoopbackPair() throws IOException {
        listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        clientChannel = SocketChannel.open(listener.getLocalAddress());
        serverChannel = listener.accept();
        clientChannel.configureBlocking(false);
        openSsl = new ScriptedOpenSsl().connectResults(1);
        stream = newStream();
    }

    @AfterEach
    void closeEverything() throws IOException {
        NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(null);
        stream.close();
        serverChannel.close();
        clientChannel.close();
        listener.close();
    }

    @Test
    @DisplayName("a full send buffer stalls the write, arms write interest and leaves the stream and its reader alone")
    void fullSendBufferStallsWithoutAborting() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(stalled::countDown);
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE);
        ReaderRun reader = new ReaderRun(stream);
        reader.awaitParked();
        LoanedBuffer payload = payload();

        stream.queueWrite(payload, PAYLOAD_BYTES);

        assertThat(stalled.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the stall hook fired").isTrue();
        assertThat(stream.hasPendingData()).as("the write stays queued").isTrue();
        assertThat(payload.isAlive()).as("the loan stays with the queue").isTrue();
        assertThat(interestCalls.get()).as("write interest is armed").isPositive();
        assertThat(stream.stalledOnRead()).isFalse();
        assertThat(closeCalls).as("nothing finished closing").hasValue(0);
        assertThat(reader.finishedWithin(QUIET_MILLIS)).as("the reader is not woken").isFalse();
        openSsl.acceptWrite();
        assertThat(stream.flushPendingWrites()).isTrue();
    }

    @Test
    @DisplayName("the retry offers SSL_write the same address and length, and its success completes the entry")
    void retryUsesTheSameBufferAndCompletesTheEntry() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE).acceptWrite();
        LoanedBuffer payload = payload();
        stream.queueWrite(payload, PAYLOAD_BYTES);
        assertThat(openSsl.writeCalls()).hasSize(1);

        assertThat(stream.flushPendingWrites()).isTrue();

        List<WriteCall> calls = openSsl.writeCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).as("a retry with other arguments is a fatal bad-write-retry").isEqualTo(calls.get(0));
        assertThat(calls.get(0).length()).isEqualTo(PAYLOAD_BYTES);
        assertThat(stream.hasPendingData()).isFalse();
        assertThat(payload.isAlive()).as("completion releases the loan").isFalse();
    }

    @Test
    @DisplayName("a write queued behind a stalled head is sent after it, and the head is retried unchanged")
    void stalledHeadIsNeitherMergedNorRecut() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE).acceptWrite().acceptWrite();
        LoanedBuffer head = payload();
        LoanedBuffer behind = payload();
        stream.queueWrite(head, PAYLOAD_BYTES);
        stream.queueWrite(behind, PAYLOAD_BYTES);

        assertThat(stream.flushPendingWrites()).isTrue();

        List<WriteCall> calls = openSsl.writeCalls();
        assertThat(calls).hasSize(3);
        assertThat(calls.get(1)).isEqualTo(calls.get(0));
        assertThat(calls.get(2).address()).as("the next entry is a write of its own")
                .isNotEqualTo(calls.get(0).address());
        assertThat(calls.get(2).length()).isEqualTo(PAYLOAD_BYTES);
        assertThat(head.isAlive()).isFalse();
        assertThat(behind.isAlive()).isFalse();
    }

    @Test
    @DisplayName("a wrap that waits for the peer stalls without arming write interest, and the next ingress flushes")
    void needUnwrapStallsWithoutWriteInterestAndIngressFlushes() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_READ).acceptWrite();
        LoanedBuffer payload = payload();

        stream.queueWrite(payload, PAYLOAD_BYTES);

        assertThat(stream.hasPendingData()).isTrue();
        assertThat(stream.stalledOnRead()).isTrue();
        assertThat(interestCalls).as("a writable socket would not help this write").hasValue(0);
        assertThat(closeCalls).hasValue(0);

        stream.flushPendingAfterIngress();

        assertThat(stream.hasPendingData()).isFalse();
        assertThat(stream.stalledOnRead()).isFalse();
        assertThat(payload.isAlive()).isFalse();
        assertThat(openSsl.writeCalls()).hasSize(2);
        assertThat(openSsl.writeCalls().get(1)).isEqualTo(openSsl.writeCalls().get(0));
    }

    @Test
    @DisplayName("an ingress batch with no write waiting for the peer does not touch the queue")
    void ingressWithoutAStalledWriteDoesNotFlush() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE);
        stream.queueWrite(payload(), PAYLOAD_BYTES);
        int callsBefore = openSsl.writeCalls().size();

        stream.flushPendingAfterIngress();

        assertThat(openSsl.writeCalls()).hasSize(callsBefore);
        openSsl.acceptWrite();
        assertThat(stream.flushPendingWrites()).isTrue();
    }

    @Test
    @DisplayName("a fatal wrap status aborts, wakes the reader, empties the queue and reports the failure to the writer")
    void fatalWrapAbortsWithoutSilentLoss() throws Exception {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_SYSCALL);
        ReaderRun reader = new ReaderRun(stream);
        reader.awaitParked();
        LoanedBuffer payload = payload();

        assertThatThrownBy(() -> stream.queueWrite(payload, PAYLOAD_BYTES))
                .isInstanceOf(TransportException.class);

        assertThat(reader.outcome()).isInstanceOf(TransportStreamClosedException.class);
        assertThat(((TransportStreamClosedException) reader.outcome()).errorCode())
                .isEqualTo(KernelErrorCodes.EX_NET_4009);
        assertThat(stream.hasPendingData()).as("the queue is gone, not stuck").isFalse();
        assertThat(payload.isAlive()).as("the abandoned write released its loan").isFalse();
        assertThat(closeCalls).as("the stream finished closing once").hasValue(1);
        Thread other = Thread.ofPlatform().unstarted(() -> { });
        assertThat(stream.tryPinOutboundConsumerForTest(other)).as("the slot is free after the abort").isTrue();
        stream.unpinOutboundConsumerForTest(other);
    }

    @Test
    @DisplayName("a fatal wrap status under a slot another frame holds leaves that slot with its holder")
    void fatalWrapNestedUnderTheSlotKeepsTheOuterSlot() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_SYSCALL);
        Thread self = Thread.currentThread();
        assertThat(stream.tryPinOutboundConsumerForTest(self)).isTrue();
        LoanedBuffer payload = payload();

        assertThatThrownBy(() -> stream.queueWrite(payload, PAYLOAD_BYTES)).isInstanceOf(TransportException.class);

        assertThat(stream.hasPendingData()).isFalse();
        Thread other = Thread.ofPlatform().unstarted(() -> { });
        assertThat(stream.tryPinOutboundConsumerForTest(other))
                .as("a nested abort never releases the slot its caller holds").isFalse();
        stream.unpinOutboundConsumerForTest(self);
        assertThat(stream.flushPendingWrites()).isTrue();
        assertThat(closeCalls).as("the holder's release finishes the close").hasValue(1);
    }

    @Test
    @DisplayName("the reactor's flush drops write interest for a write that waits for the peer, so a writable socket cannot spin it")
    void flushStreamDropsWriteInterestWhileTheWriteWaitsForThePeer() throws Exception {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_READ).failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_READ);
        NativeTcpCarrier carrier = new NativeTcpCarrier(TransportConfig.serverDefaults(8443), ALLOCATOR, null, null,
                NativeTcpClientTls.none());
        try (Selector selector = Selector.open()) {
            stream.queueWrite(payload(), PAYLOAD_BYTES);
            SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            assertThat(key.interestOps() & SelectionKey.OP_WRITE)
                    .as("a close, a reset or a handshake step armed write interest").isNotZero();

            carrier.flushStream(stream, key);

            assertThat(stream.hasPendingData()).isTrue();
            assertThat(stream.stalledOnRead()).isTrue();
            assertThat(key.interestOps()).isEqualTo(SelectionKey.OP_READ);
            openSsl.acceptWrite();
            assertThat(stream.flushPendingWrites()).isTrue();
        } finally {
            carrier.close();
        }
    }

    @Test
    @DisplayName("an ingress batch that finds the write now waiting for socket space arms write interest")
    void ingressRearmsWriteInterestWhenTheStallChangesToSocketSpace() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_READ).failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE);
        stream.queueWrite(payload(), PAYLOAD_BYTES);
        assertThat(interestCalls).hasValue(0);

        stream.flushPendingAfterIngress();

        assertThat(stream.stalledOnRead()).isFalse();
        assertThat(stream.hasPendingData()).isTrue();
        assertThat(interestCalls.get()).as("the reactor is asked to drain on writability").isPositive();
        openSsl.acceptWrite();
        assertThat(stream.flushPendingWrites()).isTrue();
    }

    @Test
    @DisplayName("the stall on the peer is visible to an ingress read as soon as the wrap releases the TLS lock")
    void stallIsVisibleToIngressAfterTheWrapReleasesTheLock() throws Exception {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_READ).acceptWrite().readRecords(new byte[] {1});
        StalledWriter writer = StalledWriter.start(stream, payload());
        try {
            LoanedBuffer record = stream.readTlsIngressFromFd();
            boolean visibleToRead = stream.stalledOnRead();
            writer.release();
            writer.join();

            assertThat(visibleToRead).as("a record consumed by the reactor finds the stall already recorded").isTrue();
            assertThat(record).isNotNull();
            stream.offerIngress(record);
            stream.flushPendingAfterIngress();
            assertThat(stream.hasPendingData()).isFalse();
        } finally {
            writer.release();
        }
    }

    @Test
    @DisplayName("an ingress flush that finds the outbound slot held is run by the holder when it releases the slot")
    void ingressFlushLeftToTheSlotHolderRunsOnRelease() throws Exception {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_READ).acceptWrite();
        LoanedBuffer payload = payload();
        StalledWriter writer = StalledWriter.start(stream, payload);
        try {
            stream.flushPendingAfterIngress();
            assertThat(stream.hasPendingData()).as("the slot is held, so the ingress flush could not run").isTrue();

            writer.release();
            writer.join();

            assertThat(stream.hasPendingData()).as("the holder retried after releasing the slot").isFalse();
            assertThat(payload.isAlive()).isFalse();
        } finally {
            writer.release();
        }
    }

    private static LoanedBuffer payload() {
        LoanedBuffer buffer = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES);
        buffer.setSize(PAYLOAD_BYTES);
        return buffer;
    }

    private NativeTcpStream newStream() {
        CommunityTlsEngine engine =
                CommunityTlsEngineTestAccess.scriptedClient(openSsl, ALLOCATOR, TlsPeerIdentity.of("localhost"));
        NativeTcpConnection connection = new NativeTcpConnection(1L, "localhost", 443);
        NativeTcpStream created = new NativeTcpStream("test-engine", 1L, clientChannel, connection, ALLOCATOR,
                engine, interestCalls::incrementAndGet, closeCalls::incrementAndGet, null,
                NativeTcpStreamTlsBackpressureTest::noPause);
        connection.bindSingleStream(created);
        created.markTlsBoundFromCarrier();
        return created;
    }

    private static void noPause() {
        // each handshake step follows the last at once
    }

    /** A virtual thread reading the stream, with the failure its read ended with. */
    private static final class ReaderRun {

        private final AtomicReference<Throwable> outcome = new AtomicReference<>();
        private final Thread thread;

        private ReaderRun(NativeTcpStream stream) {
            thread = Thread.ofVirtual().unstarted(() -> {
                try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                    stream.read(sink.segment(), 64);
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            });
            thread.start();
        }

        /** Spins on the thread state, the transport suite's idiom for "the thread is parked". */
        private void awaitParked() {
            TimeSource time = TimeKernelProviders.timeSource();
            long deadline = time.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (thread.getState() != Thread.State.WAITING && time.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(thread.getState()).as("reader parked").isEqualTo(Thread.State.WAITING);
        }

        /** A timed wait that must run out when the reader is not woken. */
        private boolean finishedWithin(long millis) throws InterruptedException {
            thread.join(millis);
            return !thread.isAlive();
        }

        private Throwable outcome() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertThat(thread.isAlive()).as("reader was released").isFalse();
            return outcome.get();
        }
    }

    /**
     * A platform thread whose {@code queueWrite} stalls and is then held inside the stall hook, with the
     * outbound slot still taken and the TLS lock already released.
     */
    private static final class StalledWriter {

        private final CountDownLatch inHook = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private StalledWriter(NativeTcpStream stream, LoanedBuffer payload) {
            NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(this::holdInHook);
            thread = Thread.ofPlatform().unstarted(() -> {
                try {
                    stream.queueWrite(payload, PAYLOAD_BYTES);
                } catch (RuntimeException error) {
                    failure.set(error);
                }
            });
        }

        private static StalledWriter start(NativeTcpStream stream, LoanedBuffer payload) throws InterruptedException {
            StalledWriter writer = new StalledWriter(stream, payload);
            writer.thread.start();
            assertThat(writer.inHook.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the write stalled").isTrue();
            return writer;
        }

        private void holdInHook() {
            inHook.countDown();
            try {
                released.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void release() {
            released.countDown();
        }

        private void join() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertThat(thread.isAlive()).as("the writer returned").isFalse();
            assertThat(failure.get()).isNull();
        }
    }
}
