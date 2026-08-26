/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@EnabledOnOs(OS.LINUX)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("IT: CommunityTlsEngine loopback integration with JFR assertions")
class CommunityTlsEngineLoopbackIntegrationTest {

    private static final String BOOTSTRAP_EVENT = "eu.exeris.kernel.crypto.CommunityProviderBootstrap";
    private static final String HANDSHAKE_EVENT = "eu.exeris.kernel.crypto.CommunityTlsHandshake";

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    private static final int HANDSHAKE_MAX_STEPS = 64;
    private static final int PAYLOAD_SIZE = 512;

    @Test
    @DisplayName("handshake completes and both engines become ACTIVE")
    void handshakeCompletesAndBothEnginesBecomeActive() throws Exception {
        CertKeyPaths certKey = resolveCertKeyOrSkip();

        try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
             MemoryAllocator allocator = createAllocator();
             CommunityTlsEngine serverEngine = createServerEngine(provider, certKey);
             CommunityTlsEngine clientEngine = createClientEngine(provider, false);
             ServerSocketChannel listen = ServerSocketChannel.open();
             SocketChannel clientChannel = SocketChannel.open()) {

            listen.configureBlocking(true);
            listen.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listen.getLocalAddress()).getPort();

            clientChannel.configureBlocking(true);
            clientChannel.connect(new InetSocketAddress("127.0.0.1", port));

            try (SocketChannel accepted = listen.accept();
                 LoanedBuffer serverHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer clientHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM)) {

                serverEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(accepted));
                clientEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(clientChannel));

                driveHandshakeConcurrently(
                        serverEngine,
                        serverHandshakeOutbound,
                        clientEngine,
                        clientHandshakeOutbound);

                assertThat(serverEngine.isHandshakeComplete()).isTrue();
                assertThat(clientEngine.isHandshakeComplete()).isTrue();
                assertThat(serverEngine.negotiatedProtocol()).isNull();
                assertThat(clientEngine.negotiatedProtocol()).isNull();
            }
        }
    }

    @Test
    @DisplayName("full round-trip both directions data matches for 512-byte payload")
    void fullRoundTripBothDirectionsDataMatches() throws Exception {
        CertKeyPaths certKey = resolveCertKeyOrSkip();

        try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
             MemoryAllocator allocator = createAllocator();
             CommunityTlsEngine serverEngine = createServerEngine(provider, certKey);
             CommunityTlsEngine clientEngine = createClientEngine(provider, false);
             ServerSocketChannel listen = ServerSocketChannel.open();
             SocketChannel clientChannel = SocketChannel.open()) {

            listen.configureBlocking(true);
            listen.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listen.getLocalAddress()).getPort();

            clientChannel.configureBlocking(true);
            clientChannel.connect(new InetSocketAddress("127.0.0.1", port));

            try (SocketChannel accepted = listen.accept();
                 LoanedBuffer serverHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer clientHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer serverPlaintext = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer clientPlaintext = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer serverCiphertext = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer clientCiphertext = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer serverDecrypted = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer clientDecrypted = allocator.allocate(AllocationHint.MEDIUM)) {

                serverEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(accepted));
                clientEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(clientChannel));
                driveHandshakeConcurrently(
                        serverEngine,
                        serverHandshakeOutbound,
                        clientEngine,
                        clientHandshakeOutbound);

                fill(serverPlaintext, (byte) 0x3C, PAYLOAD_SIZE);
                TlsStatus serverWrapStatus = serverEngine.wrap(serverPlaintext, serverCiphertext);
                assertThat(serverWrapStatus).isEqualTo(TlsStatus.OK);
                assertThat(serverCiphertext.size()).isZero();

                TlsStatus clientUnwrapStatus = clientEngine.unwrap(clientCiphertext, clientDecrypted);
                assertThat(clientUnwrapStatus).isEqualTo(TlsStatus.OK);
                assertPayload(clientDecrypted, (byte) 0x3C, PAYLOAD_SIZE);

                fill(clientPlaintext, (byte) 0x7A, PAYLOAD_SIZE);
                TlsStatus clientWrapStatus = clientEngine.wrap(clientPlaintext, clientCiphertext);
                assertThat(clientWrapStatus).isEqualTo(TlsStatus.OK);
                assertThat(clientCiphertext.size()).isZero();

                TlsStatus serverUnwrapStatus = serverEngine.unwrap(serverCiphertext, serverDecrypted);
                assertThat(serverUnwrapStatus).isEqualTo(TlsStatus.OK);
                assertPayload(serverDecrypted, (byte) 0x7A, PAYLOAD_SIZE);
            }
        }
    }

    @Test
    @DisplayName("JFR emits bootstrap and successful handshake events")
    void jfrEmitsBootstrapAndHandshakeEventsOnSuccess() throws Exception {
        CertKeyPaths certKey = resolveCertKeyOrSkip();
        Path recordingPath = Files.createTempFile("community-loopback-success-", ".jfr");

        try {
            try (Recording recording = new Recording()) {
                recording.enable(BOOTSTRAP_EVENT).withoutStackTrace();
                recording.enable(HANDSHAKE_EVENT).withoutStackTrace();
                recording.start();

                try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
                     MemoryAllocator allocator = createAllocator();
                     CommunityTlsEngine serverEngine = createServerEngine(provider, certKey);
                     CommunityTlsEngine clientEngine = createClientEngine(provider, true);
                     ServerSocketChannel listen = ServerSocketChannel.open();
                     SocketChannel clientChannel = SocketChannel.open()) {

                    listen.configureBlocking(true);
                    listen.bind(new InetSocketAddress("127.0.0.1", 0));
                    int port = ((InetSocketAddress) listen.getLocalAddress()).getPort();

                    clientChannel.configureBlocking(true);
                    clientChannel.connect(new InetSocketAddress("127.0.0.1", port));

                    try (SocketChannel accepted = listen.accept();
                         LoanedBuffer serverHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM);
                         LoanedBuffer clientHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM)) {

                        serverEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(accepted));
                        clientEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(clientChannel));
                        driveHandshakeConcurrently(
                                serverEngine,
                                serverHandshakeOutbound,
                                clientEngine,
                                clientHandshakeOutbound);
                    }
                }

                recording.stop();
                recording.dump(recordingPath);
            }

            List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath);

            RecordedEvent bootstrapEvent = events.stream()
                    .filter(event -> BOOTSTRAP_EVENT.equals(event.getEventType().getName()))
                    .findFirst()
                    .orElse(null);
            assertThat(bootstrapEvent)
                    .as("bootstrap event must be present")
                    .isNotNull();
            assertThat(bootstrapEvent.getString("providerName")).contains("Community");
            assertThat(bootstrapEvent.getLong("durationNs")).isGreaterThanOrEqualTo(0L);

            boolean hasCompleteHandshake = events.stream()
                    .filter(event -> HANDSHAKE_EVENT.equals(event.getEventType().getName()))
                    .anyMatch(event -> event.getBoolean("complete"));
            assertThat(hasCompleteHandshake)
                    .as("at least one handshake event with complete=true must be recorded")
                    .isTrue();
        } finally {
            Files.deleteIfExists(recordingPath);
        }
    }

    @Test
    @DisplayName("JFR emits handshake failure event when beginHandshake is called before bind")
    void jfrEmitsHandshakeFailureEventWhenBeginBeforeBind() throws Exception {
        Path recordingPath = Files.createTempFile("community-loopback-failure-", ".jfr");

        try {
            try (Recording recording = new Recording()) {
                recording.enable(HANDSHAKE_EVENT).withoutStackTrace();
                recording.start();

                try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
                     MemoryAllocator allocator = createAllocator();
                     CommunityTlsEngine clientEngine = createClientEngine(provider, true);
                     LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM)) {

                    assertThatThrownBy(() -> clientEngine.beginHandshake(outbound))
                            .isInstanceOf(TlsHandshakeException.class);
                }

                recording.stop();
                recording.dump(recordingPath);
            }

            List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath);
            boolean hasFailureHandshake = events.stream()
                    .filter(event -> HANDSHAKE_EVENT.equals(event.getEventType().getName()))
                    .anyMatch(event -> !event.getBoolean("complete")
                            && event.getInt("opensslError") == -1);

            assertThat(hasFailureHandshake)
                    .as("failure path must emit complete=false and opensslError=-1")
                    .isTrue();
        } finally {
            Files.deleteIfExists(recordingPath);
        }
    }

    private static CommunityKernelCryptoProvider createProviderOrSkip() {
        try {
            return new CommunityKernelCryptoProvider();
        } catch (CryptoBootstrapException exception) {
            assumeTrue(false, "OpenSSL 3.x not available on this host - skipping integration test");
            throw new IllegalStateException("unreachable", exception);
        }
    }

    private static MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    private static CommunityTlsEngine createServerEngine(
            CommunityKernelCryptoProvider provider,
            CertKeyPaths certKey) {
        return (CommunityTlsEngine) provider.createTlsEngine(
                CryptoProviderConfig.httpsServer(certKey.cert(), certKey.key()));
    }

    private static CommunityTlsEngine createClientEngine(
            CommunityKernelCryptoProvider provider,
            boolean jfrEnabled) {
        CryptoProviderConfig clientConfig = new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                null,
                null,
                List.of(),
                0,
                jfrEnabled,
                CryptoProviderConfig.TLS_1_3);
        return (CommunityTlsEngine) provider.createTlsEngine(clientConfig);
    }

    private static CertKeyPaths resolveCertKeyOrSkip() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path moduleDir = "exeris-kernel-community".equals(String.valueOf(cwd.getFileName()))
                ? cwd
                : cwd.resolve("exeris-kernel-community").normalize();

        Path cert = moduleDir.resolve("../native-libs/certs/server.crt").normalize();
        Path key = moduleDir.resolve("../native-libs/certs/server.key").normalize();

        assumeTrue(Files.isRegularFile(cert) && Files.isRegularFile(key),
                "TLS test cert/key not found at ../native-libs/certs - skipping integration test");

        return new CertKeyPaths(cert, key);
    }

    private static void driveHandshakeConcurrently(
            CommunityTlsEngine serverEngine,
            LoanedBuffer serverOutbound,
            CommunityTlsEngine clientEngine,
            LoanedBuffer clientOutbound) {
        Instant deadline = Instant.now().plus(HANDSHAKE_TIMEOUT);

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.awaitAll(),
                config -> config
                        .withThreadFactory(Thread.ofPlatform().daemon(true).factory())
                        .withTimeout(HANDSHAKE_TIMEOUT))) {

            var serverTask = scope.fork(() -> {
                driveSingleEngine(serverEngine, serverOutbound, deadline);
                return null;
            });
            var clientTask = scope.fork(() -> {
                driveSingleEngine(clientEngine, clientOutbound, deadline);
                return null;
            });

            scope.join();

            if (Instant.now().isAfter(deadline) || scope.isCancelled()) {
                throw new AssertionError("TLS handshake timed out after " + HANDSHAKE_TIMEOUT.getSeconds() + "s");
            }
            if (serverTask.state() == StructuredTaskScope.Subtask.State.FAILED) {
                throw new AssertionError("Server handshake failed", serverTask.exception());
            }
            if (clientTask.state() == StructuredTaskScope.Subtask.State.FAILED) {
                throw new AssertionError("Client handshake failed", clientTask.exception());
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new AssertionError("TLS handshake interrupted", interruptedException);
        }

        assertThat(serverEngine.isHandshakeComplete()).isTrue();
        assertThat(clientEngine.isHandshakeComplete()).isTrue();
    }

    private static void driveSingleEngine(
            CommunityTlsEngine tlsEngine,
            LoanedBuffer outbound,
            Instant deadline) {
        for (int i = 0; i < HANDSHAKE_MAX_STEPS && !tlsEngine.isHandshakeComplete(); i++) {
            tlsEngine.beginHandshake(outbound);
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("TLS handshake deadline exceeded");
            }
        }
        assertThat(tlsEngine.isHandshakeComplete())
                .as("TLS handshake did not complete within max steps")
                .isTrue();
    }

    private static void fill(LoanedBuffer buffer, byte value, int size) {
        buffer.segment().asSlice(0, size).fill(value);
        buffer.setSize(size);
    }

    private static void assertPayload(LoanedBuffer actual, byte expectedByte, int expectedSize) {
        assertThat(actual.size()).isEqualTo(expectedSize);
        MemorySegment expected = MemorySegment.ofArray(new byte[expectedSize]);
        expected.fill(expectedByte);
        long mismatch = actual.segment().asSlice(0, expectedSize).mismatch(expected);
        assertThat(mismatch)
                .as("decrypted payload must match expected pattern")
                .isEqualTo(-1L);
    }

    private record CertKeyPaths(Path cert, Path key) {
    }
}
