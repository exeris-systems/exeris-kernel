/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathParamStreamExchangeTest {

    @Test
    void peerOfTheStreamsConnectionIsDelegated() {
        PeerStream delegate = new PeerStream("10.1.2.3", 51234);
        PathParamStreamExchange exchange = new PathParamStreamExchange(delegate, Map.of("id", "42"));

        assertEquals("10.1.2.3", exchange.remoteAddress());
        assertEquals(51234, exchange.remotePort());
        assertEquals("42", exchange.pathParams().get("id"));
    }

    @Test
    void aStreamWithNoNetworkTransportStillReportsTheSpiAbsentValues() {
        PeerStream delegate = new PeerStream(null, -1);
        PathParamStreamExchange exchange = new PathParamStreamExchange(delegate, Map.of("id", "42"));

        assertNull(exchange.remoteAddress());
        assertEquals(-1, exchange.remotePort());
    }

    /** A stream whose peer is fixed at construction, and which does nothing else. */
    private record PeerStream(String address, int port) implements HttpStreamExchange {

        @Override
        public HttpRequest request() {
            return HttpRequest.noBody(HttpMethod.GET, "/s/42", HttpVersion.HTTP_1_1, List.of());
        }

        @Override
        public String remoteAddress() {
            return address;
        }

        @Override
        public int remotePort() {
            return port;
        }

        @Override
        public void emit(StreamEvent event) {
            // not exercised by these cases
        }

        @Override
        public void close() {
            // not exercised by these cases
        }
    }
}
