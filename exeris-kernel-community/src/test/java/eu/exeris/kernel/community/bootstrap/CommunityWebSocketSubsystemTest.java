/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.websocket.WebSocketHandler;
import eu.exeris.kernel.spi.websocket.WebSocketKernelProviders;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bootstrap half of ADR-084: an application that boots the kernel can reach a WebSocket endpoint
 * without naming a Community class.
 *
 * <p>The first test is the load-bearing one and it asserts an absence. Adding a subsystem to the
 * Community provider list makes it available to every boot, so a deployment that merely upgraded
 * would gain an open socket it never configured — a security regression delivered as a feature. That
 * cannot be left to reviewers noticing.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community WebSocket subsystem: reachable through the boot, off unless asked for")
class CommunityWebSocketSubsystemTest {

    // CommunityConfigProvider maps a key to the `exeris.`-prefixed system property; the
// unprefixed name is not read, which is the mistake this constant exists to not repeat.
    private static final String ENABLED = "exeris.websocket.enabled";
    private static final String PORT = "exeris.websocket.port";
    private static final String ORIGINS = "exeris.websocket.allowedOrigins";

    @AfterEach
    void clearProperties() {
        System.clearProperty(ENABLED);
        System.clearProperty(PORT);
        System.clearProperty(ORIGINS);
    }

    @Nested
    @DisplayName("Disabled by default")
    class DisabledByDefault {

        @Test
        @DisplayName("a boot with no websocket configuration binds nothing and opens no listener")
        void unconfiguredBootBindsNothing() throws Exception {
            AtomicReference<Optional<WebSocketServerEngine>> seen = new AtomicReference<>();

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("websocket"))
                    .build()
                    .boot(() -> seen.set(WebSocketKernelProviders.webSocketServerEngine()));

            assertThat(seen.get())
                    .as("an upgrade must not hand a deployment a socket it never configured")
                    .isEmpty();
            assertThat(WebSocketKernelProviders.WEBSOCKET_PROVIDER.isBound()).isFalse();
        }

        @Test
        @DisplayName("websocket.enabled=false is the same as absent")
        void explicitFalseBindsNothing() throws Exception {
            System.setProperty(ENABLED, "false");
            AtomicReference<Optional<WebSocketServerEngine>> seen = new AtomicReference<>();

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("websocket"))
                    .build()
                    .boot(() -> seen.set(WebSocketKernelProviders.webSocketServerEngine()));

            assertThat(seen.get()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Enabled")
    class Enabled {

        @Test
        @DisplayName("the engine is published into kernel scope and is listening")
        void enabledBootPublishesARunningEngine() throws Exception {
            System.setProperty(ENABLED, "true");
            System.setProperty(PORT, "0");
            System.setProperty(ORIGINS, "http://localhost, http://127.0.0.1");
            AtomicReference<Integer> port = new AtomicReference<>();
            AtomicReference<Boolean> providerBound = new AtomicReference<>();

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("websocket"))
                    .build()
                    .boot(() -> {
                        WebSocketServerEngine engine = WebSocketKernelProviders
                                .webSocketServerEngine().orElseThrow();
                        port.set(engine.boundPort());
                        providerBound.set(WebSocketKernelProviders.WEBSOCKET_PROVIDER.isBound());
                    });

            assertThat(port.get())
                    .as("an enabled subsystem must actually be listening, not merely constructed")
                    .isPositive();
            assertThat(providerBound.get())
                    .as("the provider is published too, so a consumer can ask which driver answered")
                    .isTrue();
        }

        @Test
        @DisplayName("the application's handler, supplied into the boot, is the one the engine serves")
        void applicationHandlerIsUsed() throws Exception {
            System.setProperty(ENABLED, "true");
            System.setProperty(PORT, "0");
            AtomicReference<WebSocketHandler> served = new AtomicReference<>();
            AtomicReference<Integer> port = new AtomicReference<>();
            WebSocketHandler application = exchange -> {
                while (exchange.receive() != null) {
                    continue;
                }
            };

            // `call` rather than `run`: the boot throws a checked BootstrapException, and swallowing
            // it into an unchecked wrapper here would hide a boot failure behind an assertion failure.
            ScopedValue.where(WebSocketKernelProviders.WEBSOCKET_SERVER_HANDLER, application)
                    .call(() -> {
                        KernelBootstrap.builder()
                                .selector(BootstrapSelector.forNames("websocket"))
                                .build()
                                .boot(() -> {
                                    served.set(WebSocketKernelProviders
                                            .webSocketServerHandler().orElse(null));
                                    // Asserting only the slot this test bound would pass even if the
                                    // subsystem never ran; the engine must be up as well.
                                    port.set(WebSocketKernelProviders.webSocketServerEngine()
                                            .orElseThrow().boundPort());
                                });
                        return null;
                    });

            assertThat(served.get())
                    .as("a handler bound around the boot must reach the subsystem, not be ignored")
                    .isSameAs(application);
            assertThat(port.get()).isPositive();
        }
    }
}
