/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A client with no configured trust verifies against OpenSSL's default trust, which
 * {@code SSL_CERT_FILE} and {@code SSL_CERT_DIR} name; a configured trust file replaces that default
 * rather than adding to it.
 *
 * <p>Runs only in the {@code default-trust} Surefire execution of this module, which points
 * {@code SSL_CERT_FILE} at a file this class writes and {@code SSL_CERT_DIR} at an empty directory:
 * {@code mvn -pl exeris-kernel-community surefire:test@default-trust}, or any build that runs the
 * {@code test} phase. Anywhere else it fails rather than skips, because without those variables it
 * would test the host's trust store instead.
 */
@Tag("default-trust")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community carriers — OpenSSL's default trust, and a trust file that replaces it")
class CommunityTlsDefaultTrustTest {

    private static final String POSTURE_EVENT = "eu.exeris.kernel.transport.TransportTlsClientPosture";
    private static final int X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY = 20;
    private static final byte[] PAYLOAD = {'d', 'e', 'f'};

    private static MemoryAllocator allocator;
    private static CommunityKernelCryptoProvider crypto;
    private static TlsTestAuthority defaultAuthority;
    private static TlsTestAuthority otherAuthority;
    private static String defaultCertFile;
    private static String localhostAddress;

    @TempDir
    static Path material;

    @BeforeAll
    static void writeTheDefaultTrust() throws IOException {
        defaultCertFile = System.getenv("SSL_CERT_FILE");
        String defaultCertDir = System.getenv("SSL_CERT_DIR");
        assertThat(defaultCertFile)
                .as("run through the default-trust Surefire execution, which sets SSL_CERT_FILE")
                .isNotBlank();
        assertThat(defaultCertDir)
                .as("run through the default-trust Surefire execution, which sets SSL_CERT_DIR")
                .isNotBlank();
        defaultAuthority = TlsTestAuthority.root(material, "env-default-ca");
        otherAuthority = TlsTestAuthority.root(material, "other-ca");
        Path file = Path.of(defaultCertFile);
        Files.createDirectories(file.getParent());
        Files.copy(defaultAuthority.certificate(), file, StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(Path.of(defaultCertDir));

        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        crypto = new CommunityKernelCryptoProvider();
        localhostAddress = InetAddress.getByName("localhost").getHostAddress();
    }

    @AfterAll
    static void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    @Test
    @DisplayName("no configured trust: a leaf from the CA in SSL_CERT_FILE is accepted")
    void defaultTrustAcceptsTheEnvironmentsCa() throws Exception {
        try (Server server = server(defaultAuthority.issue(TlsTestAuthority.dns("localhost")));
             TransportEngine client = client(new MapConfigProvider(Map.of(), Map.of()))) {
            TransportConnection connection = client.connect("localhost", server.port());
            try (TransportStream stream = connection.openStream();
                 LoanedBuffer inbound = allocator.allocateNetwork(PAYLOAD.length)) {
                stream.write(MemorySegment.ofArray(PAYLOAD), PAYLOAD.length);
                assertThat(stream.read(inbound.segment(), PAYLOAD.length)).isPositive();
            }
        }
    }

    @Test
    @DisplayName("a configured trust file replaces the default: the same leaf is refused with 20")
    void configuredTrustReplacesTheDefault() throws Exception {
        ConfigProvider otherOnly = new MapConfigProvider(
                Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, otherAuthority.certificate().toString()),
                Map.of());
        try (Server server = server(defaultAuthority.issue(TlsTestAuthority.dns("localhost")));
             TransportEngine client = client(otherOnly)) {
            TransportConnection connection = client.connect("localhost", server.port());
            try (TransportStream stream = connection.openStream()) {
                assertThatThrownBy(() -> stream.write(MemorySegment.ofArray(PAYLOAD), PAYLOAD.length))
                        .isInstanceOf(TlsHandshakeException.class)
                        .satisfies(e -> assertThat(((TlsHandshakeException) e).rawArgs())
                                .containsExactly(X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY,
                                        TlsFailureDetail.PEER_VERIFICATION_FAILED));
            }
        }
    }

    @Test
    @DisplayName("the posture reports the default file SSL_CERT_FILE names")
    void postureReportsTheEnvironmentsFile() throws Exception {
        Path dump = Files.createTempFile(material, "posture", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(POSTURE_EVENT).withoutStackTrace();
            recording.start();
            client(new MapConfigProvider(Map.of(), Map.of())).close();
            recording.stop();
            recording.dump(dump);
        }
        List<RecordedEvent> postures = RecordingFile.readAllEvents(dump).stream()
                .filter(event -> POSTURE_EVENT.equals(event.getEventType().getName()))
                .toList();

        assertThat(postures).hasSize(1);
        assertThat(postures.getFirst().getString("trustSource")).isEqualTo("SYSTEM_DEFAULT");
        assertThat(postures.getFirst().getString("defaultCertFile")).isEqualTo(defaultCertFile);
        assertThat(postures.getFirst().getBoolean("defaultTrustPresent")).isTrue();
    }

    /** A started TLS echo listener and its port. */
    private record Server(TransportEngine engine, int port) implements AutoCloseable {
        @Override
        public void close() {
            engine.close();
        }
    }

    private static Server server(TlsTestAuthority.Issued leaf) throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        TransportConfig config = new TransportConfig(TransportMode.SERVER, localhostAddress, port, 1,
                leaf.certificate().toString(), leaf.privateKey().toString(), 1024, 30_000);
        TransportEngine engine = within(new MapConfigProvider(Map.of(), Map.of()), config);
        engine.setStreamHandler(stream -> {
            try (LoanedBuffer buffer = allocator.allocateNetwork(PAYLOAD.length)) {
                int read = stream.read(buffer.segment(), PAYLOAD.length);
                if (read > 0) {
                    stream.write(buffer.segment(), read);
                }
            }
        });
        engine.start();
        return new Server(engine, port);
    }

    private static TransportEngine client(ConfigProvider config) {
        TransportEngine engine = within(config, new TransportConfig(TransportMode.CLIENT, "127.0.0.1", 0, 1,
                null, null, 1024, 30_000));
        engine.start();
        return engine;
    }

    private static TransportEngine within(ConfigProvider config, TransportConfig transportConfig) {
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, config)
                .call(() -> new NativeTcpTransportProvider().createEngine(transportConfig));
    }
}
