/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.support.BlockingPeerPair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code crypto.tls.minVersion} reaches the contexts of both a listener and a client carrier, and
 * a value the provider does not serve refuses the carrier at construction.
 *
 * <p>Each handshake runs against a JSSE peer that enables TLS 1.2 only, so a carrier with the key
 * absent (the {@code TLSv1.3} default) must refuse it and one with the key set to {@code TLSv1.2}
 * must complete.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community carriers — crypto.tls.minVersion sets the TLS floor")
class CommunityTlsMinVersionKeyTest {

    private static final byte[] PAYLOAD = {'p', 'i', 'n', 'g'};
    private static final char[] KEY_PASSWORD = "changeit".toCharArray();
    private static final String TLS_1_2 = "TLSv1.2";
    private static final String MIN_VERSION_KEY = "crypto.tls.minVersion";

    private static MemoryAllocator allocator;
    private static CommunityKernelCryptoProvider crypto;
    private static TlsTestCertificate certificate;

    @TempDir
    static Path material;

    @BeforeAll
    static void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        crypto = new CommunityKernelCryptoProvider();
        certificate = TlsTestCertificate.generateInto(material);
    }

    @AfterAll
    static void tearDown() {
        allocator.close();
    }

    @Test
    @DisplayName("a listener serves a TLSv1.2-only client when the key says TLSv1.2, and refuses it when absent")
    void listenerFollowsTheKey() throws Exception {
        assertThat(jsseClientAgainstListener(Map.of(MIN_VERSION_KEY, TLS_1_2)).protocol())
                .isEqualTo(TLS_1_2);

        Seen refused = jsseClientAgainstListener(Map.of());
        assertThat(refused.protocol()).isNull();
        assertThat(refused.failure()).isNotNull();
    }

    @Test
    @DisplayName("a client carrier completes with a TLSv1.2-only server when the key says TLSv1.2, and refuses it when absent")
    void clientFollowsTheKey() throws Exception {
        Seen served = clientAgainstJsseServer(true);
        assertThat(served.protocol()).isEqualTo(TLS_1_2);
        assertThat(served.failure()).isNull();

        Seen refused = clientAgainstJsseServer(false);
        assertThat(refused.protocol()).isNull();
    }

    @Test
    @DisplayName("a value the provider does not serve refuses the carrier, for every mode and before any listener")
    void invalidValueRefusesTheCarrier() {
        for (String invalid : new String[] {"TLSv1.4", "1.3", "tlsv1.3", "TLSv1.1"}) {
            for (TransportMode mode : new TransportMode[] {TransportMode.SERVER, TransportMode.CLIENT}) {
                TransportConfig config = mode == TransportMode.SERVER
                        ? new TransportConfig(mode, "127.0.0.1", 8443, 1,
                                certificate.certPath(), certificate.keyPath(), 1024, 30_000)
                        : new TransportConfig(mode, "127.0.0.1", 0, 1, null, null, 1024, 30_000);
                assertThatThrownBy(() -> carrier(config, Map.of(MIN_VERSION_KEY, invalid)))
                        .as("%s with %s", mode, invalid)
                        .isInstanceOf(TransportException.class)
                        .satisfies(e -> {
                            assertThat(e.getCause()).isInstanceOf(CryptoBootstrapException.class);
                            assertThat(((CryptoBootstrapException) e.getCause()).rawArgs()).contains(invalid);
                        });
            }
        }
    }

    @Test
    @DisplayName("a blank value keeps the default floor")
    void blankValueKeepsTheDefault() throws Exception {
        Seen refused = jsseClientAgainstListener(Map.of(MIN_VERSION_KEY, "  "));
        assertThat(refused.protocol()).isNull();
        assertThat(refused.failure()).isNotNull();
    }

    /** What a JSSE peer saw: the protocol it negotiated, or why it did not. */
    private record Seen(String protocol, Throwable failure) {
    }

    private static Seen jsseClientAgainstListener(Map<String, String> config) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        TransportEngine server = carrier(new TransportConfig(TransportMode.SERVER, "127.0.0.1", port, 1,
                certificate.certPath(), certificate.keyPath(), 1024, 30_000), config);
        server.setStreamHandler(stream -> {
            try (LoanedBuffer buffer = allocator.allocateNetwork(PAYLOAD.length)) {
                int read = stream.read(buffer.segment(), PAYLOAD.length);
                if (read > 0) {
                    stream.write(buffer.segment(), read);
                }
            }
        });
        try {
            server.start();
            SSLContext trusting = trustingContext(certificate.certificate());
            try (SSLSocket socket = (SSLSocket) trusting.getSocketFactory().createSocket()) {
                socket.setEnabledProtocols(new String[] {TLS_1_2});
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                socket.setSoTimeout(10_000);
                try {
                    socket.startHandshake();
                    socket.getOutputStream().write(PAYLOAD);
                    socket.getOutputStream().flush();
                    byte[] echoed = socket.getInputStream().readNBytes(PAYLOAD.length);
                    assertThat(echoed).containsExactly(PAYLOAD);
                    return new Seen(socket.getSession().getProtocol(), null);
                } catch (IOException refused) {
                    return new Seen(null, refused);
                }
            }
        } finally {
            server.close();
        }
    }

    /** A client carrier trusting the test certificate, dialling a JSSE server that enables TLS 1.2 only. */
    private static Seen clientAgainstJsseServer(boolean keySet) throws Exception {
        SSLContext keyed = keyedContext(certificate);
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<Throwable> clientOutcome = new AtomicReference<>();
        try (SSLServerSocket jsse = (SSLServerSocket) keyed.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            jsse.setEnabledProtocols(new String[] {TLS_1_2});
            jsse.setSoTimeout(15_000);
            Map<String, String> config = keySet
                    ? Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, certificate.certificate().toString(),
                            MIN_VERSION_KEY, TLS_1_2)
                    : Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, certificate.certificate().toString());
            try (TransportEngine client = carrier(new TransportConfig(TransportMode.CLIENT, "127.0.0.1", 0, 1,
                    null, null, 1024, 30_000), config)) {
                client.start();
                BlockingPeerPair.Outcome run = BlockingPeerPair.drive(Duration.ofSeconds(30),
                        () -> {
                            try (SSLSocket socket = (SSLSocket) jsse.accept()) {
                                socket.setSoTimeout(10_000);
                                socket.startHandshake();
                                protocol.set(socket.getSession().getProtocol());
                                byte[] in = socket.getInputStream().readNBytes(PAYLOAD.length);
                                socket.getOutputStream().write(in);
                                socket.getOutputStream().flush();
                            } catch (IOException refused) {
                                // the client refusing the handshake ends the accepted connection
                            }
                        },
                        () -> {
                            try (TransportStream stream = client.connect("127.0.0.1", jsse.getLocalPort()).openStream();
                                 LoanedBuffer inbound = allocator.allocateNetwork(PAYLOAD.length)) {
                                stream.write(MemorySegment.ofArray(PAYLOAD), PAYLOAD.length);
                                stream.read(inbound.segment(), PAYLOAD.length);
                            } catch (TlsHandshakeException refused) {
                                clientOutcome.set(refused);
                            }
                        });
                assertThat(run.timedOut()).isFalse();
                assertThat(run.serverFailure()).isNull();
                assertThat(run.clientFailure()).isNull();
            }
        }
        return new Seen(protocol.get(), clientOutcome.get());
    }

    private static TransportEngine carrier(TransportConfig config, Map<String, String> keys) {
        ConfigProvider provider = new MapConfigProvider(keys, Map.of());
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, provider)
                .call(() -> new NativeTcpTransportProvider().createEngine(config));
    }

    private static X509Certificate readCertificate(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static SSLContext trustingContext(Path file) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("server", readCertificate(file));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static SSLContext keyedContext(TlsTestCertificate material) throws Exception {
        X509Certificate leaf = readCertificate(material.certificate());
        String pem = Files.readString(material.privateKey(), StandardCharsets.US_ASCII);
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
