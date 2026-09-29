/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An HTTP engine's transport takes the engine's role, not the subsystem's {@link HttpMode}, and a
 * client engine's outbound TLS requirement.
 *
 * <p>Asserted in both directions for {@code DUAL}, the mode that builds one engine of each: the
 * server engine gets a listener with the configured material, and the client engine gets neither.
 */
@DisplayName("Community: CommunityHttpTransportFactory — transport mode follows the engine's role")
class CommunityHttpTransportFactoryRoleTest {

    private static final int PORT = 8443;
    private static final String POSTURE_EVENT = "eu.exeris.kernel.transport.TransportTlsClientPosture";
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

    @Test
    @DisplayName("a client engine's outbound TLS requirement reaches its transport, and a server engine takes none")
    void outboundRequirementReachesTheClientTransportOnly(@TempDir Path recordings) throws Exception {
        CommunityKernelCryptoProvider crypto = new CommunityKernelCryptoProvider();
        Path dump = Files.createTempFile(recordings, "posture", ".jfr");
        try (MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
             Recording recording = new Recording()) {
            recording.enable(POSTURE_EVENT).withoutStackTrace();
            recording.start();
            ScopedValue.where(KernelProviders.CRYPTO_PROVIDER, crypto)
                    .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                    .run(() -> new CommunityHttpProvider()
                            .createClientEngine(config(HttpMode.CLIENT), CommunityOutboundTls.PLAINTEXT)
                            .close());
            recording.stop();
            recording.dump(dump);

            assertThatThrownBy(() -> CommunityHttpTransportFactory.buildTransport(config(HttpMode.SERVER),
                    PORT, allocator, CommunityHttpTransportFactory.Role.SERVER, CommunityOutboundTls.VERIFIED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a server engine dials nothing");
        }

        List<RecordedEvent> postures = RecordingFile.readAllEvents(dump).stream()
                .filter(event -> POSTURE_EVENT.equals(event.getEventType().getName()))
                .toList();
        assertThat(postures).hasSize(1);
        assertThat(postures.getFirst().getString("requirement")).isEqualTo("PLAINTEXT");
        assertThat(postures.getFirst().getString("posture"))
                .as("plaintext although the Community crypto provider is bound")
                .isEqualTo("PLAINTEXT_REQUIRED");
    }
}
