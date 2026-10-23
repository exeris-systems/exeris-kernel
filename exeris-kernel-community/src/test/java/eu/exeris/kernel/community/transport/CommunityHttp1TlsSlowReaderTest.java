/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.community.http.LeakTrackingAllocator;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A large HTTP/1.1 response over TLS reaches a client that stops reading, and arrives intact once it
 * reads again. With an 8 KiB send buffer on the server and a small receive buffer on the client, the
 * single TLS write of the whole body meets a full socket long before it is sent; the connection must
 * wait for space, not reset, and the handler's write waits at the outbound bound instead of queueing
 * the whole body.
 *
 * <p>The client reads nothing until the transport reports that the write stalled, so the case cannot
 * pass without the stall having happened. The server is the production one,
 * {@code CommunityHttpProvider#createServerEngine}. Fails rather than skips when OpenSSL cannot be
 * loaded, because a skipped case reports success.
 */
@Tag("stream-loopback")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("Community: HTTP/1.1 over TLS delivers a large body to a slow reader")
class CommunityHttp1TlsSlowReaderTest {

    static {
        // The same value, as the stream bindings, so the order in which they load the carrier in one
        // JVM does not matter.
        System.setProperty("exeris.transport.acceptedSendBufferBytes", Integer.toString(8 * 1024));
    }

    private static final String HOST = "127.0.0.1";
    private static final int BODY_BYTES = 4 * 1024 * 1024;
    private static final int CLIENT_RECEIVE_BUFFER = 8 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final long AWAIT_SECONDS = 30L;
    private static final long QUIET_MILLIS = 500L;
    private static final long SEED = 0x5EED_5EEDL;
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};
    private static final String CONTENT_LENGTH = "content-length:";

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
    @DisplayName("a body sent to a client that reads nothing until the write stalls arrives byte for byte, with no reset, "
            + "and the handler waits meanwhile")
    void bodyArrivesIntactAfterTheWriteStalled() throws Exception {
        byte[] body = new byte[BODY_BYTES];
        new Random(SEED).nextBytes(body);
        String expectedDigest = sha256(body);
        LeakTrackingAllocator tracked = new LeakTrackingAllocator(ALLOCATOR);
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch handlerReturned = new CountDownLatch(1);
        int port = freePort();
        HttpServerEngine server = startServer(port, tracked, exchange -> {
            try {
                exchange.respond(new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_1_1, List.of(), bodyBuffer(tracked, body)));
            } finally {
                handlerReturned.countDown();
            }
        });
        Received received;
        NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(stalled::countDown);
        try (SSLSocket socket = connect(port)) {
            socket.getOutputStream().write(("GET /big HTTP/1.1\r\nHost: " + HOST
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            assertThat(stalled.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the server's TLS write stalled on the full send buffer").isTrue();
            assertThat(handlerReturned.await(QUIET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the handler's write waits at the outbound bound while the client is not reading")
                    .isFalse();

            received = readResponse(socket.getInputStream());
            assertThat(handlerReturned.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the handler returns once the client has read").isTrue();
        } finally {
            NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(null);
        }

        assertThat(received.contentLength()).isEqualTo(BODY_BYTES);
        assertThat(received.body()).hasSize(BODY_BYTES);
        assertThat(sha256(received.body())).isEqualTo(expectedDigest);
        Thread closer = Thread.ofVirtual().start(server::close);
        closer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        assertThat(closer.isAlive()).as("shutdown is not held by the connection").isFalse();
        assertThat(tracked.outstanding()).as("every loan is back").isZero();
    }

    private static LoanedBuffer bodyBuffer(MemoryAllocator allocator, byte[] body) {
        LoanedBuffer buffer = allocator.allocateNetwork(body.length);
        MemorySegment.copy(MemorySegment.ofArray(body), 0, buffer.segment(), 0, body.length);
        buffer.setSize(body.length);
        return buffer;
    }

    private static HttpServerEngine startServer(int port, MemoryAllocator allocator,
                                                HttpHandler handler) {
        HttpConfig config = new HttpConfig(
                HttpMode.SERVER,
                HOST,
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_1_1,
                null,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
        MapConfigProvider kernelConfig = new MapConfigProvider(
                Map.of("transport.certPath", certificate.certificate().toString(),
                        "transport.keyPath", certificate.privateKey().toString()),
                Map.of());
        HttpServerEngine[] holder = new HttpServerEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, kernelConfig)
                .run(() -> holder[0] = new CommunityHttpProvider().createServerEngine(config));
        holder[0].setHandler(handler);
        holder[0].start();
        return holder[0];
    }

    private static SSLSocket connect(int port) throws IOException {
        SSLSocket socket = (SSLSocket) clientTrust.getSocketFactory().createSocket();
        socket.setReceiveBufferSize(CLIENT_RECEIVE_BUFFER);
        socket.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MILLIS);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setApplicationProtocols(new String[] {"http/1.1"});
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    private record Received(int contentLength, byte[] body) {
    }

    private static Received readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < HEADER_END.length) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("the connection ended inside the response head");
            }
            head.write(next);
            matched = next == HEADER_END[matched] ? matched + 1 : (next == HEADER_END[0] ? 1 : 0);
        }
        int length = -1;
        for (String line : head.toString(StandardCharsets.US_ASCII).split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith(CONTENT_LENGTH)) {
                length = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());
            }
        }
        byte[] body = in.readNBytes(Math.max(length, 0));
        return new Received(length, body);
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
