/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.crypto.CommunityTlsEngine;
import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
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
import java.lang.foreign.MemorySegment;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Community client carrier verifies its server against the host it dials, through real carriers
 * and real OpenSSL on the loopback.
 *
 * <p>Each refusal is asserted as the {@code X509_V_*} code the caller receives, and, where it matters,
 * as the server never seeing an established connection: a client that aborts its own handshake sends
 * no {@code Finished}, so the server's handshake fails too. Fails rather than skips when OpenSSL
 * cannot be loaded.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community carriers — a TLS client verifies its server against the dialled host")
class CommunityTlsPeerVerificationTest {

    private static final int X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT = 18;
    private static final int X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY = 20;
    private static final int X509_V_ERR_HOSTNAME_MISMATCH = 62;
    private static final int X509_V_ERR_IP_ADDRESS_MISMATCH = 64;

    private static final byte[] PAYLOAD = {'p', 'i', 'n', 'g'};

    private static MemoryAllocator allocator;
    private static CommunityKernelCryptoProvider crypto;
    private static TlsTestAuthority trusted;
    private static TlsTestAuthority untrusted;
    private static String localhostAddress;

    @TempDir
    static Path material;

    @BeforeAll
    static void setUp() throws IOException {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        crypto = new CommunityKernelCryptoProvider();
        trusted = TlsTestAuthority.root(material, "trusted-ca");
        untrusted = TlsTestAuthority.root(material, "untrusted-ca");
        localhostAddress = InetAddress.getByName("localhost").getHostAddress();
    }

    @AfterAll
    static void tearDown() {
        allocator.close();
    }

    @Test
    @DisplayName("a trusted leaf for the dialled name completes and round-trips")
    void trustedLeafForTheDialledNameCompletes() throws Exception {
        TlsTestAuthority.Issued leaf = trusted.issue(TlsTestAuthority.dns("localhost"));
        try (Server server = Server.start(leaf, localhostAddress);
             TransportEngine client = clientTrusting(trusted.certificate())) {
            assertThat(roundTrip(client, "localhost", server.port())).containsExactly(PAYLOAD);
        }
    }

    @Test
    @DisplayName("a leaf from an issuer the client does not trust: 20, and the server never establishes")
    void untrustedIssuerIsRefused() throws Exception {
        TlsTestAuthority.Issued leaf = untrusted.issue(TlsTestAuthority.dns("localhost"));
        try (Server server = Server.start(leaf, localhostAddress);
             TransportEngine client = clientTrusting(trusted.certificate())) {
            assertRefusedOnWrite(client, "localhost", server.port(), X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY);
            server.awaitConnectionGone();
            assertThat(server.established())
                    .as("the client aborted its handshake, so the server's never completed")
                    .hasValue(0);
        }
    }

    @Test
    @DisplayName("a trusted leaf for another name: 62")
    void leafForAnotherNameIsRefused() throws Exception {
        TlsTestAuthority.Issued leaf = trusted.issue(TlsTestAuthority.dns("other.invalid"));
        try (Server server = Server.start(leaf, localhostAddress);
             TransportEngine client = clientTrusting(trusted.certificate())) {
            assertRefusedOnWrite(client, "localhost", server.port(), X509_V_ERR_HOSTNAME_MISMATCH);
        }
    }

    @Test
    @DisplayName("a leaf naming only a DNS host, dialled by IP: 64")
    void dnsLeafDialledByAddressIsRefused() throws Exception {
        TlsTestAuthority.Issued leaf = trusted.issue(TlsTestAuthority.dns("localhost"));
        try (Server server = Server.start(leaf, "127.0.0.1");
             TransportEngine client = clientTrusting(trusted.certificate())) {
            assertRefusedOnWrite(client, "127.0.0.1", server.port(), X509_V_ERR_IP_ADDRESS_MISMATCH);
        }
    }

    @Test
    @DisplayName("a self-signed leaf the client does not trust: 18")
    void untrustedSelfSignedIsRefused(@TempDir Path selfSignedDir) throws Exception {
        TlsTestCertificate selfSigned = TlsTestCertificate.generateInto(selfSignedDir);
        TlsTestAuthority.Issued leaf = new TlsTestAuthority.Issued(selfSigned.certificate(), selfSigned.privateKey());
        try (Server server = Server.start(leaf, "127.0.0.1");
             TransportEngine client = clientTrusting(trusted.certificate())) {
            assertRefusedOnWrite(client, "127.0.0.1", server.port(), X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT);
        }
    }

    @Test
    @DisplayName("a read before any write reports the refusal, not end-of-stream")
    void readFirstReportsTheRefusal() throws Exception {
        TlsTestAuthority.Issued leaf = untrusted.issue(TlsTestAuthority.dns("localhost"));
        try (Server server = Server.start(leaf, localhostAddress);
             TransportEngine client = clientTrusting(trusted.certificate())) {
            TransportConnection connection = client.connect("localhost", server.port());
            try (TransportStream stream = connection.openStream();
                 LoanedBuffer sink = allocator.allocateNetwork(PAYLOAD.length)) {
                assertThatThrownBy(() -> stream.read(sink.segment(), PAYLOAD.length))
                        .satisfies(e -> assertVerificationFailure(e, X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY));
            }
        }
    }

    @Test
    @DisplayName("a DUAL carrier holding listener material dials out as a verifying client")
    void dualCarrierDialsAsAClient(@TempDir Path dualMaterial) throws Exception {
        TlsTestAuthority.Issued remoteLeaf = trusted.issue(TlsTestAuthority.dns("localhost"));
        TlsTestCertificate ownMaterial = TlsTestCertificate.generateInto(dualMaterial);
        try (Server server = Server.start(remoteLeaf, localhostAddress);
             TransportEngine dual = carrier(new TransportConfig(TransportMode.DUAL, "127.0.0.1", freePort(), 1,
                     ownMaterial.certPath(), ownMaterial.keyPath(), 1024, 30_000), trust(trusted.certificate()))) {
            dual.setStreamHandler(stream -> { });
            dual.start();
            assertThat(roundTrip(dual, "localhost", server.port())).containsExactly(PAYLOAD);
        }
    }

    @Test
    @DisplayName("a host that is neither a name nor an address is refused before any socket opens")
    void invalidHostIsRefusedBeforeTheDial() throws Exception {
        TlsTestAuthority.Issued leaf = trusted.issue(TlsTestAuthority.dns("localhost"));
        try (Server server = Server.start(leaf, localhostAddress);
             TransportEngine client = clientTrusting(trusted.certificate())) {
            assertThatThrownBy(() -> client.connect("[]", server.port()))
                    .isInstanceOf(TransportException.class)
                    .satisfies(e -> {
                        assertThat(((TransportException) e).errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4001);
                        assertThat(e.getCause()).isInstanceOf(TlsHandshakeException.class);
                        assertThat(((TlsHandshakeException) e.getCause()).rawArgs())
                                .containsExactly(-1, TlsFailureDetail.INVALID_PEER_NAME);
                    });
            assertThat(server.engine().stats().totalAccepted()).isZero();
        }
    }

    @Test
    @DisplayName("a plain client engine: the unbound check first, then a refusal to handshake with no peer")
    void plainClientEngineRefusesItsHandshake() throws Exception {
        try (CommunityTlsEngine engine = (CommunityTlsEngine) crypto.createTlsEngine(CryptoProviderConfig.tcpClient());
             LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM);
             ServerSocketChannel listener = ServerSocketChannel.open();
             SocketChannel channel = SocketChannel.open()) {
            assertThatThrownBy(() -> engine.beginHandshake(outbound))
                    .isInstanceOf(TlsHandshakeException.class)
                    .satisfies(e -> assertThat(((TlsHandshakeException) e).rawArgs())
                            .containsExactly(-1, "TLS engine is not bound to socket FD"));

            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            channel.connect(listener.getLocalAddress());
            // Non-blocking, so an engine that wrongly starts the handshake gets WANT_READ from a
            // peer that never answers, instead of blocking in SSL_connect.
            channel.configureBlocking(false);
            try (SocketChannel accepted = listener.accept()) {
                engine.bindFileDescriptor(SocketChannelFdAccess.requireFd(channel));
                assertThatThrownBy(() -> engine.beginHandshake(outbound))
                        .isInstanceOf(TlsHandshakeException.class)
                        .satisfies(e -> assertThat(((TlsHandshakeException) e).rawArgs())
                                .containsExactly(-1, TlsFailureDetail.NO_PEER_IDENTITY));
                assertThat(accepted.isConnected()).isTrue();
            }
        }
    }

    private static void assertRefusedOnWrite(TransportEngine client, String host, int port, int x509Code) {
        TransportConnection connection = client.connect(host, port);
        try (TransportStream stream = connection.openStream()) {
            assertThatThrownBy(() -> stream.write(MemorySegment.ofArray(PAYLOAD), PAYLOAD.length))
                    .satisfies(e -> assertVerificationFailure(e, x509Code));
        }
    }

    private static void assertVerificationFailure(Throwable thrown, int x509Code) {
        assertThat(thrown).isInstanceOf(TlsHandshakeException.class);
        TlsHandshakeException refusal = (TlsHandshakeException) thrown;
        assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_2001);
        assertThat(refusal.rawArgs()).containsExactly(x509Code, TlsFailureDetail.PEER_VERIFICATION_FAILED);
    }

    private static byte[] roundTrip(TransportEngine client, String host, int port) {
        TransportConnection connection = client.connect(host, port);
        try (TransportStream stream = connection.openStream();
             LoanedBuffer inbound = allocator.allocateNetwork(PAYLOAD.length)) {
            stream.write(MemorySegment.ofArray(PAYLOAD), PAYLOAD.length);
            int read = 0;
            while (read < PAYLOAD.length) {
                int got = stream.read(inbound.segment().asSlice(read), PAYLOAD.length - read);
                if (got < 0) {
                    break;
                }
                read += got;
            }
            byte[] echoed = new byte[read];
            MemorySegment.copy(inbound.segment(), java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, echoed, 0, read);
            return echoed;
        }
    }

    private static TransportEngine clientTrusting(Path anchor) {
        TransportEngine engine = carrier(new TransportConfig(TransportMode.CLIENT, "127.0.0.1", 0, 1,
                null, null, 1024, 30_000), trust(anchor));
        engine.start();
        return engine;
    }

    private static ConfigProvider trust(Path anchor) {
        return new MapConfigProvider(
                Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, anchor.toString()), Map.of());
    }

    private static TransportEngine carrier(TransportConfig config, ConfigProvider configProvider) {
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, configProvider)
                .call(() -> new NativeTcpTransportProvider().createEngine(config));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A TLS echo listener that counts the connections whose handshake completed. */
    private record Server(TransportEngine engine, int port, AtomicInteger established) implements AutoCloseable {

        static Server start(TlsTestAuthority.Issued leaf, String bindHost) throws IOException {
            int port = freePort();
            TransportEngine engine = carrier(new TransportConfig(TransportMode.SERVER, bindHost, port, 1,
                    leaf.certificate().toString(), leaf.privateKey().toString(), 1024, 30_000),
                    new MapConfigProvider(Map.of(), Map.of()));
            AtomicInteger established = new AtomicInteger();
            engine.setConnectionHandler(connection -> established.incrementAndGet());
            engine.setStreamHandler(stream -> {
                try (LoanedBuffer buffer = allocator.allocateNetwork(PAYLOAD.length)) {
                    int read = stream.read(buffer.segment(), PAYLOAD.length);
                    if (read > 0) {
                        stream.write(buffer.segment(), read);
                    }
                }
            });
            engine.start();
            return new Server(engine, port, established);
        }

        /**
         * Waits until the listener has accepted a connection and torn it down, so that everything the
         * client sent — including a {@code Finished} a client that did not verify would have sent —
         * has been processed before an assertion about what the server saw.
         */
        void awaitConnectionGone() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (engine.stats().totalAccepted() < 1 || engine.stats().activeConnections() > 0) {
                assertThat(System.nanoTime()).as("the server finished with the connection").isLessThan(deadline);
                TimeUnit.MILLISECONDS.sleep(5);
            }
        }

        @Override
        public void close() {
            engine.close();
        }
    }
}
