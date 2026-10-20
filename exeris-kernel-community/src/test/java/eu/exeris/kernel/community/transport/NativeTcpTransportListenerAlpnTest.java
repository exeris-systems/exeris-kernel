/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A listener's ALPN list must name {@code http/1.1}, the fallback every TCP listener serves. The
 * refusal happens before any native resource is taken, so the test binds no providers.
 */
@DisplayName("NativeTcpTransportProvider#createEngine — a listener's ALPN list must name http/1.1")
class NativeTcpTransportListenerAlpnTest {

    @Test
    @DisplayName("a list naming h2 without http/1.1 is refused")
    void listWithoutHttp11IsRefused() {
        TransportConfig listener = new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", 8443, 1, null, null, 64, 30_000);

        assertThatThrownBy(() -> new NativeTcpTransportProvider().createEngine(
                listener, CommunityOutboundTls.AMBIENT, List.of("h2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/1.1");
    }
}
