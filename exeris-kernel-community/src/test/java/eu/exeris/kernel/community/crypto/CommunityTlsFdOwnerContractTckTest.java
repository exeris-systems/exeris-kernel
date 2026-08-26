/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Community: FD-owner bind contract TCK")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CommunityTlsFdOwnerContractTckTest {

    @Test
    @DisplayName("runtime probe recognizes direct channel FD accessor when available")
    void runtimeProbeRecognizesDirectChannelFdAccessorWhenAvailable() throws Exception {
        try (SocketChannel channel = SocketChannel.open()) {
            if (hasDirectChannelFdAccessor(channel)) {
                assertThat(SocketChannelFdAccess.isRuntimeFdAccessAvailable()).isTrue();
                assertThat(SocketChannelFdAccess.requireFd(channel)).isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("bindFileDescriptor before beginHandshake allows handshake progression")
    void bindFileDescriptorBeforeBeginHandshakeAllowsHandshakeProgression() throws Exception {
        CertKeyPaths certKey = resolveCertKeyOrSkip();
        assumeSocketFdAccessOnLoopbackOrSkip();

        try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
             MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
             CommunityTlsEngine serverEngine = (CommunityTlsEngine) provider.createTlsEngine(
                     CryptoProviderConfig.httpsServer(certKey.cert(), certKey.key()));
             CommunityTlsEngine clientEngine = (CommunityTlsEngine) provider.createTlsEngine(
                     CryptoProviderConfig.tcpClient());
             ServerSocketChannel listener = ServerSocketChannel.open();
             SocketChannel clientChannel = SocketChannel.open()) {

            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            clientChannel.connect(new InetSocketAddress("127.0.0.1", port));

            try (SocketChannel accepted = listener.accept();
                 LoanedBuffer serverOutbound = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer clientOutbound = allocator.allocate(AllocationHint.MEDIUM)) {

                accepted.configureBlocking(false);
                clientChannel.configureBlocking(false);

                serverEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(accepted));
                clientEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(clientChannel));

                TlsStatus serverStatus = serverEngine.beginHandshake(serverOutbound);
                TlsStatus clientStatus = clientEngine.beginHandshake(clientOutbound);

                assertThat(serverStatus).isNotEqualTo(TlsStatus.CLOSED);
                assertThat(clientStatus).isNotEqualTo(TlsStatus.CLOSED);
            }
        }
    }

    @Test
    @DisplayName("bindFileDescriptor is idempotent for same descriptor")
    void bindFileDescriptorIsIdempotentForSameDescriptor() throws Exception {
        CertKeyPaths certKey = resolveCertKeyOrSkip();
        assumeSocketFdAccessOnLoopbackOrSkip();

        try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
             CommunityTlsEngine engine = (CommunityTlsEngine) provider.createTlsEngine(
                     CryptoProviderConfig.httpsServer(certKey.cert(), certKey.key()));
             ServerSocketChannel listener = ServerSocketChannel.open();
             SocketChannel clientChannel = SocketChannel.open()) {

            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            clientChannel.connect(new InetSocketAddress("127.0.0.1", port));

            try (SocketChannel accepted = listener.accept()) {
                accepted.configureBlocking(false);
                clientChannel.configureBlocking(false);

                int descriptor = SocketChannelFdAccess.requireFd(accepted);
                engine.bindFileDescriptor(descriptor);
                assertThatCode(() -> engine.bindFileDescriptor(descriptor)).doesNotThrowAnyException();
            }
        }
    }

    private static CommunityKernelCryptoProvider createProviderOrSkip() {
        try {
            return new CommunityKernelCryptoProvider();
        } catch (Exception | Error exception) {
            assumeTrue(false, "OpenSSL provider not available - skipping FD-owner contract test");
            throw new IllegalStateException("unreachable", exception);
        }
    }

    private static CertKeyPaths resolveCertKeyOrSkip() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path moduleDir = "exeris-kernel-community".equals(String.valueOf(cwd.getFileName()))
                ? cwd
                : cwd.resolve("exeris-kernel-community").normalize();
        Path cert = moduleDir.resolve("../native-libs/certs/server.crt").normalize();
        Path key = moduleDir.resolve("../native-libs/certs/server.key").normalize();

        assumeTrue(Files.isRegularFile(cert) && Files.isRegularFile(key),
                "TLS cert/key not found under ../native-libs/certs - skipping FD-owner contract test");

        return new CertKeyPaths(cert, key);
    }

    private static void assumeSocketFdAccessOnLoopbackOrSkip() {
        assumeTrue(SocketChannelFdAccess.isRuntimeFdAccessAvailable(),
                "SocketChannel FD access unavailable - skipping FD-owner contract test");

        try (ServerSocketChannel listener = ServerSocketChannel.open();
             SocketChannel client = SocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            try (SocketChannel accepted = listener.accept()) {
                assumeTrue(SocketChannelFdAccess.canResolveFd(client)
                                && SocketChannelFdAccess.canResolveFd(accepted),
                        "SocketChannel FD cannot be resolved on loopback pair - skipping FD-owner contract test");
            }
        } catch (Exception _) {
            assumeTrue(false, "Unable to create loopback socket pair - skipping FD-owner contract test");
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static boolean hasDirectChannelFdAccessor(SocketChannel channel) {
        Class<?> current = channel.getClass();
        while (current != null) {
            for (String methodName : new String[]{"getFDVal", "fdVal"}) {
                try {
                    Method method = current.getDeclaredMethod(methodName);
                    if (!method.trySetAccessible()) {
                        return false;
                    }
                    Object value = method.invoke(channel);
                    return value instanceof Number number && number.intValue() >= 0;
                } catch (NoSuchMethodException _) {
                    // try next candidate
                } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException _) {
                    return false;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static final class CertKeyPaths {

        private final Path cert;
        private final Path key;

        private CertKeyPaths(Path cert, Path key) {
            this.cert = cert;
            this.key = key;
        }

        private Path cert() {
            return cert;
        }

        private Path key() {
            return key;
        }
    }
}
