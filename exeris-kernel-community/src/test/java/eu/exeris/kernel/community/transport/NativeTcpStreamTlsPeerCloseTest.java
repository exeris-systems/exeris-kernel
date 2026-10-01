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
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link NativeTcpStream} keeps what a TLS peer sent before it closed readable after the engine has
 * reported the close.
 *
 * <p>A socket at end-of-stream stays readable, so the reactor can call
 * {@link NativeTcpStream#readTlsIngressFromFd()} again after the engine reported the close. That
 * call must not reach the engine: an engine past its inbound side throws on unwrap, and the reactor
 * resets a stream whose read throws, discarding the records still queued for the reader.
 *
 * <p>The engine is a real {@link CommunityTlsEngine} over {@link ScriptedOpenSsl}: its handshake
 * succeeds, {@code SSL_read} returns one record, and the next {@code SSL_read} ends the stream with
 * the {@code SSL_get_error} code of each case — a {@code close_notify}, or an end without one. The
 * socket pair is real; no byte crosses it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpStream — what a TLS peer sent before it closed stays readable")
class NativeTcpStreamTlsPeerCloseTest {

    private static final byte[] RECORD = {'p', 'o', 'n', 'g'};

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private ServerSocketChannel listener;
    private SocketChannel clientChannel;
    private SocketChannel serverChannel;
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

    @ParameterizedTest(name = "SSL_get_error {0} at the end of the stream")
    @ValueSource(ints = {CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN, CoreOpenSslLoader.SSL_ERROR_SYSCALL})
    @DisplayName("a read event after the engine reported the close does not reach the engine, and the reader gets the record")
    void recordSentBeforeTheCloseStaysReadable(int endOfStream) {
        ScriptedOpenSsl openSsl = new ScriptedOpenSsl()
                .connectResults(1)
                .readRecords(RECORD)
                .errorResult(endOfStream);
        stream = stream(openSsl);

        drainLikeTheReactor(stream);
        assertThat(stream.isRemoteClosed()).as("the engine reported the peer's close").isTrue();
        int readsAtClose = openSsl.readCalls();

        assertThatCode(stream::readTlsIngressFromFd)
                .as("the read event a socket at end-of-stream raises again")
                .doesNotThrowAnyException();
        assertThat(openSsl.readCalls()).as("SSL_read calls after the close").isEqualTo(readsAtClose);

        try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(RECORD.length)) {
            int read = stream.read(sink.segment(), RECORD.length);
            assertThat(read).isEqualTo(RECORD.length);
            assertThat(sink.segment().asSlice(0, read).toArray(ValueLayout.JAVA_BYTE)).containsExactly(RECORD);
            assertThat(stream.read(sink.segment(), RECORD.length)).as("after the record").isEqualTo(-1);
        }
    }

    /** One readable event as the carrier handles it: every record the engine yields, then stop. */
    private static void drainLikeTheReactor(NativeTcpStream stream) {
        LoanedBuffer record = stream.readTlsIngressFromFd();
        while (record != null) {
            stream.offerIngress(record);
            record = stream.readTlsIngressFromFd();
        }
    }

    private NativeTcpStream stream(ScriptedOpenSsl openSsl) {
        CommunityTlsEngine engine =
                CommunityTlsEngineTestAccess.scriptedClient(openSsl, ALLOCATOR, TlsPeerIdentity.of("localhost"));
        NativeTcpConnection connection = new NativeTcpConnection(1L, "localhost", 443);
        NativeTcpStream created = new NativeTcpStream(
                "test-engine",
                1L,
                clientChannel,
                connection,
                ALLOCATOR,
                engine,
                () -> { },
                () -> { },
                null,
                NativeTcpStreamTlsPeerCloseTest::noPause);
        connection.bindSingleStream(created);
        created.markTlsBoundFromCarrier();
        return created;
    }

    private static void noPause() {
        // each handshake step follows the last at once
    }
}
