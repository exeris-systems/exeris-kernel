/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.transport.syscall.CoreSyscallLoader;
import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/**
 * A send on a plain TCP {@link NativeTcpStream} to a peer that ended the connection reports the
 * closed-stream signal for the peer ({@code EX-NET-4008}) with the transport failure as its cause,
 * and a send that fails for any other reason stays {@code EX-NET-4002}.
 *
 * <p>The peer "goes" by closing with {@code SO_LINGER} 0, which sends a reset. The stream is built on
 * a real loopback socket pair with no reactor; each case writes until the first throw, within a
 * bounded attempt count, because the reset reaches the stream's socket asynchronously.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpStream: a plain send to a peer that ended the connection")
class NativeTcpStreamPeerGoneSendTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final int PAYLOAD_BYTES = 16;
    private static final int MAX_ATTEMPTS = 200_000;
    private static final long AWAIT_MILLIS = 5_000L;

    private final AtomicInteger closeCalls = new AtomicInteger();
    private final AtomicInteger interestCalls = new AtomicInteger();

    private Arena arena;
    private ServerSocketChannel listener;
    private SocketChannel clientChannel;
    private SocketChannel peerChannel;
    private NativeTcpStream stream;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @BeforeEach
    void connectLoopbackPair() throws IOException {
        arena = Arena.ofShared();
        listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        clientChannel = SocketChannel.open(listener.getLocalAddress());
        peerChannel = listener.accept();
        clientChannel.configureBlocking(false);
    }

    @AfterEach
    void closeEverything() throws IOException {
        if (stream != null) {
            stream.close();
        }
        peerChannel.close();
        clientChannel.close();
        listener.close();
        arena.close();
    }

    @Test
    @DisplayName("a queued write after the end was observed is refused with the peer code, the transport failure as cause")
    void queuedWriteAfterTheObservedEndReportsThePeerCode() throws IOException {
        stream = nioStream();
        stream.markRemoteClosed();
        resetFromThePeer();

        LoanedBuffer last = null;
        Throwable first = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS && first == null; attempt++) {
            last = payload();
            try {
                stream.queueWrite(last, PAYLOAD_BYTES);
            } catch (RuntimeException failure) {
                first = failure;
            }
        }

        assertThat(first).isInstanceOfSatisfying(TransportStreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        assertSendFailureUnderTheSignal(first);
        assertThat(stream.hasPendingData()).as("the queue is gone, not stuck").isFalse();
        assertThat(last.isAlive()).as("the abandoned write released its loan").isFalse();
        assertThat(closeCalls).as("the stream finished closing once").hasValue(1);
        Thread other = Thread.ofPlatform().unstarted(() -> { });
        assertThat(stream.tryPinOutboundConsumerForTest(other)).as("the slot is free after the abort").isTrue();
        stream.unpinOutboundConsumerForTest(other);
    }

    @Test
    @DisplayName("on the NIO fallback, a queued write after a reset reports the peer code with no end observed")
    void nioQueuedWriteAfterAResetReportsThePeerCode() throws IOException {
        stream = nioStream();
        resetFromThePeer();

        Throwable first = firstFailure(() -> stream.queueWrite(payload(), PAYLOAD_BYTES));

        assertThat(first).isInstanceOfSatisfying(TransportStreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        assertSendFailureUnderTheSignal(first);
        assertThat(stream.isRemoteClosed()).as("the stream recorded the peer's end").isTrue();
        assertThat(stream.hasPendingData()).isFalse();
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    @DisplayName("on the NIO fallback, a queued write on a channel closed locally stays a send failure")
    void nioQueuedWriteOnALocallyClosedChannelStaysASendFailure() throws IOException {
        stream = nioStream();
        clientChannel.close();

        Throwable first = firstFailure(() -> stream.queueWrite(payload(), PAYLOAD_BYTES));

        assertThat(first).isInstanceOfSatisfying(TransportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002));
        assertThat(first.getCause()).isInstanceOf(ClosedChannelException.class);
        assertThat(stream.isRemoteClosed()).isFalse();
    }

    @Test
    @DisplayName("on the core-socket seam, the send's errno alone says the peer is gone, and it is named in the cause")
    void seamErrnoAloneReportsThePeerCode() throws IOException {
        stream = seamStream();
        assertThat(stream.plainSocketBackendName()).as("the seam backend is selected").isEqualTo("core-socket-seam");
        resetFromThePeer();

        Throwable first = firstFailure(() -> stream.queueWrite(payload(), PAYLOAD_BYTES));

        assertThat(first).isInstanceOfSatisfying(TransportStreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        assertSendFailureUnderTheSignal(first);
        Throwable bottom = first.getCause().getCause();
        assertThat(bottom).isInstanceOf(NativeTcpPeerResetException.class);
        assertThat(bottom.getMessage()).containsPattern("errno=(104|32)\\b");
        assertThat(stream.isRemoteClosed()).as("the stream recorded the peer's end").isTrue();
    }

    @Test
    @DisplayName("the side that ended the stream first decides: a local close before the peer's end reports EX-NET-4009")
    void localCloseBeforeThePeersEndKeepsTheLocalCode() throws Exception {
        stream = nioStream();
        Thread holder = Thread.ofPlatform().unstarted(() -> { });
        assertThat(stream.tryPinOutboundConsumerForTest(holder)).isTrue();
        LoanedBuffer queued = payload();
        stream.queueWrite(queued, PAYLOAD_BYTES);
        assertThat(stream.hasPendingData()).as("the slot is held, so the write stays queued").isTrue();
        stream.close();
        stream.markRemoteClosed();
        resetFromThePeer();
        stream.unpinOutboundConsumerForTest(holder);

        Throwable drainFailure = firstFailure(stream::flushPendingWrites);

        assertThat(drainFailure).isInstanceOfSatisfying(TransportStreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
        assertSendFailureUnderTheSignal(drainFailure);
        assertThat(stream.hasPendingData()).isFalse();
        assertThat(queued.isAlive()).isFalse();
        try (LoanedBuffer next = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES)) {
            assertThat(catchFailure(() -> stream.write(next.segment(), PAYLOAD_BYTES)))
                    .isInstanceOfSatisfying(TransportStreamClosedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
        }
    }

    @Test
    @DisplayName("a direct write after the end was observed reports the peer code and leaves the close to the application")
    void directWriteAfterTheObservedEndReportsThePeerCode() throws IOException {
        stream = nioStream();
        stream.markRemoteClosed();
        resetFromThePeer();

        Throwable first = writeUntilFailure();

        assertThat(first).isInstanceOfSatisfying(TransportStreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        assertSendFailureUnderTheSignal(first);
        assertThat(stream.isClosed()).as("no teardown on the direct path").isFalse();
        assertThat(closeCalls).hasValue(0);
    }

    @Test
    @DisplayName("on the NIO fallback, a direct write after a reset reports the peer code with no end observed")
    void nioDirectWriteAfterAResetReportsThePeerCode() throws IOException {
        stream = nioStream();
        resetFromThePeer();

        Throwable first = writeUntilFailure();

        assertThat(first).isInstanceOfSatisfying(TransportStreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        assertSendFailureUnderTheSignal(first);
        assertThat(stream.isRemoteClosed()).isTrue();
        assertThat(stream.isClosed()).as("no teardown on the direct path").isFalse();
    }

    @Test
    @DisplayName("on the NIO fallback, a direct write on a channel closed locally stays a send failure")
    void nioDirectWriteOnALocallyClosedChannelStaysASendFailure() throws IOException {
        stream = nioStream();
        clientChannel.close();

        Throwable first = writeUntilFailure();

        assertThat(first).isInstanceOfSatisfying(TransportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002));
        assertThat(first.getCause()).isInstanceOf(ClosedChannelException.class);
    }

    @Test
    @DisplayName("a peer FIN is a half-close: a write after it is still sent and the peer reads it")
    void writeAfterThePeersFinIsStillSent() throws IOException {
        stream = nioStream();
        peerChannel.shutdownOutput();
        stream.markRemoteClosed();
        byte[] sent = new byte[PAYLOAD_BYTES];
        try (LoanedBuffer source = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES)) {
            for (int i = 0; i < PAYLOAD_BYTES; i++) {
                sent[i] = (byte) (i + 1);
                source.segment().set(ValueLayout.JAVA_BYTE, i, sent[i]);
            }
            assertThatCode(() -> stream.write(source.segment(), PAYLOAD_BYTES)).doesNotThrowAnyException();
        }

        ByteBuffer received = ByteBuffer.allocate(PAYLOAD_BYTES);
        while (received.hasRemaining()) {
            assertThat(peerChannel.read(received)).as("the peer reads the bytes").isNotNegative();
        }
        assertThat(received.array()).containsExactly(sent);
    }

    @Test
    @DisplayName("the reactor's flush that meets the peer's end neither throws nor leaves the stream open, and the writer is told")
    void reactorFlushThatMeetsThePeersEndIsSwallowed() throws Exception {
        stream = nioStream();
        Thread holder = Thread.ofPlatform().unstarted(() -> { });
        assertThat(stream.tryPinOutboundConsumerForTest(holder)).isTrue();
        stream.queueWrite(payload(), PAYLOAD_BYTES);
        assertThat(stream.hasPendingData()).isTrue();
        stream.unpinOutboundConsumerForTest(holder);
        stream.markRemoteClosed();
        resetFromThePeer();
        NativeTcpCarrier carrier = new NativeTcpCarrier(TransportConfig.serverDefaults(8443), ALLOCATOR, null, null,
                NativeTcpClientTls.none());
        try (Selector selector = Selector.open()) {
            SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            assertThatCode(() -> carrier.flushStream(stream, key))
                    .as("a peer-gone flush is not a dispatch fault")
                    .doesNotThrowAnyException();
        } finally {
            carrier.close();
        }

        assertThat(stream.hasPendingData()).isFalse();
        assertThat(closeCalls).as("the stream finished its own close once").hasValue(1);
        try (LoanedBuffer next = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES)) {
            assertThat(catchFailure(() -> stream.write(next.segment(), PAYLOAD_BYTES)))
                    .isInstanceOfSatisfying(TransportStreamClosedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        }
    }

    /** The chain under a send-failure signal: the 4002 transport failure over an I/O failure. */
    private static void assertSendFailureUnderTheSignal(Throwable signal) {
        assertThat(signal.getCause()).isInstanceOfSatisfying(TransportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002));
        assertThat(signal.getCause().getCause()).isInstanceOf(IOException.class);
    }

    private Throwable writeUntilFailure() {
        try (LoanedBuffer source = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES)) {
            return firstFailure(() -> stream.write(source.segment(), PAYLOAD_BYTES));
        }
    }

    /** Runs the action until it throws, within the attempt bound; fails the case if it never does. */
    private static Throwable firstFailure(Runnable action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Throwable failure = catchFailure(action);
            if (failure != null) {
                return failure;
            }
        }
        return fail("the send was never refused within " + MAX_ATTEMPTS + " attempts");
    }

    private static Throwable catchFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    /**
     * The peer closes with {@code SO_LINGER} 0, so the stream's socket receives a reset. Returns once the
     * socket is readable, which is how the reset shows, so every later send meets it.
     */
    private void resetFromThePeer() throws IOException {
        peerChannel.setOption(StandardSocketOptions.SO_LINGER, 0);
        peerChannel.close();
        try (Selector selector = Selector.open()) {
            clientChannel.register(selector, SelectionKey.OP_READ);
            assertThat(selector.select(AWAIT_MILLIS)).as("the reset reached the stream's socket").isEqualTo(1);
        }
    }

    private static LoanedBuffer payload() {
        LoanedBuffer buffer = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES);
        buffer.setSize(PAYLOAD_BYTES);
        return buffer;
    }

    private NativeTcpStream nioStream() {
        return newStream(null);
    }

    private NativeTcpStream seamStream() {
        SyscallHandles handles = CoreSyscallLoader.load(arena);
        return newStream(handles);
    }

    private NativeTcpStream newStream(SyscallHandles handles) {
        NativeTcpConnection connection = new NativeTcpConnection(1L, "localhost", 80);
        NativeTcpStream created = new NativeTcpStream("test-engine", 1L, clientChannel, connection, ALLOCATOR,
                null, interestCalls::incrementAndGet, closeCalls::incrementAndGet, handles);
        connection.bindSingleStream(created);
        return created;
    }
}
