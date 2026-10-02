/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketHandler;
import eu.exeris.kernel.spi.websocket.WebSocketHandshakeHandler;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import eu.exeris.kernel.tck.contract.websocket.AbstractWebSocketExchangeTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Binds {@link AbstractWebSocketExchangeTck} to the community provider over a real loopback socket.
 *
 * <p>Real wire, not an in-memory double: the contract this TCK states is about frames, masking and a
 * handshake, none of which a double would exercise. The client is
 * {@link TestWebSocketClient}, which frames by hand rather than through the kernel codec, so the two
 * ends cannot agree on a shared mistake.
 */
@DisplayName("Community: WebSocket exchange TCK")
class CommunityWebSocketExchangeTckTest extends AbstractWebSocketExchangeTck {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected boolean supportsClientFragmentation() {
        return true;
    }

    @Override
    protected boolean supportsClientBinaryFrames() {
        return true;
    }

    @Override
    protected WebSocketScenario connect(WebSocketConfig config,
                                        WebSocketHandler handler,
                                        WebSocketHandshakeHandler handshakeHandler,
                                        String clientOrigin) {
        WebSocketServerEngine[] holder = new WebSocketServerEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new CommunityWebSocketProvider().createServerEngine(config));
        WebSocketServerEngine engine = holder[0];
        engine.setHandler(handler);
        if (handshakeHandler != null) {
            engine.setHandshakeHandler(handshakeHandler);
        }
        engine.start();
        try {
            return new LoopbackScenario(engine,
                    new TestWebSocketClient(engine.boundPort(), clientOrigin, null));
        } catch (IOException unreachable) {
            engine.close();
            throw new UncheckedIOException("could not reach the engine under test", unreachable);
        }
    }

    private static final class LoopbackScenario implements WebSocketScenario {

        private final WebSocketServerEngine engine;
        private final TestWebSocketClient client;

        LoopbackScenario(WebSocketServerEngine engine, TestWebSocketClient client) {
            this.engine = engine;
            this.client = client;
        }

        @Override
        public Optional<HttpStatus> handshakeStatus() {
            return client.handshakeStatus();
        }

        @Override
        public Optional<String> negotiatedSubprotocol() {
            return client.negotiatedSubprotocol();
        }

        @Override
        public void sendFromClient(String message) {
            client.sendText(message);
        }

        @Override
        public void sendFragmentedFromClient(String message, int fragments) {
            client.sendFragmented(message, fragments);
        }

        @Override
        public void sendBinaryFromClient(byte[] payload) {
            client.sendBinary(payload);
        }

        @Override
        public String receiveOnClient(long timeout, TimeUnit unit) {
            return client.receive(timeout, unit);
        }

        @Override
        public void closeClient(WebSocketCloseCode code) {
            client.sendClose(code);
        }

        @Override
        public Optional<Integer> observedCloseCode(long timeout, TimeUnit unit) {
            return client.observedCloseCode(timeout, unit);
        }

        @Override
        public void close() {
            client.close();
            engine.close();
        }
    }
}
