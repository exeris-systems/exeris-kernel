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
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NativeTcpStream} turns a TLS handshake that ended in {@code CLOSED} into a
 * {@link TlsHandshakeException} naming its cause, at every path a caller reaches afterwards.
 *
 * <p>The engine is a real {@link CommunityTlsEngine} over {@link ScriptedOpenSsl}, so each case
 * chooses the handshake outcome. The socket pair is real; no byte crosses it. The close callback
 * records whether the failure was already recorded when the stream closed.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpStream — a TLS handshake failure reaches the caller with its cause")
class NativeTcpStreamTlsFailureTest {

    private static final long X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT = 18L;
    private static final long X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY = 20L;
    private static final long X509_V_ERR_HOSTNAME_MISMATCH = 62L;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private final AtomicReference<NativeTcpStream> streamRef = new AtomicReference<>();
    private final AtomicReference<NativeTcpTlsFailure> recordedWhenClosed = new AtomicReference<>();
    private final AtomicInteger closeCallbacks = new AtomicInteger();

    private ServerSocketChannel listener;
    private SocketChannel clientChannel;
    private SocketChannel serverChannel;
    private ScriptedOpenSsl openSsl;

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
        openSsl = new ScriptedOpenSsl();
    }

    @AfterEach
    void closeEverything() throws IOException {
        NativeTcpStream stream = streamRef.get();
        if (stream != null) {
            stream.close();
        }
        serverChannel.close();
        clientChannel.close();
        listener.close();
    }

    @Test
    @DisplayName("a writer that drives a failed verification gets its X509 code, recorded before the stream closed")
    void writerGetsTheVerificationCode() {
        openSsl.errorResult(CoreOpenSslLoader.SSL_ERROR_SSL).verifyResult(X509_V_ERR_HOSTNAME_MISMATCH);
        NativeTcpStream stream = stream(NativeTcpStreamTlsFailureTest::noPause);

        assertThatThrownBy(() -> write(stream))
                .isInstanceOf(TlsHandshakeException.class)
                .satisfies(e -> assertVerificationFailure(e, X509_V_ERR_HOSTNAME_MISMATCH));
        assertThat(closeCallbacks).hasValue(1);
        assertThat(recordedWhenClosed.get())
                .as("the failure is recorded before the stream closes")
                .isEqualTo(new NativeTcpTlsFailure(CoreOpenSslLoader.SSL_ERROR_SSL, X509_V_ERR_HOSTNAME_MISMATCH));
    }

    @Test
    @DisplayName("a failed step with nothing wrong in verification reports its SSL_get_error code")
    void failureWithoutVerificationGetsTheSslError() {
        openSsl.errorResult(CoreOpenSslLoader.SSL_ERROR_SYSCALL).verifyResult(CoreOpenSslLoader.X509_V_OK);
        NativeTcpStream stream = stream(NativeTcpStreamTlsFailureTest::noPause);

        assertThatThrownBy(() -> write(stream))
                .isInstanceOf(TlsHandshakeException.class)
                .satisfies(e -> assertThat(((TlsHandshakeException) e).rawArgs())
                        .containsExactly(CoreOpenSslLoader.SSL_ERROR_SYSCALL, TlsFailureDetail.HANDSHAKE_FAILED));
    }

    @Test
    @DisplayName("a writer waiting between steps gets the failure another thread's step recorded")
    void waitingWriterGetsTheFailureAnotherThreadRecorded() {
        openSsl.errorResult(CoreOpenSslLoader.SSL_ERROR_WANT_READ);
        AtomicBoolean reactorRan = new AtomicBoolean();
        NativeTcpStream stream = stream(() -> {
            if (reactorRan.compareAndSet(false, true)) {
                openSsl.errorResult(CoreOpenSslLoader.SSL_ERROR_SSL)
                        .verifyResult(X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY);
                runOnAnotherThread(() -> streamRef.get().readTlsIngressFromFd());
            }
        });

        assertThatThrownBy(() -> write(stream))
                .isInstanceOf(TlsHandshakeException.class)
                .satisfies(e -> assertVerificationFailure(e, X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY));
        assertThat(reactorRan).isTrue();
        assertThat(openSsl.connectCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("write() after the failure throws it")
    void writeAfterTheFailureThrowsIt() {
        NativeTcpStream stream = failedOnTheReactor();

        assertThatThrownBy(() -> write(stream))
                .isInstanceOf(TlsHandshakeException.class)
                .satisfies(e -> assertVerificationFailure(e, X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT));
    }

    @Test
    @DisplayName("queueWrite() after the failure throws it and leaves the buffer with the caller")
    void queueWriteAfterTheFailureThrowsIt() {
        NativeTcpStream stream = failedOnTheReactor();

        try (LoanedBuffer buffer = ALLOCATOR.allocateNetwork(8)) {
            assertThatThrownBy(() -> stream.queueWrite(buffer, 1))
                    .isInstanceOf(TlsHandshakeException.class)
                    .satisfies(e -> assertVerificationFailure(e, X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT));
        }
    }

    @Test
    @DisplayName("read() after the failure throws it")
    void readAfterTheFailureThrowsIt() {
        NativeTcpStream stream = failedOnTheReactor();

        try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(8)) {
            assertThatThrownBy(() -> stream.read(sink.segment(), 8))
                    .isInstanceOf(TlsHandshakeException.class)
                    .satisfies(e -> assertVerificationFailure(e, X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT));
        }
    }

    @Test
    @DisplayName("read() throws the failure, not end-of-stream, when the peer has closed and the stream has not finished closing")
    void readThrowsTheFailureRatherThanEndOfStream() throws ReflectiveOperationException {
        NativeTcpStream stream = stream(NativeTcpStreamTlsFailureTest::noPause);
        AtomicReference<Thread> outboundSlot = outboundConsumerSlot(stream);
        outboundSlot.set(new Thread(() -> { }));
        try {
            failOnTheReactor(stream);
            stream.markRemoteClosed();
            assertThat(closeCallbacks).as("the stream has not finished closing").hasValue(0);

            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(8)) {
                assertThatThrownBy(() -> stream.read(sink.segment(), 8))
                        .isInstanceOf(TlsHandshakeException.class)
                        .satisfies(e -> assertVerificationFailure(e, X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT));
            }
        } finally {
            outboundSlot.set(null);
        }
    }

    @Test
    @DisplayName("no handshake step reaches the engine once a failure is recorded")
    void noStepRunsAfterTheFailureIsRecorded() throws ReflectiveOperationException {
        NativeTcpStream stream = stream(NativeTcpStreamTlsFailureTest::noPause);
        AtomicReference<Thread> outboundSlot = outboundConsumerSlot(stream);
        outboundSlot.set(new Thread(() -> { }));
        try {
            failOnTheReactor(stream);
            int stepsAtFailure = openSsl.connectCalls();

            assertThatCode(stream::readTlsIngressFromFd).doesNotThrowAnyException();

            assertThat(openSsl.connectCalls()).isEqualTo(stepsAtFailure);
            assertThat(stream.recordedTlsFailure())
                    .isEqualTo(new NativeTcpTlsFailure(CoreOpenSslLoader.SSL_ERROR_SSL,
                            X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT));
        } finally {
            outboundSlot.set(null);
        }
    }

    private NativeTcpStream failedOnTheReactor() {
        NativeTcpStream stream = stream(NativeTcpStreamTlsFailureTest::noPause);
        failOnTheReactor(stream);
        return stream;
    }

    private void failOnTheReactor(NativeTcpStream stream) {
        openSsl.errorResult(CoreOpenSslLoader.SSL_ERROR_SSL).verifyResult(X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT);
        assertThat(stream.readTlsIngressFromFd()).isNull();
        assertThat(stream.recordedTlsFailure()).isNotNull();
    }

    private NativeTcpStream stream(NativeTcpHandshakeBackoff backoff) {
        CommunityTlsEngine engine =
                CommunityTlsEngineTestAccess.scriptedClient(openSsl, ALLOCATOR, TlsPeerIdentity.of("localhost"));
        NativeTcpConnection connection = new NativeTcpConnection(1L, "localhost", 443);
        NativeTcpStream stream = new NativeTcpStream(
                "test-engine",
                1L,
                clientChannel,
                connection,
                ALLOCATOR,
                engine,
                () -> { },
                () -> {
                    closeCallbacks.incrementAndGet();
                    recordedWhenClosed.set(streamRef.get().recordedTlsFailure());
                },
                null,
                backoff);
        streamRef.set(stream);
        connection.bindSingleStream(stream);
        stream.markTlsBoundFromCarrier();
        return stream;
    }

    private static void write(NativeTcpStream stream) {
        byte[] payload = {1};
        stream.write(MemorySegment.ofArray(payload), payload.length);
    }

    private static void assertVerificationFailure(Throwable thrown, long x509Code) {
        TlsHandshakeException exception = (TlsHandshakeException) thrown;
        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_2001);
        assertThat(exception.rawArgs())
                .containsExactly((int) x509Code, TlsFailureDetail.PEER_VERIFICATION_FAILED);
    }

    private static void noPause() {
        // each step follows the last at once
    }

    private static void runOnAnotherThread(Runnable step) {
        Thread thread = Thread.ofPlatform().start(step);
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        assertThat(thread.isAlive()).as("the other thread's step finished").isFalse();
    }

    /**
     * The stream's outbound-consumer slot. Holding it with a thread that never yields keeps
     * {@link NativeTcpStream#close()} from finishing, which is the window between a recorded failure
     * and a closed stream.
     */
    @SuppressWarnings("unchecked")
    private static AtomicReference<Thread> outboundConsumerSlot(NativeTcpStream stream)
            throws ReflectiveOperationException {
        Field runtimeField = NativeTcpStream.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        Object runtime = runtimeField.get(stream);
        Field slot = runtime.getClass().getDeclaredField("outboundConsumer");
        slot.setAccessible(true);
        return (AtomicReference<Thread>) slot.get(runtime);
    }
}
