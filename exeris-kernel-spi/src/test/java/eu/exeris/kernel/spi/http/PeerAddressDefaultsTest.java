/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An exchange that no network transport backs, and that does not override the peer accessors,
 * reports an unknown peer: {@code null} and {@code -1}, never an address a per-client key could
 * mistake for a real one.
 */
@DisplayName("HttpExchange / HttpStreamExchange: the peer accessors default to an unknown peer")
class PeerAddressDefaultsTest {

    @Test
    @DisplayName("an HttpExchange that does not override them reports null and -1")
    void exchangeDefaultsToAnUnknownPeer() {
        HttpExchange exchange = new HttpExchange() {
            @Override
            public HttpRequest request() {
                throw new UnsupportedOperationException("not used by this test");
            }

            @Override
            public void respond(HttpResponse response) {
                throw new UnsupportedOperationException("not used by this test");
            }
        };

        assertThat(exchange.remoteAddress()).isNull();
        assertThat(exchange.remotePort()).isEqualTo(-1);
    }

    @Test
    @DisplayName("an HttpStreamExchange that does not override them reports null and -1")
    void streamExchangeDefaultsToAnUnknownPeer() {
        HttpStreamExchange exchange = new HttpStreamExchange() {
            @Override
            public HttpRequest request() {
                throw new UnsupportedOperationException("not used by this test");
            }

            @Override
            public void emit(StreamEvent event) {
                throw new UnsupportedOperationException("not used by this test");
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        assertThat(exchange.remoteAddress()).isNull();
        assertThat(exchange.remotePort()).isEqualTo(-1);
    }
}
