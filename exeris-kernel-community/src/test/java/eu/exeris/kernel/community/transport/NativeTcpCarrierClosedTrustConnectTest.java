/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A verifying connect whose carrier's client trust is already closed fails as "not running", the
 * exception {@code connect} documents for an engine that is not running, and leaves nothing open.
 *
 * <p>{@link NativeTcpCarrier#close()} stops the carrier and then closes its {@link NativeTcpClientTls};
 * a connect that passed the running check before that close reaches the engine build with the trust
 * already closed. This test builds that state directly, by closing the client TLS of a running
 * carrier, so the outcome does not depend on thread timing.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpCarrier — a connect that meets a closed client trust fails as not running")
class NativeTcpCarrierClosedTrustConnectTest {

    private static final int PEER_READ_TIMEOUT_MS = 5_000;

    @TempDir
    Path material;

    @Test
    @DisplayName("IllegalStateException(Engine is not running), the dialled socket closed, nothing counted")
    void closedTrustFailsAsNotRunning() throws Exception {
        CommunityKernelCryptoProvider crypto = new CommunityKernelCryptoProvider();
        TlsTestAuthority authority = TlsTestAuthority.root(material, "closed-trust-ca");

        try (MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(
                     MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
             ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            NativeTcpClientTls clientTls = NativeTcpClientTls.verified(crypto,
                    crypto.openClientTrust(authority.certificate()), NativeTcpClientTls.TrustOrigin.CONFIG_KEY);
            NativeTcpCarrier carrier = new NativeTcpCarrier(
                    new TransportConfig(TransportMode.CLIENT, "127.0.0.1", 0, 1, null, null, 1024, 30_000),
                    allocator, crypto, null, clientTls);
            try {
                carrier.start();
                clientTls.close();

                assertThatThrownBy(() -> carrier.connect("127.0.0.1", peer.getLocalPort()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Engine is not running")
                        .cause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("client trust is closed");

                try (Socket dialled = peer.accept()) {
                    dialled.setSoTimeout(PEER_READ_TIMEOUT_MS);
                    try (InputStream in = dialled.getInputStream()) {
                        assertThat(in.read())
                                .as("the carrier closed the socket it dialled")
                                .isEqualTo(-1);
                    }
                }
                assertThat(carrier.stats().activeConnections()).isZero();
                assertThat(carrier.stats().activeStreams()).isZero();
            } finally {
                carrier.close();
            }
        }
    }
}
