/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults this resolver produces are the defaults {@code docs/subsystems/config.md} publishes.
 *
 * <p>That is the reason these cases exist rather than coverage for its own sake: a documented default
 * and an effective one that disagree is the drift this repository keeps catching, and the table cannot
 * check itself. Each value below is asserted against the constant {@code WebSocketConfig} exposes, so
 * changing one without the other fails here.
 */
@DisplayName("CommunityWebSocketConfigResolver")
class CommunityWebSocketConfigResolverTest {

    @Nested
    @DisplayName("The gate")
    class Gate {

        @Test
        @DisplayName("absent means disabled — an upgrade must not open a socket")
        void absentIsDisabled() {
            assertThat(CommunityWebSocketConfigResolver.isEnabled(config(Map.of()))).isFalse();
        }

        @Test
        @DisplayName("explicit true and false are both honoured")
        void explicitValuesAreHonoured() {
            assertThat(CommunityWebSocketConfigResolver.isEnabled(
                    config(Map.of("websocket.enabled", "true")))).isTrue();
            assertThat(CommunityWebSocketConfigResolver.isEnabled(
                    config(Map.of("websocket.enabled", "false")))).isFalse();
        }
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("an empty configuration produces exactly the documented defaults")
        void emptyConfigurationMatchesTheTable() {
            WebSocketConfig resolved =
                    CommunityWebSocketConfigResolver.buildWebSocketConfig(config(Map.of()));

            assertThat(resolved.bindHost())
                    .as("loopback, asked of the JDK so an IPv6-only host can bind it")
                    .isEqualTo(InetAddress.getLoopbackAddress().getHostAddress());
            assertThat(resolved.port()).isEqualTo(8081);
            assertThat(resolved.maxConnections()).isEqualTo(WebSocketConfig.DEFAULT_MAX_CONNECTIONS);
            assertThat(resolved.idleTimeoutMillis())
                    .isEqualTo(WebSocketConfig.DEFAULT_IDLE_TIMEOUT_MILLIS);
            assertThat(resolved.keepAliveIntervalMillis())
                    .isEqualTo(WebSocketConfig.DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS);
            assertThat(resolved.maxMessageBytes())
                    .isEqualTo(WebSocketConfig.DEFAULT_MAX_MESSAGE_BYTES);
            assertThat(resolved.allowedOrigins())
                    .as("empty accepts no browser origin — ADR-084 §6's refusing default, "
                            + "reached by leaving the key alone")
                    .isEmpty();
        }

        @Test
        @DisplayName("a blank bindHost falls back rather than binding nothing")
        void blankBindHostFallsBack() {
            WebSocketConfig resolved = CommunityWebSocketConfigResolver.buildWebSocketConfig(
                    config(Map.of("websocket.bindHost", "   ")));

            assertThat(resolved.bindHost())
                    .isEqualTo(InetAddress.getLoopbackAddress().getHostAddress());
        }
    }

    @Nested
    @DisplayName("Explicit values")
    class Explicit {

        @Test
        @DisplayName("every numeric key overrides its default")
        void numericKeysOverride() {
            WebSocketConfig resolved = CommunityWebSocketConfigResolver.buildWebSocketConfig(config(Map.of(
                    "websocket.bindHost", "0.0.0.0",
                    "websocket.port", "9443",
                    "websocket.maxConnections", "7",
                    "websocket.idleTimeoutMillis", "1234",
                    "websocket.keepAliveIntervalMillis", "567",
                    "websocket.maxMessageBytes", "89")));

            assertThat(resolved.bindHost()).isEqualTo("0.0.0.0");
            assertThat(resolved.port()).isEqualTo(9443);
            assertThat(resolved.maxConnections()).isEqualTo(7);
            assertThat(resolved.idleTimeoutMillis()).isEqualTo(1234L);
            assertThat(resolved.keepAliveIntervalMillis()).isEqualTo(567L);
            assertThat(resolved.maxMessageBytes()).isEqualTo(89L);
        }

        @Test
        @DisplayName("origins are split, trimmed, and empty entries dropped")
        void originsAreParsed() {
            WebSocketConfig resolved = CommunityWebSocketConfigResolver.buildWebSocketConfig(config(
                    Map.of("websocket.allowedOrigins", " http://a ,, http://b,")));

            assertThat(resolved.allowedOrigins())
                    .containsExactlyInAnyOrder("http://a", "http://b");
        }
    }

    private static ConfigProvider config(Map<String, String> values) {
        return new MapConfigProvider(values);
    }

    private record MapConfigProvider(Map<String, String> values) implements ConfigProvider {

        @Override
        public Supplier<ConfigProvider.KernelSettings> kernelSettings() {
            return ConfigProvider.KernelSettings::defaults;
        }

        @Override
        public Optional<String> getString(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return getString(key).map(Integer::parseInt);
        }

        @Override
        public Optional<Long> getLong(String key) {
            return getString(key).map(Long::parseLong);
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            return getString(key).map(Boolean::parseBoolean);
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            return Optional.empty();
        }

        @Override
        public void watch(String file, String key, Consumer<Object> callback) {
            // no-op
        }
    }
}
