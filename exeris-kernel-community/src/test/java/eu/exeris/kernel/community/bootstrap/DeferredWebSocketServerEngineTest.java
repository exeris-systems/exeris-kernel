/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketHandler;
import eu.exeris.kernel.spi.websocket.WebSocketHandshake;
import eu.exeris.kernel.spi.websocket.WebSocketHandshakeHandler;
import eu.exeris.kernel.spi.websocket.WebSocketProvider;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deferral itself, against a recording provider rather than a socket.
 *
 * <p>What matters here is <em>when</em> the delegate is built, and that is invisible to a test that
 * opens a real endpoint: the whole reason this class exists is that bootstrap publishes the engine
 * reference before {@code MEMORY_ALLOCATOR} is bound, so constructing early would either fail or open
 * a second memory budget. A counting provider is what makes "not yet" observable.
 */
@DisplayName("DeferredWebSocketServerEngine")
class DeferredWebSocketServerEngineTest {

    private static final WebSocketConfig CONFIG =
            WebSocketConfig.defaultServer("127.0.0.1", 0, List.of());

    private final AtomicInteger created = new AtomicInteger();
    private final RecordingEngine recording = new RecordingEngine();
    private final WebSocketProvider provider = new RecordingProvider(created, recording);

    @Nested
    @DisplayName("Before start")
    class BeforeStart {

        @Test
        @DisplayName("no delegate is built, which is the entire point of the class")
        void constructionIsDeferred() {
            new DeferredWebSocketServerEngine(provider, CONFIG);

            assertThat(created.get())
                    .as("building at construction is what would read MEMORY_ALLOCATOR too early")
                    .isZero();
        }

        @Test
        @DisplayName("boundPort answers -1 rather than throwing")
        void boundPortAnswersBeforeStart() {
            // providerBindings() publishes this engine before start(), so something reading the slot
            // early must get an answer.
            assertThat(new DeferredWebSocketServerEngine(provider, CONFIG).boundPort()).isEqualTo(-1);
        }

        @Test
        @DisplayName("stop and close are quiet on an engine that never started")
        void stopAndCloseAreQuiet() {
            DeferredWebSocketServerEngine engine = new DeferredWebSocketServerEngine(provider, CONFIG);

            assertThatCode(engine::stop).doesNotThrowAnyException();
            assertThatCode(engine::close).doesNotThrowAnyException();
            assertThat(created.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Across start")
    class AcrossStart {

        @Test
        @DisplayName("handlers set before start reach the delegate when it is built")
        void handlersSetBeforeStartAreForwarded() {
            DeferredWebSocketServerEngine engine = new DeferredWebSocketServerEngine(provider, CONFIG);
            WebSocketHandler handler = exchange -> { };
            WebSocketHandshakeHandler policy = request -> WebSocketHandshake.accept();

            engine.setHandler(handler);
            engine.setHandshakeHandler(policy);
            engine.start();

            assertThat(recording.handler).isSameAs(handler);
            assertThat(recording.handshakeHandler).isSameAs(policy);
        }

        @Test
        @DisplayName("a handler set after start reaches the live delegate")
        void handlerSetAfterStartIsForwarded() {
            DeferredWebSocketServerEngine engine = new DeferredWebSocketServerEngine(provider, CONFIG);
            engine.start();
            WebSocketHandler later = exchange -> { };

            engine.setHandler(later);

            assertThat(recording.handler).isSameAs(later);
        }

        @Test
        @DisplayName("starting twice builds one delegate, not two")
        void startIsIdempotentInConstruction() {
            DeferredWebSocketServerEngine engine = new DeferredWebSocketServerEngine(provider, CONFIG);

            engine.start();
            engine.start();

            assertThat(created.get()).isEqualTo(1);
            assertThat(recording.starts).isEqualTo(2);
        }

        @Test
        @DisplayName("boundPort delegates once there is a delegate")
        void boundPortDelegates() {
            DeferredWebSocketServerEngine engine = new DeferredWebSocketServerEngine(provider, CONFIG);
            engine.start();

            assertThat(engine.boundPort()).isEqualTo(RecordingEngine.PORT);
        }
    }

    @Nested
    @DisplayName("After close")
    class AfterClose {

        @Test
        @DisplayName("start on a closed engine is refused instead of quietly rebuilding")
        void startAfterCloseIsRefused() {
            DeferredWebSocketServerEngine engine = new DeferredWebSocketServerEngine(provider, CONFIG);
            engine.start();
            engine.close();

            assertThatThrownBy(engine::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("close is idempotent, so the delegate is released exactly once")
        void closeIsIdempotent() {
            DeferredWebSocketServerEngine engine = new DeferredWebSocketServerEngine(provider, CONFIG);
            engine.start();

            engine.close();
            engine.close();

            assertThat(recording.closes)
                    .as("a second close must not reach the delegate")
                    .isEqualTo(1);
        }
    }

    private record RecordingProvider(AtomicInteger created, RecordingEngine engine)
            implements WebSocketProvider {

        @Override
        public WebSocketServerEngine createServerEngine(WebSocketConfig config) {
            created.incrementAndGet();
            return engine;
        }

        @Override
        public String providerId() {
            return "recording";
        }

        @Override
        public String providerName() {
            return "Recording/Test";
        }

        @Override
        public int priority() {
            return 0;
        }
    }

    private static final class RecordingEngine implements WebSocketServerEngine {

        private static final int PORT = 4242;

        private WebSocketHandler handler;
        private WebSocketHandshakeHandler handshakeHandler;
        private int starts;
        private int closes;

        @Override
        public void setHandler(WebSocketHandler handler) {
            this.handler = handler;
        }

        @Override
        public void setHandshakeHandler(WebSocketHandshakeHandler handshakeHandler) {
            this.handshakeHandler = handshakeHandler;
        }

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void stop() {
            // no-op
        }

        @Override
        public int boundPort() {
            return PORT;
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
