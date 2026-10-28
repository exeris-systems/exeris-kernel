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
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bounded TLS stream turns one large write into pending entries of at most half the bound, and a
 * stalled entry is retried with the identical plaintext address and length, never re-cut.
 *
 * <p>The engine is a real {@code CommunityTlsEngine} over {@link ScriptedOpenSsl}; no byte crosses
 * the socket pair.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpStream — a bounded TLS write is chunked at half the bound")
class NativeTcpStreamTlsWriteChunkingTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final int BOUND = 64 * 1024;
    private static final int CHUNK = BOUND / 2;
    private static final int PAYLOAD = 1024 * 1024;
    private static final long AWAIT_SECONDS = 10L;

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
        CommunityTlsEngine engine =
                CommunityTlsEngineTestAccess.scriptedClient(openSsl, ALLOCATOR, TlsPeerIdentity.of("localhost"));
        NativeTcpConnection connection = new NativeTcpConnection(1L, "localhost", 443);
        stream = new NativeTcpStream("test-engine", 1L, clientChannel, connection, ALLOCATOR, engine,
                () -> { }, () -> { }, null, () -> { }, BOUND);
        connection.bindSingleStream(stream);
        stream.markTlsBoundFromCarrier();
    }

    @AfterEach
    void closeEverything() throws IOException {
        stream.close();
        serverChannel.close();
        clientChannel.close();
        listener.close();
    }

    @Test
    @DisplayName("a large write queues entries of at most half the bound, and a stalled one is retried unchanged")
    void largeWriteIsChunkedAndAStalledChunkIsNotRecut() throws Exception {
        openSsl.failWrite(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE);
        for (int i = 0; i < PAYLOAD / CHUNK; i++) {
            openSsl.acceptWrite();
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = Thread.ofVirtual().unstarted(() -> {
            try (LoanedBuffer source = ALLOCATOR.allocateNetwork(PAYLOAD)) {
                stream.write(source.segment(), PAYLOAD);
            } catch (RuntimeException | Error ended) {
                failure.set(ended);
            }
        });
        writer.start();
        awaitParked(writer);

        assertThat(openSsl.writeCalls()).as("only the stalled head has reached SSL_write").hasSize(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (writer.isAlive() && System.nanoTime() < deadline) {
            stream.flushPendingWrites();
            writer.join(1);
        }
        assertThat(writer.isAlive()).as("the drain frees the writer").isFalse();
        assertThat(failure.get()).as("the write completes once the drain frees the queue").isNull();
        List<WriteCall> calls = openSsl.writeCalls();
        assertThat(calls.get(1)).as("the stalled head is offered the same address and length").isEqualTo(calls.get(0));
        assertThat(calls).isNotEmpty()
                .allSatisfy(call -> assertThat(call.length()).isLessThanOrEqualTo(CHUNK));
        assertThat(calls.stream().skip(1).mapToLong(WriteCall::length).sum())
                .as("every byte reached SSL_write once, apart from the stalled attempt")
                .isEqualTo(PAYLOAD);
        assertThat(stream.outboundQueuedBytesForTest()).isZero();
    }

    private static void awaitParked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).as("the writer parks at the bound").isEqualTo(Thread.State.WAITING);
    }
}
