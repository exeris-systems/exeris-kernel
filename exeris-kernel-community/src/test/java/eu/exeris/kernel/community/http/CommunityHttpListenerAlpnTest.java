/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener's ALPN list follows {@code http.maxVersion}: HTTP/2 is offered only where it is a
 * permitted version.
 */
@DisplayName("CommunityHttpTransportFactory#listenerAlpn — the listener offers what maxVersion permits")
class CommunityHttpListenerAlpnTest {

    @Test
    @DisplayName("an engine capped at HTTP/1.1 offers http/1.1 only")
    void http11CapOffersHttp11Only() {
        assertThat(CommunityHttpTransportFactory.listenerAlpn(HttpVersion.HTTP_1_1))
                .isEqualTo(List.of("http/1.1"));
    }

    @Test
    @DisplayName("an engine capped at HTTP/2 offers h2 before http/1.1")
    void http2CapOffersH2ThenHttp11() {
        assertThat(CommunityHttpTransportFactory.listenerAlpn(HttpVersion.HTTP_2))
                .isEqualTo(List.of("h2", "http/1.1"));
    }

    @Test
    @DisplayName("an engine capped at HTTP/3 offers h2 before http/1.1 on its TCP listener")
    void http3CapOffersH2ThenHttp11() {
        assertThat(CommunityHttpTransportFactory.listenerAlpn(HttpVersion.HTTP_3))
                .isEqualTo(List.of("h2", "http/1.1"));
    }
}
