/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An HTTP engine's transport takes the engine's role, not the subsystem's {@link HttpMode}.
 *
 * <p>Asserted in both directions for {@code DUAL}, the mode that builds one engine of each: the
 * server engine gets a listener with the configured material, and the client engine gets neither.
 */
@DisplayName("Community: CommunityHttpTransportFactory — transport mode follows the engine's role")
class CommunityHttpTransportFactoryRoleTest {

    private static final int PORT = 8443;
    private static final MapConfigProvider MATERIAL = new MapConfigProvider(
            Map.of("transport.certPath", "/etc/tls/server.crt", "transport.keyPath", "/etc/tls/server.key"),
            Map.of());

    private static HttpConfig config(HttpMode mode) {
        return new HttpConfig(mode, "127.0.0.1", PORT,
                HttpConfig.DEFAULT_MAX_CONNECTIONS, HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT, HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, false, HttpVersion.HTTP_1_1);
    }

    @ParameterizedTest(name = "HttpMode.{0}")
    @EnumSource(value = HttpMode.class, names = {"SERVER", "DUAL"})
    @DisplayName("the server engine's transport is a SERVER listener carrying the configured material")
    void serverEngineGetsAListener(HttpMode mode) {
        TransportConfig transport = CommunityHttpTransportFactory.buildTransportConfig(
                config(mode), PORT, MATERIAL, CommunityHttpTransportFactory.Role.SERVER);

        assertThat(transport.mode()).isEqualTo(TransportMode.SERVER);
        assertThat(transport.port()).isEqualTo(PORT);
        assertThat(transport.certPath()).isEqualTo("/etc/tls/server.crt");
        assertThat(transport.keyPath()).isEqualTo("/etc/tls/server.key");
    }

    @ParameterizedTest(name = "HttpMode.{0}")
    @EnumSource(value = HttpMode.class, names = {"CLIENT", "DUAL"})
    @DisplayName("the client engine's transport is a CLIENT with no port and no listener material")
    void clientEngineGetsNoListener(HttpMode mode) {
        TransportConfig transport = CommunityHttpTransportFactory.buildTransportConfig(
                config(mode), PORT, MATERIAL, CommunityHttpTransportFactory.Role.CLIENT);

        assertThat(transport.mode()).isEqualTo(TransportMode.CLIENT);
        assertThat(transport.port()).isZero();
        assertThat(transport.certPath()).isNull();
        assertThat(transport.keyPath()).isNull();
    }

    @Test
    @DisplayName("a DISABLED subsystem gives either engine a DISABLED transport")
    void disabledStaysDisabled() {
        for (CommunityHttpTransportFactory.Role role : CommunityHttpTransportFactory.Role.values()) {
            TransportConfig transport = CommunityHttpTransportFactory.buildTransportConfig(
                    config(HttpMode.DISABLED), PORT, MATERIAL, role);

            assertThat(transport.mode()).as("role %s", role).isEqualTo(TransportMode.DISABLED);
        }
    }
}
