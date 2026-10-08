/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.support.BlockingPeerPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A context refuses a peer that negotiates below {@link CryptoProviderConfig#minimumTlsVersion()},
 * in both directions, and refuses a configuration that names no version it serves.
 *
 * <p>Each handshake runs against a JSSE peer that enables one protocol, so the version actually
 * negotiated is read from the peer rather than inferred. The cases that set the floor to
 * {@code TLSv1.2} are the ones a context hard-wired to {@code TLSv1.3} fails.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community TLS minimum protocol version")
class CommunityTlsMinimumVersionTest {

    private static final String TLS_1_2 = "TLSv1.2";
    private static final String TLS_1_3 = "TLSv1.3";
    private static final int HANDSHAKE_MAX_STEPS = 64;
    private static final Duration PEER_TIMEOUT = Duration.ofSeconds(20);
    private static final char[] KEY_PASSWORD = "changeit".toCharArray();

    @TempDir
    /* default */ static Path tlsMaterialDir;

    /** What one handshake showed: whether the Community side completed, and what the JSSE peer saw. */
    private record Outcome(boolean communityCompleted, String jsseProtocol, Throwable jsseFailure) {
    }

    @Test
    @DisplayName("a listener with the default floor refuses a TLSv1.2-only client and serves a TLSv1.3 client")
    void listenerDefaultFloorRefusesTls12() throws Exception {
        Outcome refused = listenerVersus(TLS_1_3, TLS_1_2);
        assertThat(refused.communityCompleted()).isFalse();
        assertThat(refused.jsseProtocol()).isNull();
        assertThat(refused.jsseFailure()).isNotNull();

        Outcome served = listenerVersus(TLS_1_3, TLS_1_3);
        assertThat(served.communityCompleted()).isTrue();
        assertThat(served.jsseProtocol()).isEqualTo(TLS_1_3);
    }

    @Test
    @DisplayName("a listener configured with a TLSv1.2 floor serves a TLSv1.2-only client")
    void listenerTls12FloorServesTls12() throws Exception {
        Outcome outcome = listenerVersus(TLS_1_2, TLS_1_2);
        assertThat(outcome.communityCompleted()).isTrue();
        assertThat(outcome.jsseProtocol()).isEqualTo(TLS_1_2);
    }

    @Test
    @DisplayName("a client with the default floor refuses a TLSv1.2-only server and uses a TLSv1.3 server")
    void clientDefaultFloorRefusesTls12() throws Exception {
        Outcome refused = clientVersus(TLS_1_3, TLS_1_2);
        assertThat(refused.communityCompleted()).isFalse();
        assertThat(refused.jsseProtocol()).isNull();

        Outcome served = clientVersus(TLS_1_3, TLS_1_3);
        assertThat(served.communityCompleted()).isTrue();
        assertThat(served.jsseProtocol()).isEqualTo(TLS_1_3);
    }

    @Test
    @DisplayName("a client configured with a TLSv1.2 floor completes against a TLSv1.2-only server")
    void clientTls12FloorServesTls12() throws Exception {
        Outcome outcome = clientVersus(TLS_1_2, TLS_1_2);
        assertThat(outcome.communityCompleted()).isTrue();
        assertThat(outcome.jsseProtocol()).isEqualTo(TLS_1_2);
    }

    @Test
    @DisplayName("a minimumTlsVersion that names no served version is refused by both entry points")
    void unknownVersionIsRefused() {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        try (CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider();
             CommunityTlsClientTrust trust = provider.openClientTrust(certificate.certificate())) {
            for (String unknown : List.of("TLSv1.4", "1.3", "tlsv1.3", "TLSv1.1", "SSLv3")) {
                CryptoProviderConfig server = new CryptoProviderConfig(CryptoProviderConfig.Protocol.TCP_TLS,
                        certificate.certificate(), certificate.privateKey(), List.of(), 0, false, unknown);
                CryptoProviderConfig client = new CryptoProviderConfig(CryptoProviderConfig.Protocol.TCP_TLS,
                        null, null, List.of(), 0, false, unknown);

                assertThatThrownBy(() -> provider.createTlsEngine(server))
                        .as("createTlsEngine(server, %s)", unknown)
                        .isInstanceOfSatisfying(CryptoBootstrapException.class,
                                refused -> assertThat(refused.rawArgs()).contains(unknown));
                assertThatThrownBy(() -> provider.createTlsEngine(client))
                        .as("createTlsEngine(client, %s)", unknown)
                        .isInstanceOf(CryptoBootstrapException.class);
                assertThatThrownBy(() -> provider.createClientTlsEngine(client, trust, TlsPeerIdentity.of("127.0.0.1")))
                        .as("createClientTlsEngine(%s)", unknown)
                        .isInstanceOfSatisfying(CryptoBootstrapException.class,
                                refused -> assertThat(refused.rawArgs()).contains(unknown));
            }
        }
    }

    /** A Community server engine with {@code floor}, against a JSSE client that enables only {@code peerProtocol}. */
    private static Outcome listenerVersus(String floor, String peerProtocol) throws Exception {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        CryptoProviderConfig config = new CryptoProviderConfig(CryptoProviderConfig.Protocol.TCP_TLS,
                certificate.certificate(), certificate.privateKey(), List.of(), 0, false, floor);
        try (CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider();
             MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
             CommunityTlsEngine engine = (CommunityTlsEngine) provider.createTlsEngine(config);
             ServerSocketChannel listen = ServerSocketChannel.open()) {
            listen.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listen.getLocalAddress()).getPort();
            SSLContext trusting = trustingContext(certificate.certificate());

            JssePeer jsse = new JssePeer();
            AtomicBoolean completed = new AtomicBoolean();
            BlockingPeerPair.Outcome run = BlockingPeerPair.drive(PEER_TIMEOUT,
                    () -> {
                        try (SocketChannel accepted = listen.accept();
                             LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM)) {
                            engine.bindFileDescriptor(SocketChannelFdAccess.requireFd(accepted));
                            completed.set(drive(engine, outbound));
                        }
                    },
                    () -> jsse.connect(trusting, port, peerProtocol));
            return outcome(run, completed.get(), jsse);
        }
    }

    /** A Community client engine with {@code floor}, against a JSSE server that enables only {@code peerProtocol}. */
    private static Outcome clientVersus(String floor, String peerProtocol) throws Exception {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        CryptoProviderConfig config = new CryptoProviderConfig(CryptoProviderConfig.Protocol.TCP_TLS,
                null, null, List.of(), 0, false, floor);
        SSLContext keyed = keyedContext(certificate);
        try (CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider();
             MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
             SSLServerSocket jsse = (SSLServerSocket) keyed.getServerSocketFactory()
                     .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            jsse.setEnabledProtocols(new String[] {peerProtocol});
            jsse.setSoTimeout(15_000);
            JssePeer peer = new JssePeer();
            AtomicBoolean completed = new AtomicBoolean();
            CommunityTlsEngine engine;
            try (CommunityTlsClientTrust trust = provider.openClientTrust(certificate.certificate())) {
                engine = provider.createClientTlsEngine(config, trust, TlsPeerIdentity.of("127.0.0.1"));
            }
            try (CommunityTlsEngine owned = engine) {
                BlockingPeerPair.Outcome run = BlockingPeerPair.drive(PEER_TIMEOUT,
                        () -> peer.accept(jsse),
                        () -> {
                            try (SocketChannel channel = SocketChannel.open();
                                 LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM)) {
                                channel.connect(new InetSocketAddress("127.0.0.1", jsse.getLocalPort()));
                                owned.bindFileDescriptor(SocketChannelFdAccess.requireFd(channel));
                                completed.set(drive(owned, outbound));
                            }
                        });
                return outcome(run, completed.get(), peer);
            }
        }
    }

    /** Runs the handshake until it completes or the engine refuses it. */
    private static boolean drive(CommunityTlsEngine engine, LoanedBuffer outbound) {
        try {
            for (int i = 0; i < HANDSHAKE_MAX_STEPS && !engine.isHandshakeComplete(); i++) {
                engine.beginHandshake(outbound);
            }
        } catch (RuntimeException refused) {
            return false;
        }
        return engine.isHandshakeComplete();
    }

    private static Outcome outcome(BlockingPeerPair.Outcome run, boolean communityCompleted, JssePeer jsse) {
        assertThat(run.timedOut()).as("the handshake must end, completed or refused").isFalse();
        assertThat(run.serverFailure()).isNull();
        assertThat(run.clientFailure()).isNull();
        return new Outcome(communityCompleted, jsse.protocol.get(), jsse.failure.get());
    }

    /** The JSSE side of one handshake; it records what it saw instead of throwing. */
    private static final class JssePeer {

        private final AtomicReference<String> protocol = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        void connect(SSLContext context, int port, String enabledProtocol) {
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                socket.setEnabledProtocols(new String[] {enabledProtocol});
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                socket.setSoTimeout(10_000);
                socket.startHandshake();
                protocol.set(socket.getSession().getProtocol());
            } catch (IOException refused) {
                failure.set(refused);
            }
        }

        void accept(SSLServerSocket listener) {
            try (SSLSocket socket = (SSLSocket) listener.accept()) {
                socket.setSoTimeout(10_000);
                socket.startHandshake();
                protocol.set(socket.getSession().getProtocol());
            } catch (IOException refused) {
                failure.set(refused);
            }
        }
    }

    private static X509Certificate readCertificate(Path certificate) throws Exception {
        try (InputStream in = Files.newInputStream(certificate)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static SSLContext trustingContext(Path certificate) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("server", readCertificate(certificate));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static SSLContext keyedContext(TlsTestCertificate certificate) throws Exception {
        X509Certificate leaf = readCertificate(certificate.certificate());
        String pem = Files.readString(certificate.privateKey(), StandardCharsets.US_ASCII);
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance(leaf.getPublicKey().getAlgorithm())
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("leaf", key, KEY_PASSWORD, new Certificate[] {leaf});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, KEY_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }
}
