/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Community listener's handler that sends to a peer which ended the stream is told so through the
 * closed-stream signal for the peer, and is not told the send failed. A peer FIN is a half-close and
 * leaves writes working; a TLS {@code close_notify} is not, and ends writes.
 *
 * <p>The server is a real Community transport engine; the clients are a plain socket and a JDK
 * {@code SSLSocket}. Fails rather than skips when OpenSSL cannot be loaded, because a skipped case
 * reports success. Runs with the {@code stream-loopback} group.
 */
@Tag("stream-loopback")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("Community: a handler's send to a peer that ended the stream")
class CommunityTransportPeerEndWriteIntegrationTest {

    static {
        // The same value as the stream bindings, so the order in which they load the carrier in one
        // JVM does not matter.
        System.setProperty("exeris.transport.acceptedSendBufferBytes", Integer.toString(8 * 1024));
    }

    private static final String HOST = "127.0.0.1";
    private static final String COMMUNITY_LOGGER = "eu.exeris.kernel.community";
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final long AWAIT_SECONDS = 30L;
    private static final int MAX_SEND_ATTEMPTS = 200_000;
    private static final byte[] REPLY = {'r', 'e', 'p', 'l', 'y', '0', '1', '!'};

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

    @Test
    @DisplayName("plain: a write after the client's FIN succeeds and the client reads it")
    void plainWriteAfterTheClientsFinIsDelivered() throws Exception {
        Outcome<Object> afterEnd = new Outcome<>();
        try (ServerUnderTest server = ServerUnderTest.plain(stream -> {
            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                int read = stream.read(sink.segment(), 64);
                while (read >= 0) {
                    read = stream.read(sink.segment(), 64);
                }
                writeReply(stream);
                afterEnd.complete(read);
            } catch (RuntimeException failure) {
                afterEnd.complete(failure);
            }
        }); Socket client = server.connectPlain()) {
            client.shutdownOutput();

            assertThat(afterEnd.await()).as("the handler read the end and its write succeeded").isEqualTo(-1);
            assertThat(client.getInputStream().readNBytes(REPLY.length)).containsExactly(REPLY);
        }
    }

    @Test
    @DisplayName("plain: the first send refused after the client's reset reports EX-NET-4008, not a send failure")
    void plainWriteAfterTheClientsResetReportsThePeerCode() throws Exception {
        Outcome<Throwable> firstRefusal = new Outcome<>();
        CountDownLatch clientGone = new CountDownLatch(1);
        try (ServerUnderTest server = ServerUnderTest.plain(stream -> {
            awaitUninterruptibly(clientGone);
            firstRefusal.complete(firstRefusalOf(stream));
        })) {
            Socket client = server.connectPlain();
            client.setSoLinger(true, 0);
            client.close();
            clientGone.countDown();

            Throwable refusal = firstRefusal.await();

            assertThat(refusal).as(chainOf(refusal)).isInstanceOfSatisfying(TransportStreamClosedException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        }
    }

    @Test
    @DisplayName("TLS: a write after the client's close_notify reports EX-NET-4008, delivers nothing and logs no warning")
    void tlsWriteAfterTheClientsCloseNotifyReportsThePeerCode() throws Exception {
        Outcome<Throwable> outcome = new Outcome<>();
        try (ServerUnderTest server = ServerUnderTest.tls(stream -> {
            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                int first = stream.read(sink.segment(), 64);
                int second = first > 0 ? stream.read(sink.segment(), 64) : first;
                if (first != 1 || second != -1) {
                    outcome.complete(new AssertionError("expected one byte then the end, read " + first + ", " + second));
                    return;
                }
                outcome.complete(firstRefusalOf(stream));
            } catch (RuntimeException failure) {
                outcome.complete(failure);
            }
        }); WarningCapture warnings = new WarningCapture()) {
            try (SSLSocket client = server.connectTls()) {
                OutputStream out = client.getOutputStream();
                out.write('x');
                out.flush();
                client.shutdownOutput();

                Throwable refusal = outcome.await();

                assertThat(refusal).isInstanceOfSatisfying(TransportStreamClosedException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008);
                    assertThat(e.isPeerClosed()).isTrue();
                });
                for (Throwable link = refusal; link != null; link = link.getCause()) {
                    assertThat(link).as("no handshake failure in the chain").isNotInstanceOf(TlsHandshakeException.class);
                }
                assertThat(applicationBytesUntilTheEnd(client.getInputStream()))
                        .as("the write after close_notify is not delivered")
                        .isZero();
            }
            assertThat(warnings.records()).as("warnings logged by the transport").isEmpty();
        }
    }

    private static String chainOf(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable link = failure; link != null; link = link.getCause()) {
            chain.append(link).append(" <- ");
        }
        return chain.toString();
    }

    /** One value handed from a handler thread to the test thread, awaited as an event. */
    private static final class Outcome<T> {

        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<T> value = new AtomicReference<>();

        private void complete(T result) {
            value.compareAndSet(null, result);
            done.countDown();
        }

        private T await() throws InterruptedException {
            assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the handler finished").isTrue();
            return value.get();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("TLS: a write after the client's reset, with no read before it, reports EX-NET-4008")
    void tlsWriteAfterTheClientsResetWithoutAReadReportsThePeerCode() throws Exception {
        Outcome<Throwable> firstRefusal = new Outcome<>();
        CountDownLatch clientGone = new CountDownLatch(1);
        CountDownLatch handlerRunning = new CountDownLatch(1);
        try (ServerUnderTest server = ServerUnderTest.tls(stream -> {
            // One byte in, so the handler is running before the client resets; nothing is read after it.
            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                stream.read(sink.segment(), 64);
            }
            handlerRunning.countDown();
            awaitUninterruptibly(clientGone);
            firstRefusal.complete(firstRefusalOf(stream));
        })) {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(HOST, port(server)), CONNECT_TIMEOUT_MILLIS);
            SSLSocket tls = (SSLSocket) clientTrust.getSocketFactory().createSocket(raw, HOST, port(server), false);
            tls.startHandshake();
            tls.getOutputStream().write('x');
            tls.getOutputStream().flush();
            assertThat(handlerRunning.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the handler is running").isTrue();
            // The TCP socket is reset under the TLS layer, so no close_notify precedes the reset.
            raw.setSoLinger(true, 0);
            raw.close();
            clientGone.countDown();

            Throwable refusal = firstRefusal.await();

            assertThat(refusal).as(chainOf(refusal)).isInstanceOfSatisfying(TransportStreamClosedException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
            for (Throwable link = refusal; link != null; link = link.getCause()) {
                assertThat(link).as("no handshake failure in the chain").isNotInstanceOf(TlsHandshakeException.class);
            }
        }
    }

    private static int port(ServerUnderTest server) {
        return server.port;
    }

    /** Sends one byte at a time until a send throws; returns what it threw, or fails past the attempt bound. */
    private static Throwable firstRefusalOf(TransportStream stream) {
        try (LoanedBuffer probe = ALLOCATOR.allocateNetwork(1)) {
            for (int attempt = 0; attempt < MAX_SEND_ATTEMPTS; attempt++) {
                try {
                    stream.write(probe.segment(), 1);
                } catch (RuntimeException refusal) {
                    return refusal;
                }
            }
        }
        return new AssertionError("no send was refused within " + MAX_SEND_ATTEMPTS + " attempts");
    }

    private static void writeReply(TransportStream stream) {
        try (LoanedBuffer reply = ALLOCATOR.allocateNetwork(REPLY.length)) {
            reply.segment().asSlice(0, REPLY.length).asByteBuffer().put(REPLY);
            reply.setSize(REPLY.length);
            stream.write(reply.segment(), REPLY.length);
        }
    }

    /** Reads until the stream ends or the connection fails; a timeout means the server never ended it. */
    private static int applicationBytesUntilTheEnd(InputStream in) throws IOException {
        int bytes = 0;
        try {
            while (in.read() >= 0) {
                bytes++;
            }
        } catch (SocketTimeoutException silent) {
            throw silent;
        } catch (IOException ended) {
            return bytes;
        }
        return bytes;
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

    /** A started Community server whose accepted streams run the given handler. */
    private static final class ServerUnderTest implements AutoCloseable {

        private final TransportEngine engine;
        private final int port;

        private ServerUnderTest(TransportEngine engine, int port) {
            this.engine = engine;
            this.port = port;
        }

        private static ServerUnderTest plain(Consumer<TransportStream> handler) {
            return start(handler, null, null);
        }

        private static ServerUnderTest tls(Consumer<TransportStream> handler) {
            return start(handler, certificate.certPath(), certificate.keyPath());
        }

        private static ServerUnderTest start(Consumer<TransportStream> handler, String certPath, String keyPath) {
            int port = CommunityTransportTestHarness.nextFreePort();
            TransportEngine[] holder = new TransportEngine[1];
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                    .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                    .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                            TransportMode.SERVER, HOST, port, 1, certPath, keyPath, 1024, 30_000)));
            holder[0].setConnectionHandler(connection -> { });
            holder[0].setStreamHandler(handler::accept);
            holder[0].start();
            return new ServerUnderTest(holder[0], port);
        }

        private Socket connectPlain() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            return socket;
        }

        private SSLSocket connectTls() throws IOException {
            SSLSocket socket = (SSLSocket) clientTrust.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.startHandshake();
            return socket;
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    /** Records every WARNING or worse the Community loggers publish while open. */
    private static final class WarningCapture extends Handler implements AutoCloseable {

        private final List<String> records = new CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getLogger(COMMUNITY_LOGGER);

        private WarningCapture() {
            super();
            setLevel(Level.WARNING);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord != null && logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(logRecord.getLoggerName() + ": " + logRecord.getMessage());
            }
        }

        private List<String> records() {
            return records;
        }

        @Override
        public void flush() {
            // nothing is buffered
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
