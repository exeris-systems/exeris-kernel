/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the TLS reactor busy-spin: {@link NativeTcpCarrier#closeKeyStream} must
 * cancel the selection key <em>synchronously</em> so a faulted connection's key can never be
 * re-selected (for read or write).
 *
 * <h2>Why this exists</h2>
 * <p>The reactor select loop reaches {@code closeKeyStream} only via its {@code catch
 * (RuntimeException)} branch — most often an unwrap-on-closed {@code TlsDecryptException} thrown
 * when a stream's TLS engine is already closed but its graceful close is still deferred for
 * outbound drain ({@code NativeTcpStream#finishCloseIfDrained}). Because the deferred graceful close
 * never reaches {@code channel.close()}, the key stays registered; the level-triggered channel
 * re-fires every select, re-throwing and busy-spinning the reactor (~5.7M throws/s measured).
 * Withdrawing only {@code OP_READ} merely relocates the spin onto {@code OP_WRITE} (a closed-engine
 * stream's queued egress is undrainable, so {@link NativeTcpCarrier#flushStream} never narrows
 * interest). The fix cancels the key outright and resets the stream abortively.
 *
 * <p>The assertion is non-vacuous: with the channel's stream unregistered, the pre-fix
 * {@code closeKeyStream} returns without touching the key, so the key would stay valid — the
 * cancellation assertion fails. The cancel happens before {@code resolveStream}, so it is
 * independent of whether the stream's teardown completes or defers.
 */
class NativeTcpCloseKeyStreamReadInterestTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void closeKeyStreamCancelsKeySynchronously() throws Exception {
        // Port is irrelevant — the carrier is never started/bound; closeKeyStream only manipulates
        // the supplied SelectionKey. A valid SERVER-mode port just satisfies config validation.
        NativeTcpCarrier carrier =
                new NativeTcpCarrier(TransportConfig.serverDefaults(8443), ALLOCATOR, null, null);
        try (ServerSocketChannel listener = ServerSocketChannel.open();
             Selector selector = Selector.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel =
                         SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);
                SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ);

                assertTrue(key.isValid() && (key.interestOps() & SelectionKey.OP_READ) != 0,
                        "precondition: channel must start registered and armed for OP_READ");

                carrier.closeKeyStream(key);

                assertFalse(key.isValid(),
                        "closeKeyStream must cancel the key synchronously so the faulted connection "
                                + "cannot be re-selected for read or write and busy-spin the reactor");
            }
        } finally {
            carrier.close();
        }
    }
}
