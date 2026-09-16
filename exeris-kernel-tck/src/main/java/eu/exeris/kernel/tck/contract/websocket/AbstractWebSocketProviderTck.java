/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketProvider;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The construction contract every {@link WebSocketProvider} owes, kernel boot or no kernel boot.
 *
 * <h2>Why this is a TCK case and not a driver test</h2>
 * <p>ADR-084 §1 makes "the platform must get an endpoint <em>without</em> booting the kernel" the
 * deciding property of the whole decision — it is why the ADR rejects negotiating an upgrade on the
 * HTTP stream seam. A property that decides an ADR has to be enforceable against <em>any</em>
 * provider, and until now it was pinned nowhere: {@code AbstractWebSocketExchangeTck} covers
 * wire-level behaviour (handshake, frames, close codes), so an Enterprise provider that quietly
 * required a kernel scope would satisfy the shared suite while contradicting the ADR.
 *
 * <p>The Community provider did exactly that at v0.12 — it resolved the kernel's allocator at
 * construction and threw when nothing had bound one, which is precisely the state a tool embedding
 * an endpoint is in. That was a driver bug found by reading; this class is what makes the next one a
 * test failure.
 *
 * <p>These cases bind no {@code ScopedValue} and boot nothing, deliberately. A binding that needs a
 * kernel scope to pass here is not a conforming binding.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("TCK: WebSocketProvider construction contract")
public abstract class AbstractWebSocketProviderTck {

    /**
     * Creates the contract; subclasses supply the provider under test via {@link #createProvider()}.
     */
    public AbstractWebSocketProviderTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Supplies the provider under test.
     *
     * @return a provider instance; a fresh one per case
     */
    protected abstract WebSocketProvider createProvider();

    private WebSocketProvider provider;

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    /**
     * A loopback server on an ephemeral port, refusing every browser origin.
     *
     * @return a configuration usable without any ambient kernel state
     */
    private static WebSocketConfig ephemeralLoopback() {
        return WebSocketConfig.defaultServer("127.0.0.1", 0, List.of());
    }

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("id and name are present, so a deployment can say which driver answered")
        void identityIsPresent() {
            assertThat(provider.providerId()).isNotBlank();
            assertThat(provider.providerName()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Construction without a kernel")
    class WithoutAKernel {

        @Test
        @DisplayName("createServerEngine succeeds with no ScopedValue bound and nothing booted")
        void engineIsConstructibleBootless() {
            WebSocketServerEngine engine = provider.createServerEngine(ephemeralLoopback());

            assertThat(engine)
                    .as("ADR-084 §1: an endpoint without booting the kernel")
                    .isNotNull();
            engine.close();
        }

        @Test
        @DisplayName("the engine listens after start, and reports the port it took")
        void engineListensAfterStart() {
            WebSocketServerEngine engine = provider.createServerEngine(ephemeralLoopback());
            engine.setHandler(AbstractWebSocketProviderTck::drain);

            engine.start();
            try {
                assertThat(engine.boundPort())
                        .as("an ephemeral port must be resolved and reported, not left at 0")
                        .isPositive();
            } finally {
                engine.close();
            }
        }

        @Test
        @DisplayName("close is idempotent, whoever owns the memory behind the engine")
        void closeIsIdempotent() {
            WebSocketServerEngine engine = provider.createServerEngine(ephemeralLoopback());
            engine.setHandler(AbstractWebSocketProviderTck::drain);
            engine.start();

            engine.close();
            // A provider that creates its own allocator when none is bound owns it, and a second
            // close must not release it twice: a double free off-heap is a SIGSEGV, not an exception.
            assertThatCode(engine::close).doesNotThrowAnyException();
        }
    }

    private static void drain(eu.exeris.kernel.spi.websocket.WebSocketExchange exchange) {
        String message = exchange.receive();
        while (message != null) {
            message = exchange.receive();
        }
    }
}
