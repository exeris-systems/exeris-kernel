/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.support.BlockingPeerPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Community server presents the whole chain its certificate file names, not only the leaf, and a
 * client verifies it against its own trust.
 *
 * <p>The leaf here is issued by an intermediate that is not a trust anchor, so the handshake succeeds
 * only when the server sends that intermediate. The JDK client reads the peer chain it was given, and
 * the Community client verifies through {@code createClientTlsEngine}, which is the path an
 * Exeris-to-Exeris client takes.
 */
@Timeout(60)
@DisplayName("CommunityKernelCryptoProvider — the server sends its certificate chain")
class CommunityServerCertificateChainTest {

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    private static final int HANDSHAKE_MAX_STEPS = 64;

    @TempDir
    Path material;

    @Test
    @DisplayName("a JDK client trusting only the root accepts the leaf and intermediate the server sends")
    void jdkClientTrustingOnlyTheRootAcceptsTheServedChain() throws Exception {
        Material issued = issue(material.resolve("served-chain"));

        int peerCertificates = handshakeWithJdkClient(issued.chain(), issued.leafKey(), issued.root().certificate());

        assertThat(peerCertificates)
                .as("the server sent the leaf and the intermediate, not the leaf alone")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a leaf-only certificate file is refused by a client that trusts only the root")
    void leafOnlyFileIsRefusedByRootOnlyClient() throws Exception {
        Material issued = issue(material.resolve("leaf-only-refused"));

        assertThatThrownBy(() -> handshakeWithJdkClient(
                issued.leafOnly(), issued.leafKey(), issued.root().certificate()))
                .isInstanceOf(SSLException.class);
    }

    @Test
    @DisplayName("a leaf-only certificate file is accepted by a client that trusts the intermediate")
    void leafOnlyFileIsAcceptedWhenTheIntermediateIsTrusted() throws Exception {
        Material issued = issue(material.resolve("leaf-only-accepted"));

        int peerCertificates = handshakeWithJdkClient(
                issued.leafOnly(), issued.leafKey(), issued.root().certificate(),
                issued.intermediate().certificate());

        assertThat(peerCertificates).as("the server sent the leaf alone").isEqualTo(1);
    }

    @Test
    @DisplayName("a Community client verifies the served chain through createClientTlsEngine")
    void communityClientVerifiesTheServedChain() throws Exception {
        Material issued = issue(material.resolve("community-client"));

        try (CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider();
             MemoryAllocator allocator = createAllocator();
             ServerSocketChannel listen = ServerSocketChannel.open()) {
            listen.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listen.getLocalAddress()).getPort();
            CommunityTlsEngine serverEngine = (CommunityTlsEngine) provider.createTlsEngine(
                    CryptoProviderConfig.httpsServer(issued.chain(), issued.leafKey()));

            BlockingPeerPair.Outcome outcome = BlockingPeerPair.drive(HANDSHAKE_TIMEOUT,
                    () -> serveOne(listen, serverEngine, allocator),
                    () -> dialCommunityClient(provider, issued.root().certificate(), port, allocator));

            release(serverEngine, outcome);
            awaitHandshake(outcome);
        }
    }

    /** The leaf, the intermediate and the root, with the files each case serves or trusts. */
    private record Material(
            TlsTestAuthority root,
            TlsTestAuthority intermediate,
            Path leafOnly,
            Path chain,
            Path leafKey) {
    }

    private static Material issue(Path directory) throws Exception {
        Files.createDirectories(directory);
        TlsTestAuthority root = TlsTestAuthority.root(directory, "chain-root");
        TlsTestAuthority intermediate = root.intermediate("chain-intermediate");
        TlsTestAuthority.Issued leaf = intermediate.issue(TlsTestAuthority.ip("127.0.0.1"));
        Path chain = directory.resolve("leaf-and-intermediate.crt");
        Files.writeString(chain, Files.readString(leaf.certificate())
                + Files.readString(intermediate.certificate()));
        return new Material(root, intermediate, leaf.certificate(), chain, leaf.privateKey());
    }

    /**
     * Serves one handshake from a Community server built on {@code certificateFile}, and returns the
     * JDK client's peer chain length. The JDK client trusts exactly {@code trusted}; a refusal
     * surfaces as the client's {@link SSLException}.
     */
    private static int handshakeWithJdkClient(Path certificateFile, Path key, Path... trusted) throws Exception {
        try (CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider();
             MemoryAllocator allocator = createAllocator();
             ServerSocketChannel listen = ServerSocketChannel.open()) {
            listen.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listen.getLocalAddress()).getPort();
            CommunityTlsEngine serverEngine = (CommunityTlsEngine) provider.createTlsEngine(
                    CryptoProviderConfig.httpsServer(certificateFile, key));
            int[] peerCertificates = new int[1];

            BlockingPeerPair.Outcome outcome = BlockingPeerPair.drive(HANDSHAKE_TIMEOUT,
                    () -> serveOne(listen, serverEngine, allocator),
                    () -> {
                        try (SSLSocket client = jdkClient(port, trusted)) {
                            client.startHandshake();
                            peerCertificates[0] = client.getSession().getPeerCertificates().length;
                        }
                    });

            release(serverEngine, outcome);
            awaitHandshake(outcome);
            return peerCertificates[0];
        }
    }

    /**
     * Accepts one connection on {@code listen} and drives the server engine's handshake over it.
     */
    private static void serveOne(ServerSocketChannel listen, CommunityTlsEngine engine,
                                 MemoryAllocator allocator) throws Exception {
        try (SocketChannel accepted = listen.accept();
             LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM)) {
            engine.bindFileDescriptor(SocketChannelFdAccess.requireFd(accepted));
            driveHandshake(engine, outbound);
        }
    }

    /**
     * A Community client that verifies {@code root} and expects {@code 127.0.0.1}, dialled over the
     * loopback listener on {@code port}.
     */
    private static void dialCommunityClient(CommunityKernelCryptoProvider provider, Path root, int port,
                                            MemoryAllocator allocator) throws Exception {
        CommunityTlsEngine client;
        try (CommunityTlsClientTrust trust = provider.openClientTrust(root)) {
            client = provider.createClientTlsEngine(
                    CryptoProviderConfig.tcpClient(), trust, TlsPeerIdentity.of("127.0.0.1"));
        }
        try (CommunityTlsEngine owned = client;
             SocketChannel dialed = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
             LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM)) {
            owned.bindFileDescriptor(SocketChannelFdAccess.requireFd(dialed));
            driveHandshake(owned, outbound);
        }
    }

    private static void driveHandshake(CommunityTlsEngine engine, LoanedBuffer outbound) {
        for (int step = 0; step < HANDSHAKE_MAX_STEPS && !engine.isHandshakeComplete(); step++) {
            engine.beginHandshake(outbound);
        }
        assertThat(engine.isHandshakeComplete())
                .as("the handshake completes within %d steps", HANDSHAKE_MAX_STEPS)
                .isTrue();
    }

    /**
     * Closes the server engine once both peers have returned. A peer still inside a native call when
     * the deadline passes keeps the engine: releasing it under that peer would be a use after free.
     */
    private static void release(CommunityTlsEngine serverEngine, BlockingPeerPair.Outcome outcome) {
        if (!outcome.timedOut()) {
            serverEngine.close();
        }
    }

    /**
     * Rethrows the client's failure as itself, since a refusal is the outcome under test, and fails
     * on a server failure only when the client side succeeded.
     */
    private static void awaitHandshake(BlockingPeerPair.Outcome outcome) throws Exception {
        assertThat(outcome.timedOut())
                .as("the handshake finishes within %s", HANDSHAKE_TIMEOUT)
                .isFalse();
        if (outcome.clientFailure() instanceof Exception clientFailure) {
            throw clientFailure;
        }
        if (outcome.clientFailure() != null) {
            throw new AssertionError("the client peer failed", outcome.clientFailure());
        }
        if (outcome.serverFailure() != null) {
            throw new AssertionError("the server peer failed", outcome.serverFailure());
        }
    }

    private static SSLSocket jdkClient(int port, Path... trusted) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        for (Path certificate : trusted) {
            try (InputStream in = Files.newInputStream(certificate)) {
                store.setCertificateEntry(certificate.getFileName().toString(), factory.generateCertificate(in));
            }
        }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, managers.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
