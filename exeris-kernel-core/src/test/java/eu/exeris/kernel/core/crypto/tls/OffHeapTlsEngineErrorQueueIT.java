/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.ArenaLoanedBuffer;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoaderTestHelper;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a real handshake failure, the thread that drove it has an empty OpenSSL error queue.
 *
 * <p>The assertion reads the queue with {@code ERR_peek_error} on the same OS thread, so it holds
 * on every OpenSSL major the loader accepts — including 4.x, whose {@code SSL_get_error} does not
 * let a stale entry turn a neighbour's retry into a fatal error. A missing clear leaves the entry
 * that the failure pushed, and this test reads it.
 *
 * <p>Fails, rather than skips, when OpenSSL cannot be loaded.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("IT: OffHeapTlsEngine — a failed handshake leaves the thread's error queue empty")
class OffHeapTlsEngineErrorQueueIT {

    private static final Arena ARENA = Arena.ofShared(); //NOPMD DirectArena — test harness only

    private static final MemoryAllocator ALLOC = new MemoryAllocator() {
        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return ArenaLoanedBuffer.allocate(ARENA, hint, 16_384);
        }

        @Override public LoanedBuffer allocateNetwork(int bytes)         { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateCarrierSlab(int index)     { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateInfrastructure(long bytes) { return allocate(AllocationHint.MEDIUM); }
        @Override public MemoryStats stats()                             { return MemoryStats.zero(); }
        @Override public void close()                                    { /* arena lives for the class */ }
    };

    private static CoreSslHandles handles;
    private static MethodHandle sslSetFd;
    private static MethodHandle errPeekError;

    @BeforeAll
    static void loadOpenSsl() {
        CoreOpenSslRuntime runtime = CoreOpenSslLoader.load(Arena.global());
        handles = runtime.handles();
        sslSetFd = CoreOpenSslLoaderTestHelper.resolveSslSetFd(runtime);
        assertThat(sslSetFd).as("SSL_set_fd must resolve from the loaded libssl").isNotNull();
        errPeekError = runtime.requiredCryptoHandle("ERR_peek_error", FunctionDescriptor.of(JAVA_LONG));
    }

    @Test
    @DisplayName("a server handshake refused on plaintext bytes leaves the queue empty")
    void serverRefusingPlaintextLeavesQueueEmpty(@TempDir Path dir) throws Exception {
        TlsTestPki.Issued server = TlsTestPki.leaf(dir, "server", null, "exeris-core-it-server",
                TlsTestPki.ip("127.0.0.1"));
        long serverCtx = TlsTestSslContexts.server(handles, server);
        try (SocketPair pair = SocketPair.open();
             OffHeapTlsEngine engine = new OffHeapTlsEngine(handles, serverCtx, true, ALLOC);
             LoanedBuffer out = ALLOC.allocate(AllocationHint.MEDIUM)) {
            pair.client().write(ByteBuffer.wrap(
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
            bind(engine, pair.serverFd());
            handles.errorQueue().invokeClearError();

            assertThat(engine.beginHandshake(out)).isEqualTo(TlsStatus.CLOSED);

            assertThat(peekError())
                    .as("ERR_peek_error on the thread that ran the failed SSL_accept")
                    .isZero();
        } finally {
            handles.ctx().invokeCtxFree(serverCtx);
        }
    }

    @Test
    @DisplayName("a client handshake refused on a non-TLS reply leaves the queue empty")
    void clientRefusingNonTlsReplyLeavesQueueEmpty() throws Exception {
        long clientCtx = TlsTestSslContexts.client(handles, CoreOpenSslLoader.SSL_VERIFY_NONE);
        try (SocketPair pair = SocketPair.open();
             OffHeapTlsEngine engine = new OffHeapTlsEngine(handles, clientCtx, false, ALLOC);
             LoanedBuffer out = ALLOC.allocate(AllocationHint.MEDIUM)) {
            pair.server().write(ByteBuffer.wrap(
                    "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
            bind(engine, pair.clientFd());
            handles.errorQueue().invokeClearError();

            assertThat(engine.beginHandshake(out)).isEqualTo(TlsStatus.CLOSED);

            assertThat(peekError())
                    .as("ERR_peek_error on the thread that ran the failed SSL_connect")
                    .isZero();
        } finally {
            handles.ctx().invokeCtxFree(clientCtx);
        }
    }

    private static void bind(OffHeapTlsEngine engine, int fd) {
        assertThat(engine.bindTransportFd(sslSetFd, fd)).isEqualTo(1);
        engine.notifyBound();
    }

    private static long peekError() {
        try {
            return (long) errPeekError.invokeExact();
        } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            throw new IllegalStateException("ERR_peek_error failed", t);
        }
    }

    /**
     * A connected loopback pair in blocking mode; OpenSSL drives the descriptors directly.
     */
    record SocketPair(ServerSocketChannel listener, SocketChannel client, SocketChannel server)
            implements AutoCloseable {

        static SocketPair open() throws java.io.IOException {
            ServerSocketChannel listener = ServerSocketChannel.open();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            SocketChannel client = SocketChannel.open(listener.getLocalAddress());
            SocketChannel server = listener.accept();
            return new SocketPair(listener, client, server);
        }

        int clientFd() {
            return TlsTestSslContexts.fd(client);
        }

        int serverFd() {
            return TlsTestSslContexts.fd(server);
        }

        @Override
        public void close() throws java.io.IOException {
            try (listener; client; server) {
                // closed in reverse declaration order
            }
        }
    }
}
