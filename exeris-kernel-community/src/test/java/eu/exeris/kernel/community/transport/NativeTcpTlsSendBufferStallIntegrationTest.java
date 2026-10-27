/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.http.LeakTrackingAllocator;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A TLS write that meets a full socket send buffer waits for space and then delivers every byte,
 * against the real OpenSSL binding and not a scripted one. The server is a real Community transport
 * engine; the client is a JDK {@code SSLSocket}, which reads nothing until the server's write has
 * stalled. A Community peer would not do for the reader: its reactor reads ahead into an unbounded
 * inbound queue, so it never fills the sender's buffer reliably.
 *
 * <p>The volume is sized for the operating system's default buffers and the case does not depend on
 * the send-buffer property below; it cannot pass without the stall having happened. Fails rather than
 * skips when OpenSSL cannot be loaded, because a skipped case reports success.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("Community: a TLS write on a full send buffer stalls, and every byte arrives")
class NativeTcpTlsSendBufferStallIntegrationTest {

    static {
        // The same value as the stream bindings, so the order in which they load the carrier in one
        // JVM does not matter.
        System.setProperty("exeris.transport.acceptedSendBufferBytes", Integer.toString(8 * 1024));
    }

    private static final String HOST = "127.0.0.1";
    private static final int TOTAL_BYTES = 16 * 1024 * 1024;
    private static final int CHUNK = 64 * 1024;
    private static final int CLIENT_RECEIVE_BUFFER = 8 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final long AWAIT_SECONDS = 30L;
    private static final long SEED = 0x5EED_5EEDL;

    @TempDir
    /* default */ static Path tlsMaterialDir;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static CommunityKernelCryptoProvider crypto;
    private static TlsTestCertificate certificate;
    private static SSLContext clientTrust;

    @BeforeAll
    static void loadOpenSslAndMaterial() throws Exception {
        crypto = new CommunityKernelCryptoProvider();
        certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        clientTrust = trusting(certificate.certificate());
    }

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @AfterEach
    void clearStallHook() {
        NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(null);
    }

    @Test
    @DisplayName("a 16 MiB write to a TLS client that reads nothing until the write stalls arrives byte for byte, "
            + "the stream stays open, and shutdown is not held")
    void writeToAClientThatReadsNothingUntilTheStallArrivesIntact() throws Exception {
        byte[] body = new byte[TOTAL_BYTES];
        new Random(SEED).nextBytes(body);
        String expectedDigest = sha256(body);
        LeakTrackingAllocator tracked = new LeakTrackingAllocator(ALLOCATOR);
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch clientDone = new CountDownLatch(1);
        Outcome<Throwable> writer = new Outcome<>();
        AtomicReference<Boolean> openBeforeClose = new AtomicReference<>();
        byte[] received;
        int afterTheBody;
        Thread closer = null;
        NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(stalled::countDown);
        ServerUnderTest server = ServerUnderTest.start(tracked, stream ->
                serve(stream, tracked, body, writer, openBeforeClose, clientDone));
        try {
            try (SSLSocket client = connect(server.port)) {
                client.getOutputStream().write('g');
                client.getOutputStream().flush();

                assertThat(stalled.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                        .as("the server's TLS write met a full send buffer and stalled; if the host's "
                                + "net.ipv4.tcp_wmem max exceeds TOTAL_BYTES, raise TOTAL_BYTES")
                        .isTrue();

                received = client.getInputStream().readNBytes(TOTAL_BYTES);
                assertThat(received).hasSize(TOTAL_BYTES);
                assertThat(sha256(received)).isEqualTo(expectedDigest);
                assertThat(writer.await()).as("the writer completed without a failure").isNull();
                assertThat(openBeforeClose.get()).as("the stream stayed open through the write").isTrue();

                clientDone.countDown();
                afterTheBody = client.getInputStream().read();
            }
            closer = Thread.ofVirtual().start(server::close);
            closer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        } finally {
            NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(null);
            clientDone.countDown();
            if (closer == null) {
                server.close();
            }
        }

        assertThat(afterTheBody).as("a graceful end, not a reset").isEqualTo(-1);
        assertThat(closer.isAlive()).as("shutdown is not held by the connection").isFalse();
        assertThat(tracked.outstanding()).as("every loan is back").isZero();
    }

    /** Reads the go byte, writes the body in chunk-sized slices, and holds the stream open until the client is done. */
    private static void serve(TransportStream stream, MemoryAllocator allocator, byte[] body,
                              Outcome<Throwable> writer, AtomicReference<Boolean> openBeforeClose,
                              CountDownLatch clientDone) {
        try (LoanedBuffer go = allocator.allocateNetwork(1)) {
            if (stream.read(go.segment(), 1) != 1) {
                writer.complete(new AssertionError("the client's go byte did not arrive"));
            } else {
                try (LoanedBuffer slice = allocator.allocateNetwork(CHUNK)) {
                    for (int offset = 0; offset < body.length; offset += CHUNK) {
                        int length = Math.min(CHUNK, body.length - offset);
                        MemorySegment.copy(MemorySegment.ofArray(body), offset, slice.segment(), 0, length);
                        stream.write(slice.segment(), length);
                    }
                }
                writer.complete(null);
            }
        } catch (RuntimeException failure) {
            writer.complete(failure);
        }
        openBeforeClose.set(!((NativeTcpStream) stream).isClosed());
        awaitUninterruptibly(clientDone);
        stream.close();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static SSLSocket connect(int port) throws IOException {
        SSLSocket socket = (SSLSocket) clientTrust.getSocketFactory().createSocket();
        socket.setReceiveBufferSize(CLIENT_RECEIVE_BUFFER);
        socket.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MILLIS);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        socket.startHandshake();
        return socket;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static SSLContext trusting(Path certificatePath) throws Exception {
        X509Certificate server;
        try (InputStream in = Files.newInputStream(certificatePath)) {
            server = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("server", server);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    /** One value handed from a handler thread to the test thread; a null value is a completion, not an absence. */
    private static final class Outcome<T> {

        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<T> value = new AtomicReference<>();

        private void complete(T result) {
            value.compareAndSet(null, result);
            done.countDown();
        }

        private T await() throws InterruptedException {
            assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the writer finished").isTrue();
            return value.get();
        }
    }

    /** A started Community TLS server whose accepted streams run the given handler. */
    private static final class ServerUnderTest implements AutoCloseable {

        private final TransportEngine engine;
        private final int port;

        private ServerUnderTest(TransportEngine engine, int port) {
            this.engine = engine;
            this.port = port;
        }

        private static ServerUnderTest start(MemoryAllocator allocator, Consumer<TransportStream> handler) {
            int port = CommunityTransportTestHarness.nextFreePort();
            TransportEngine[] holder = new TransportEngine[1];
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                    .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                    .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                            TransportMode.SERVER, HOST, port, 1, certificate.certPath(), certificate.keyPath(),
                            1024, 30_000)));
            holder[0].setConnectionHandler(connection -> { });
            holder[0].setStreamHandler(handler::accept);
            holder[0].start();
            return new ServerUnderTest(holder[0], port);
        }

        @Override
        public void close() {
            engine.close();
        }
    }
}
