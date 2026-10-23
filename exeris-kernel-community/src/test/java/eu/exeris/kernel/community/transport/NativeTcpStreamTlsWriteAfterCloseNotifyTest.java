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
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * A write to a TLS stream whose peer ended the session is refused with the closed-stream signal for
 * the peer ({@code EX-NET-4008}), not attempted and not reported as a handshake failure.
 *
 * <p>The engine is a real {@link CommunityTlsEngine} over {@link ScriptedOpenSsl}: the script decides
 * how the session ends and whether a write stalls first, and records every {@code SSL_write} so a case
 * can show none was attempted. The socket pair is real; no byte crosses it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpStream: a TLS write after the peer ended the session")
class NativeTcpStreamTlsWriteAfterCloseNotifyTest {

    private static final byte[] RECORD = {'p', 'o', 'n', 'g'};
    private static final int PAYLOAD_BYTES = 64;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private final AtomicInteger closeCalls = new AtomicInteger();
    private final AtomicInteger interestCalls = new AtomicInteger();

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
    }

    @AfterEach
    void closeEverything() throws IOException {
        if (stream != null) {
            stream.close();
        }
        serverChannel.close();
        clientChannel.close();
        listener.close();
    }

    static Stream<Arguments> endsOfTheSession() {
        return Stream.of(
                Arguments.of("close_notify, write", CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN, false),
                Arguments.of("close_notify, queueWrite", CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN, true),
                Arguments.of("fatal read, write", CoreOpenSslLoader.SSL_ERROR_SYSCALL, false),
                Arguments.of("fatal read, queueWrite", CoreOpenSslLoader.SSL_ERROR_SYSCALL, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endsOfTheSession")
    @DisplayName("a send after the peer's end reports the peer code over a send failure, and attempts no SSL_write")
    void sendAfterThePeersEndReportsThePeerCode(String label, int endOfSession, boolean queued) {
        openSsl.errorResult(endOfSession);
        stream = stream();
        drainLikeTheReactor(stream);
        assertThat(stream.isRemoteClosed()).as("the engine reported the peer's end").isTrue();
        LoanedBuffer payload = payload();

        Throwable failure = failureOf(queued
                ? () -> stream.queueWrite(payload, PAYLOAD_BYTES)
                : () -> stream.write(payload.segment(), PAYLOAD_BYTES));

        assertPeerCodeOverASendFailure(failure);
        assertThat(openSsl.writeCalls()).as("the engine refuses a wrap after the peer's end").isEmpty();
        assertThat(stream.hasPendingData()).isFalse();
        if (queued) {
            assertThat(payload.isAlive()).as("the abandoned write released its loan").isFalse();
        }
        assertThat(closeCalls).as("the stream finished closing once").hasValue(1);
        payload.close();
    }

    @Test
    @DisplayName("what the peer sent before its close_notify stays readable after the write is refused")
    void recordSentBeforeTheCloseNotifyStaysReadable() {
        openSsl.readRecords(RECORD).errorResult(CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN);
        stream = stream();
        drainLikeTheReactor(stream);

        try (LoanedBuffer payload = payload()) {
            assertPeerCodeOverASendFailure(failureOf(() -> stream.queueWrite(payload, PAYLOAD_BYTES)));
        }

        try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(RECORD.length)) {
            assertThat(stream.read(sink.segment(), RECORD.length)).isEqualTo(RECORD.length);
            assertThat(sink.segment().asSlice(0, RECORD.length).toArray(ValueLayout.JAVA_BYTE))
                    .containsExactly(RECORD);
            assertThat(stream.read(sink.segment(), RECORD.length)).as("after the record").isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("a session the engine ended before the reactor recorded it is still the peer's end, and gets recorded")
    void sessionEndedBeforeTheReactorRecordedItReportsThePeerCode() {
        openSsl.acceptWrite().errorResult(CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN);
        stream = stream();
        try (LoanedBuffer first = payload()) {
            stream.queueWrite(first, PAYLOAD_BYTES);
        }
        try (LoanedBuffer ciphertext = ALLOCATOR.allocateNetwork(1);
             LoanedBuffer plaintext = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES)) {
            assertThat(stream.tlsEngine().unwrap(ciphertext, plaintext)).isEqualTo(TlsStatus.CLOSED);
        }
        assertThat(stream.isRemoteClosed()).as("the reactor has not recorded the end yet").isFalse();

        try (LoanedBuffer payload = payload()) {
            Throwable failure = failureOf(() -> stream.write(payload.segment(), PAYLOAD_BYTES));

            assertPeerCodeOverASendFailure(failure);
        }
        assertThat(stream.isRemoteClosed()).as("the refusal recorded the peer's end").isTrue();
        assertThat(openSsl.writeCalls()).as("only the write before the end reached SSL_write").hasSize(1);
    }

    @Test
    @DisplayName("a write stalled on a full send buffer when the peer's close_notify arrives is dropped and ends the stream")
    void writeStalledOnSocketSpaceIsDroppedAtTheCloseNotify() throws Exception {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE).errorResult(CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN);
        stream = stream();
        LoanedBuffer payload = payload();
        stream.queueWrite(payload, PAYLOAD_BYTES);
        assertThat(stream.hasPendingData()).as("the write stalled").isTrue();
        drainLikeTheReactor(stream);
        assertThat(stream.isRemoteClosed()).isTrue();
        NativeTcpCarrier carrier = carrier();
        try (Selector selector = Selector.open()) {
            SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            assertThatCode(() -> carrier.flushStream(stream, key))
                    .as("a stall that meets the peer's end is not a dispatch fault")
                    .doesNotThrowAnyException();
        } finally {
            carrier.close();
        }

        assertThat(openSsl.writeCalls()).as("the stalled head is not retried").hasSize(1);
        assertThat(stream.hasPendingData()).isFalse();
        assertThat(payload.isAlive()).as("the dropped write released its loan").isFalse();
        assertThat(closeCalls).as("the stream finished closing once").hasValue(1);
        assertNextWriteReportsThePeerCode();
    }

    @Test
    @DisplayName("a write that waits for the peer when its close_notify arrives is dropped by the ingress flush")
    void writeStalledOnThePeerIsDroppedAtTheCloseNotify() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_READ).errorResult(CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN);
        stream = stream();
        LoanedBuffer payload = payload();
        stream.queueWrite(payload, PAYLOAD_BYTES);
        assertThat(stream.stalledOnRead()).as("the write waits for the peer").isTrue();
        drainLikeTheReactor(stream);
        assertThat(stream.isRemoteClosed()).isTrue();

        assertThatCode(stream::flushPendingAfterIngress)
                .as("the ingress flush that meets the peer's end has nothing to report")
                .doesNotThrowAnyException();

        assertThat(stream.hasPendingData()).isFalse();
        assertThat(payload.isAlive()).isFalse();
        assertThat(closeCalls).hasValue(1);
        assertNextWriteReportsThePeerCode();
    }

    @Test
    @DisplayName("a fatal wrap status after the end was observed reports the peer code, not a send failure")
    void fatalWrapAfterTheObservedEndReportsThePeerCode() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_SYSCALL);
        stream = stream();
        try (LoanedBuffer ready = payload()) {
            // The engine is still ACTIVE: only the stream has recorded the peer's end.
            stream.markRemoteClosed();
            assertPeerCodeOverASendFailure(failureOf(() -> stream.queueWrite(ready, PAYLOAD_BYTES)));
        }
    }

    @Test
    @DisplayName("a fatal wrap status with the peer's end not observed stays a send failure")
    void fatalWrapWithoutAnObservedEndStaysASendFailure() {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_SYSCALL);
        stream = stream();
        try (LoanedBuffer payload = payload()) {
            assertThatThrownBy(() -> stream.queueWrite(payload, PAYLOAD_BYTES))
                    .isInstanceOfSatisfying(TransportException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002));
        }
    }

    private void assertNextWriteReportsThePeerCode() {
        try (LoanedBuffer next = payload()) {
            assertThatThrownBy(() -> stream.write(next.segment(), PAYLOAD_BYTES))
                    .isInstanceOfSatisfying(TransportStreamClosedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        }
    }

    /** The peer code, over a 4002 transport failure, and no handshake failure anywhere in the chain. */
    private static void assertPeerCodeOverASendFailure(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(TransportStreamClosedException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008);
            assertThat(e.isPeerClosed()).isTrue();
        });
        assertThat(failure.getCause()).isInstanceOfSatisfying(TransportException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002));
        for (Throwable link = failure; link != null; link = link.getCause()) {
            assertThat(link).as("no handshake failure in the chain").isNotInstanceOf(TlsHandshakeException.class);
        }
    }

    private static Throwable failureOf(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        return fail("the send was not refused");
    }

    /** One readable event as the carrier handles it: every record the engine yields, then stop. */
    private static void drainLikeTheReactor(NativeTcpStream stream) {
        LoanedBuffer record = stream.readTlsIngressFromFd();
        while (record != null) {
            stream.offerIngress(record);
            record = stream.readTlsIngressFromFd();
        }
    }

    private static NativeTcpCarrier carrier() {
        return new NativeTcpCarrier(TransportConfig.serverDefaults(8443), ALLOCATOR, null, null,
                NativeTcpClientTls.none());
    }

    private static LoanedBuffer payload() {
        LoanedBuffer buffer = ALLOCATOR.allocateNetwork(PAYLOAD_BYTES);
        buffer.setSize(PAYLOAD_BYTES);
        return buffer;
    }

    private NativeTcpStream stream() {
        CommunityTlsEngine engine =
                CommunityTlsEngineTestAccess.scriptedClient(openSsl, ALLOCATOR, TlsPeerIdentity.of("localhost"));
        NativeTcpConnection connection = new NativeTcpConnection(1L, "localhost", 443);
        NativeTcpStream created = new NativeTcpStream("test-engine", 1L, clientChannel, connection, ALLOCATOR,
                engine, interestCalls::incrementAndGet, closeCalls::incrementAndGet, null,
                NativeTcpStreamTlsWriteAfterCloseNotifyTest::noPause);
        connection.bindSingleStream(created);
        created.markTlsBoundFromCarrier();
        return created;
    }

    private static void noPause() {
        // each handshake step follows the last at once
    }
}
