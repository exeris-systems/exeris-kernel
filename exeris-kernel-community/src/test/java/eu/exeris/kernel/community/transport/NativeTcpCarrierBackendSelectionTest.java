/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Community: NativeTcpCarrier backend selection seam")
class NativeTcpCarrierBackendSelectionTest {

    private static final String SOCKET_BACKEND_PROPERTY = "exeris.community.transport.socket.backend";
    private static final String SOCKET_BACKEND_ALIAS_PROPERTY = "SOCKET_BACKEND_ENV";
    private static final String SOCKET_BACKEND_AUTO = "auto";
    private static final String SOCKET_BACKEND_NIO = "nio";
    private static final String SOCKET_BACKEND_POSIX_HYBRID = "posix-hybrid";
    private static final String LOOPBACK_HOST = InetAddress.getLoopbackAddress().getHostAddress();
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @AfterEach
    @SuppressWarnings("unused")
    void clearBackendOverride() {
        System.clearProperty(SOCKET_BACKEND_PROPERTY);
        System.clearProperty(SOCKET_BACKEND_ALIAS_PROPERTY);
    }

    @Test
    @DisplayName("auto mode exposes an honest hybrid backend name when POSIX syscall I/O is armed")
    void autoModeExposesHybridBackendName() {
        try (NativeTcpCarrier carrier = createCarrier(TransportMode.SERVER)) {
            assertThat(carrier.requestedSocketBackend()).isEqualTo(SOCKET_BACKEND_AUTO);
            assertThat(carrier.isServerSocketValidationSuccessful()).isFalse();
            assertThat(carrier.isClientSocketValidationSuccessful()).isFalse();

            if (carrier.isFfmSocketBackendArmed()) {
                assertThat(carrier.activeSocketBackend()).isEqualTo(SOCKET_BACKEND_POSIX_HYBRID);
            } else {
                assertThat(carrier.activeSocketBackend()).isEqualTo(SOCKET_BACKEND_NIO);
            }
        }
    }

    @Test
    @DisplayName("auto mode falls back to nio when the Core syscall seam is unavailable on this platform")
    void autoModeFallsBackToNioWhenSeamIsUnavailable() {
        try (NativeTcpCarrier carrier = createCarrier(TransportMode.SERVER)) {
            assumeFalse(carrier.isFfmSocketBackendArmed(),
                    "Core syscall seam is available on this platform; fallback-only path is not active");

            carrier.setStreamHandler(stream -> { });
            carrier.start();

            assertThat(carrier.requestedSocketBackend()).isEqualTo(SOCKET_BACKEND_AUTO);
            assertThat(carrier.activeSocketBackend()).isEqualTo(SOCKET_BACKEND_NIO);
            assertThat(carrier.isServerSocketValidationSuccessful()).isFalse();
            assertThat(carrier.isClientSocketValidationSuccessful()).isFalse();
        }
    }

    @Test
    @DisplayName("explicit nio mode skips FFM arming and preserves the fallback path")
    void nioModePinsExplicitFallback() {
        System.setProperty(SOCKET_BACKEND_PROPERTY, "nio");

        try (NativeTcpCarrier carrier = createCarrier(TransportMode.SERVER)) {
            assertThat(carrier.requestedSocketBackend()).isEqualTo(SOCKET_BACKEND_NIO);
            assertThat(carrier.activeSocketBackend()).isEqualTo(SOCKET_BACKEND_NIO);
            assertThat(carrier.isFfmSocketBackendArmed()).isFalse();
            assertThat(carrier.isServerSocketValidationSuccessful()).isFalse();
            assertThat(carrier.isClientSocketValidationSuccessful()).isFalse();
        }
    }

    @Test
    @DisplayName("explicit posix-hybrid mode prefers the Core socket seam while retaining fallback honesty")
    void posixHybridModePinsExplicitFfmPreference() {
        System.setProperty(SOCKET_BACKEND_PROPERTY, SOCKET_BACKEND_POSIX_HYBRID);

        try (NativeTcpCarrier carrier = createCarrier(TransportMode.SERVER)) {
            assertThat(carrier.requestedSocketBackend()).isEqualTo(SOCKET_BACKEND_POSIX_HYBRID);
            if (carrier.isFfmSocketBackendArmed()) {
                assertThat(carrier.activeSocketBackend()).isEqualTo(SOCKET_BACKEND_POSIX_HYBRID);
            } else {
                assertThat(carrier.activeSocketBackend()).isEqualTo(SOCKET_BACKEND_NIO);
            }
        }
    }

    @Test
    @DisplayName("legacy backend alias property is honored without breaking the canonical override")
    void legacyBackendAliasPropertyIsHonored() {
        System.setProperty(SOCKET_BACKEND_ALIAS_PROPERTY, SOCKET_BACKEND_POSIX_HYBRID);

        try (NativeTcpCarrier carrier = createCarrier(TransportMode.SERVER)) {
            assertThat(carrier.requestedSocketBackend()).isEqualTo(SOCKET_BACKEND_POSIX_HYBRID);
            assertThat(carrier.activeSocketBackend())
                    .isIn(SOCKET_BACKEND_POSIX_HYBRID, SOCKET_BACKEND_NIO);
        }
    }

    @Test
    @DisplayName("listener backlog is computed from max connections with stable internal clamps")
    void computesListenerBacklog_withStableClamps() {
        try (NativeTcpCarrier smallCarrier = createCarrier(TransportMode.SERVER, 8);
             NativeTcpCarrier defaultCarrier = createCarrier(TransportMode.SERVER, 128);
             NativeTcpCarrier largeCarrier = createCarrier(TransportMode.SERVER, 20_000)) {
            assertThat(smallCarrier.listenerBacklog()).isEqualTo(64);
            assertThat(defaultCarrier.listenerBacklog()).isEqualTo(128);
            assertThat(largeCarrier.listenerBacklog()).isEqualTo(1_024);
        }
    }

    @Test
    @DisplayName("auto mode performs one-time server socket validation during start when the FFM seam is armed")
    void autoModeValidatesServerSocketBootstrapOnStart() {
        try (NativeTcpCarrier carrier = createCarrier(TransportMode.SERVER)) {
            carrier.setStreamHandler(stream -> { });

            assertThat(carrier.isServerSocketValidationSuccessful()).isFalse();
            carrier.start();

            if (carrier.isFfmSocketBackendArmed()) {
                assertThat(carrier.isServerSocketValidationSuccessful()).isTrue();
            } else {
                assertThat(carrier.isServerSocketValidationSuccessful()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("auto mode performs one-time client socket validation during connect when the FFM seam is armed")
    void autoModeValidatesClientSocketConnectPath() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (var accepted = listener.accept()) {
                    assertThat(accepted).isNotNull();
                } catch (IOException _) {
                    // listener closed by test teardown
                }
            });

            try (NativeTcpCarrier carrier = createCarrier(TransportMode.CLIENT)) {
                carrier.start();
                assertThat(carrier.isClientSocketValidationSuccessful()).isFalse();

                try (var connection = carrier.connect(LOOPBACK_HOST, port)) {
                    if (carrier.isFfmSocketBackendArmed()) {
                        assertThat(carrier.isClientSocketValidationSuccessful()).isTrue();
                    } else {
                        assertThat(carrier.isClientSocketValidationSuccessful()).isFalse();
                    }
                    assertThat(connection).isNotNull();
                }
            } finally {
                acceptThread.join(1_000L);
            }
        }
    }

    private static NativeTcpCarrier createCarrier(TransportMode mode) {
        return createCarrier(mode, 128);
    }

    private static NativeTcpCarrier createCarrier(TransportMode mode, int maxConnections) {
        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportConfig config = new TransportConfig(
                mode,
                LOOPBACK_HOST,
                nextFreePort(),
                1,
                null,
                null,
                maxConnections,
                30_000);

        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = provider.createEngine(config));
        return (NativeTcpCarrier) holder[0];
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free TCP port", e);
        }
    }
}
