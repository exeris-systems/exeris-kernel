/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two origin cases {@code AbstractWebSocketExchangeTck} does not reach, both from the subsystem
 * contract in {@code docs/subsystems/http.md}.
 *
 * <p>The TCK drives every scenario with an origin and a non-empty allowlist, so neither the
 * empty-allowlist direction nor the header-less client is exercised there. Both are contract
 * statements rather than binding details, so they arguably belong in the TCK; they are here because
 * this PR already adds one security case to it and a second is better proposed than bundled.
 */
@DisplayName("Community: WebSocket origin policy")
class CommunityWebSocketOriginPolicyTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    private static WebSocketServerEngine start(List<String> allowedOrigins) {
        WebSocketConfig config = WebSocketConfig.defaultServer("127.0.0.1", 0, allowedOrigins);
        WebSocketServerEngine[] holder = new WebSocketServerEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new CommunityWebSocketProvider().createServerEngine(config));
        WebSocketServerEngine engine = holder[0];
        engine.setHandler(exchange -> {
            while (exchange.receive() != null) {
                // Drain; these tests assert on the handshake, not on message flow.
            }
        });
        engine.start();
        return engine;
    }

    @Test
    @DisplayName("an empty allowlist refuses every browser origin rather than admitting any")
    void emptyAllowlistFailsClosed() throws IOException {
        // The direction that matters: a config that forgot to list its origins must stop working
        // visibly. Skipping the check when the set is empty would invert exactly this.
        try (WebSocketServerEngine engine = start(List.of());
             TestWebSocketClient client =
                     new TestWebSocketClient(engine.boundPort(), "https://studio.example", null)) {
            assertThat(client.accepted()).isFalse();
            assertThat(client.handshakeStatus()).isPresent();
        }
    }

    @Test
    @DisplayName("a client sending no Origin is not a browser, and the allowlist does not apply")
    void headerlessClientIsAdmitted() {
        // CSWSH is a browser attack: it works because the victim's browser attaches ambient
        // cookies. A client that chooses its own headers has none to abuse, so refusing it would
        // break every non-browser consumer while stopping an attacker who need only omit a header.
        try (WebSocketServerEngine engine = start(List.of("https://studio.example"));
             TestWebSocketClient client = new TestWebSocketClient(engine.boundPort(), null, null)) {
            assertThat(client.accepted()).isTrue();
        } catch (IOException unreachable) {
            throw new AssertionError("engine under test was unreachable", unreachable);
        }
    }
}
