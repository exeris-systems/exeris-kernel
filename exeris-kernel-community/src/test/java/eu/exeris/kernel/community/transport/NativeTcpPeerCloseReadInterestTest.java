/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client carrier whose peer answers and then closes stops selecting the connection for read, and
 * its reader still gets the answer.
 *
 * <p>A socket at end-of-stream stays readable on a level-triggered selector, so a key that keeps
 * {@code OP_READ} after the close is selected on every reactor turn until the stream closes. Each
 * case waits for the stream to see the close, then for the key to drop {@code OP_READ}, and only
 * then reads. Fails rather than skips when OpenSSL cannot be loaded.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Native TCP carrier — a peer's close ends read interest and leaves its answer readable")
class NativeTcpPeerCloseReadInterestTest {

    private static final byte[] PAYLOAD = {'p', 'i', 'n', 'g'};
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static MemoryAllocator allocator;
    private static CommunityKernelCryptoProvider crypto;
    private static String localhostAddress;

    @TempDir
    static Path material;

    @BeforeAll
    static void setUp() throws IOException {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        crypto = new CommunityKernelCryptoProvider();
        localhostAddress = InetAddress.getByName("localhost").getHostAddress();
    }

    @AfterAll
    static void tearDown() {
        allocator.close();
    }

    @Test
    @DisplayName("TLS: a server that echoes and closes")
    void tlsPeerThatAnswersAndCloses() throws Exception {
        TlsTestAuthority authority = TlsTestAuthority.root(material, "peer-close-ca");
        TlsTestAuthority.Issued leaf = authority.issue(TlsTestAuthority.dns("localhost"));
        int port = freePort();
        try (var _ = echoThenCloseTlsServer(leaf, port);
             TransportEngine client = tlsClientTrusting(authority.certificate())) {
            assertAnswerSurvivesThePeersClose((NativeTcpCarrier) client, "localhost", port);
        }
    }

    @Test
    @DisplayName("plaintext: a server that echoes and closes")
    void plaintextPeerThatAnswersAndCloses() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             TransportEngine client = plaintextClient()) {
            Thread server = Thread.ofPlatform().daemon().start(() -> echoThenClose(listener));
            assertAnswerSurvivesThePeersClose((NativeTcpCarrier) client, "127.0.0.1", listener.getLocalPort());
            server.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private static void assertAnswerSurvivesThePeersClose(NativeTcpCarrier client, String host, int port)
            throws ReflectiveOperationException, InterruptedException {
        try (TransportStream opened = client.connect(host, port).openStream();
             LoanedBuffer sink = allocator.allocateNetwork(PAYLOAD.length)) {
            NativeTcpStream stream = (NativeTcpStream) opened;
            stream.write(MemorySegment.ofArray(PAYLOAD), PAYLOAD.length);

            awaitTrue(stream::isRemoteClosed, "the stream saw the peer's close");
            Selector selector = selectorOf(client);
            SocketChannel channel = (SocketChannel) field(NativeTcpStream.class, "channel").get(stream);
            awaitTrue(() -> {
                SelectionKey key = channel.keyFor(selector);
                return key != null && key.isValid() && (key.interestOps() & SelectionKey.OP_READ) == 0;
            }, "the key dropped OP_READ and was not cancelled");

            int read = 0;
            while (read < PAYLOAD.length) {
                int got = stream.read(sink.segment().asSlice(read), PAYLOAD.length - read);
                assertThat(got).as("bytes read after %d of the answer", read).isPositive();
                read += got;
            }
            assertThat(sink.segment().asSlice(0, read).toArray(ValueLayout.JAVA_BYTE)).containsExactly(PAYLOAD);
            assertThat(stream.read(sink.segment(), PAYLOAD.length)).as("after the answer").isEqualTo(-1);
        }
    }

    private static TransportEngine echoThenCloseTlsServer(TlsTestAuthority.Issued leaf, int port) {
        TransportEngine server = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, new MapConfigProvider(Map.of(), Map.of()))
                .call(() -> new NativeTcpTransportProvider().createEngine(new TransportConfig(
                        TransportMode.SERVER, localhostAddress, port, 1,
                        leaf.certificate().toString(), leaf.privateKey().toString(), 1024, 30_000)));
        server.setStreamHandler(stream -> {
            try (stream; LoanedBuffer buffer = allocator.allocateNetwork(PAYLOAD.length)) {
                int read = 0;
                while (read < PAYLOAD.length) {
                    int got = stream.read(buffer.segment().asSlice(read), PAYLOAD.length - read);
                    if (got < 0) {
                        return;
                    }
                    read += got;
                }
                stream.write(buffer.segment(), read);
            }
        });
        server.start();
        return server;
    }

    private static void echoThenClose(ServerSocket listener) {
        try (Socket socket = listener.accept()) {
            InputStream in = socket.getInputStream();
            byte[] request = in.readNBytes(PAYLOAD.length);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("the plaintext peer failed", e);
        }
    }

    private static TransportEngine tlsClientTrusting(Path anchor) {
        TransportEngine client = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, new MapConfigProvider(
                        Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, anchor.toString()), Map.of()))
                .call(() -> new NativeTcpTransportProvider().createEngine(clientConfig()));
        client.start();
        return client;
    }

    private static TransportEngine plaintextClient() {
        TransportEngine client = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .call(() -> new NativeTcpTransportProvider().createEngine(clientConfig()));
        client.start();
        return client;
    }

    private static TransportConfig clientConfig() {
        return new TransportConfig(TransportMode.CLIENT, "127.0.0.1", 0, 1, null, null, 1024, 30_000);
    }

    /** The selector of the carrier's only reactor. */
    private static Selector selectorOf(NativeTcpCarrier carrier) throws ReflectiveOperationException {
        List<?> reactors = (List<?>) field(NativeTcpCarrier.class, "reactors").get(carrier);
        assertThat(reactors).as("the client carrier's reactors").hasSize(1);
        return (Selector) field(NativeTcpReactor.class, "selector").get(reactors.getFirst());
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void awaitTrue(BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("not within 10 s: " + what);
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
