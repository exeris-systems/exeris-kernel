/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketProvider;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The path ADR-084 §1 exists for: an endpoint obtained <em>without booting the kernel</em>.
 *
 * <p>"The platform must get an endpoint without booting the kernel… from two public calls, no
 * {@code KernelBootstrap}, no DI, no {@code ServiceLoader}" is the deciding argument in that ADR,
 * and nothing asserted it. What the code did instead was throw: engine construction resolved
 * {@code KernelProviders.MEMORY_ALLOCATOR} and refused when it was unbound — which the transport
 * factory's own javadoc described as "exactly the case" for an embedded endpoint. The class named
 * the scenario and the method rejected it.
 *
 * <p>These tests bind no {@code ScopedValue} and boot nothing. If the ambient-allocator requirement
 * ever comes back, they fail here rather than in a consumer's LSP server.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Community WebSocket: embedded without a kernel boot")
class CommunityWebSocketEmbeddedBootlessTest {

    private static WebSocketConfig loopbackConfig() {
        return WebSocketConfig.defaultServer("127.0.0.1", 0, List.of("http://localhost"));
    }

    @Test
    @DisplayName("an engine starts with no ScopedValue bound and no kernel booted")
    void engineStartsWithoutAmbientAllocator() {
        WebSocketServerEngine engine =
                new CommunityWebSocketProvider().createServerEngine(loopbackConfig());
        engine.setHandler(exchange -> {
            while (exchange.receive() != null) {
                // Drain; this test asserts reachability, not message flow.
            }
        });

        engine.start();
        try {
            assertThat(engine.boundPort())
                    .as("ADR-084 §1: two public calls, no kernel boot — the socket must be listening")
                    .isPositive();
        } finally {
            engine.close();
        }
    }

    @Test
    @DisplayName("the provider is reachable through ServiceLoader, so a consumer needs no Community type")
    void providerIsDiscoverable() {
        // The ADR's phrasing rules ServiceLoader out as a REQUIREMENT, not as a possibility. It
        // matters because the alternative is naming CommunityWebSocketProvider directly, which puts
        // a Community type on a consumer's compile classpath — the coupling the Wall exists to
        // prevent, and the one an LSP server would otherwise have to accept.
        List<WebSocketProvider> providers = ServiceLoader.load(WebSocketProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        assertThat(providers)
                .as("a WebSocketProvider must be discoverable without naming its class")
                .isNotEmpty();
        assertThat(providers).allSatisfy(provider -> {
            assertThat(provider.providerId()).isNotBlank();
            assertThat(provider.providerName()).isNotBlank();
        });
    }

    @Test
    @DisplayName("closing an engine that created its own allocator releases it")
    void closeReleasesTheAllocatorItCreated() {
        WebSocketServerEngine engine =
                new CommunityWebSocketProvider().createServerEngine(loopbackConfig());
        engine.setHandler(exchange -> {
            while (exchange.receive() != null) {
                // Drain.
            }
        });
        engine.start();
        engine.close();

        // Idempotent, and a second close must not double-release the allocator this engine owns.
        // A double free on an off-heap allocator is a SIGSEGV, not an exception, so this case is
        // cheap here and expensive anywhere else.
        assertThatCode(engine::close).doesNotThrowAnyException();
    }
}
