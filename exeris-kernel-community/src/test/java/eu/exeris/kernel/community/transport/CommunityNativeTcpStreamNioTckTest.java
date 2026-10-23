/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.transport.AbstractTransportStreamTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AbstractTransportStreamTck} over the NIO fallback, which carries no errno: the backend is
 * pinned by its documented property, and the streams are checked to run on it, so a platform where the
 * core-socket seam would arm cannot make this binding silently test the seam instead.
 */
@DisplayName("Community: NativeTcpStream TCK on the NIO fallback")
class CommunityNativeTcpStreamNioTckTest extends AbstractTransportStreamTck {

    private String previousBackend;
    private MemoryAllocator allocator;
    private CommunityTransportTestHarness.Pair pair;

    /**
     * Pins the backend. The suite's own set-up creates the stream pair before any subclass hook runs, so
     * the pin is taken where the pair is created.
     */
    private void pinNioBackend() {
        previousBackend = System.getProperty(NativeTcpSocketBackend.SOCKET_BACKEND_PROPERTY);
        System.setProperty(NativeTcpSocketBackend.SOCKET_BACKEND_PROPERTY, NativeTcpSocketBackend.SOCKET_BACKEND_NIO);
    }

    @AfterEach
    @SuppressWarnings("unused")
    void closeHarnessAndRestoreBackend() {
        try {
            if (pair != null) {
                pair.closeConnections();
                pair.closeEngines();
                pair = null;
            }
        } finally {
            if (previousBackend == null) {
                System.clearProperty(NativeTcpSocketBackend.SOCKET_BACKEND_PROPERTY);
            } else {
                System.setProperty(NativeTcpSocketBackend.SOCKET_BACKEND_PROPERTY, previousBackend);
            }
        }
    }

    @Override
    protected StreamPair createStreamPair() {
        pinNioBackend();
        pair = CommunityTransportTestHarness.openLoopbackPair(allocator, false);
        assertThat(((NativeTcpStream) pair.clientStream()).plainSocketBackendName())
                .as("the client stream runs on the NIO fallback").isEqualTo("nio-fallback");
        assertThat(((NativeTcpStream) pair.serverStream()).plainSocketBackendName())
                .as("the server stream runs on the NIO fallback").isEqualTo("nio-fallback");
        return new StreamPair(pair.clientStream(), pair.serverStream());
    }

    @Override
    protected boolean expectsTrueReset() {
        return true;
    }

    @Override
    protected AutoCloseable holdOutboundEgress(TransportStream writer) {
        return CommunityTransportTestHarness.holdOutboundConsumer(writer);
    }

    @Override
    protected MemoryAllocator createAllocator() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        return allocator;
    }
}
