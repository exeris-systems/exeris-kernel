/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.testing.HeldTimeSource;
import eu.exeris.kernel.core.contract.DeveloperWorkloadAdmission;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketHandshake;
import eu.exeris.kernel.spi.websocket.WebSocketHandshakeHandler;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A WebSocket listener honours the workload gate the way the HTTP listener does: the handshake counts as
 * one request, every status line it writes carries the licence marker, and a connection past the ceiling
 * is closed at accept. The gate is read in {@code start()}, so each case starts the engine inside the
 * scope that binds it.
 */
@DisplayName("Community: WebSocket workload admission")
class CommunityWebSocketWorkloadAdmissionTest {

    private static final String MARKER = "exeris-license: development";
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    private static WebSocketServerEngine start(WorkloadAdmission gate) {
        return start(gate, null);
    }

    private static WebSocketServerEngine start(WorkloadAdmission gate, WebSocketHandshakeHandler handshake) {
        WebSocketConfig config = WebSocketConfig.defaultServer("127.0.0.1", 0, List.of());
        WebSocketServerEngine[] holder = new WebSocketServerEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new CommunityWebSocketProvider().createServerEngine(config));
        holder[0].setHandler(exchange -> {
            while (exchange.receive() != null) {
                // Drain; the cases assert on the handshake.
            }
        });
        if (handshake != null) {
            holder[0].setHandshakeHandler(handshake);
        }
        if (gate == null) {
            holder[0].start();
        } else {
            ScopedValue.where(ContractKernelProviders.WORKLOAD_ADMISSION, gate).run(holder[0]::start);
        }
        return holder[0];
    }

    private static DeveloperWorkloadAdmission developerGate(long requests, int connections) {
        return new DeveloperWorkloadAdmission(
                "EXR-WS-ADMISSION", new WorkloadEnvelope(requests, connections, 0), new HeldTimeSource());
    }

    @Test
    @DisplayName("a 101 carries the marker once, and a handshake past the request ceiling is answered 429")
    void handshakeIsMarkedAndThrottled() throws Exception {
        try (WebSocketServerEngine engine = start(developerGate(1, 10));
             Socket accepted = connect(engine.boundPort());
             Socket refused = connect(engine.boundPort())) {
            String upgraded = handshake(accepted);
            String throttled = handshake(refused);

            assertThat(upgraded).startsWith("HTTP/1.1 101");
            assertThat(lines(upgraded)).containsOnlyOnce(MARKER);
            assertThat(throttled).startsWith("HTTP/1.1 429");
            assertThat(lines(throttled)).contains("retry-after: 1").containsOnlyOnce(MARKER);
        }
    }

    @Test
    @DisplayName("a connection past the ceiling is closed with no byte read")
    void connectionCeiling() throws Exception {
        try (WebSocketServerEngine engine = start(developerGate(1_000, 1));
             Socket held = connect(engine.boundPort())) {
            assertThat(handshake(held)).startsWith("HTTP/1.1 101");

            try (Socket surplus = connect(engine.boundPort())) {
                assertThat(readOrClosed(surplus))
                        .as("a refused connection is closed before the server writes a byte")
                        .isEqualTo(-1);
            }
        }
    }

    private static int readOrClosed(Socket socket) throws IOException {
        try {
            return socket.getInputStream().read();
        } catch (SocketException _) {
            // A reset is a close with no bytes either; a read that timed out is not caught and fails the case.
            return -1;
        }
    }

    @Test
    @DisplayName("UNGATED: the same handshakes carry no marker and are not refused")
    void ungatedDirectionControl() throws Exception {
        try (WebSocketServerEngine engine = start(WorkloadAdmission.UNGATED)) {
            for (int i = 0; i < 3; i++) {
                try (Socket client = connect(engine.boundPort())) {
                    String reply = handshake(client);

                    assertThat(reply).startsWith("HTTP/1.1 101");
                    assertThat(lines(reply)).doesNotContain(MARKER);
                }
            }
        }
    }

    @Test
    @DisplayName("an unbound slot behaves as UNGATED")
    void unboundDirectionControl() throws Exception {
        try (WebSocketServerEngine engine = start(null);
             Socket client = connect(engine.boundPort())) {
            String reply = handshake(client);

            assertThat(reply).startsWith("HTTP/1.1 101");
            assertThat(lines(reply)).doesNotContain(MARKER);
        }
    }

    private static final WebSocketHandshakeHandler HANDLER_CHOOSES_429 =
            request -> WebSocketHandshake.refuse(HttpStatus.TOO_MANY_REQUESTS);

    @Test
    @DisplayName("UNGATED: a 429 the handshake handler chose is written exactly as before, with no Retry-After")
    void handlerChosen429IsUntouchedWhenUngated() throws Exception {
        try (WebSocketServerEngine engine = start(WorkloadAdmission.UNGATED, HANDLER_CHOOSES_429);
             Socket client = connect(engine.boundPort())) {
            assertThat(handshake(client)).isEqualTo(
                    "HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
        }
    }

    @Test
    @DisplayName("enforced: a 429 the handshake handler chose carries the marker and no Retry-After")
    void handlerChosen429CarriesNoRetryAfterWhenEnforced() throws Exception {
        try (WebSocketServerEngine engine = start(developerGate(1_000, 10), HANDLER_CHOOSES_429);
             Socket client = connect(engine.boundPort())) {
            String reply = handshake(client);

            assertThat(reply).startsWith("HTTP/1.1 429");
            assertThat(lines(reply)).containsOnlyOnce(MARKER).noneMatch(line -> line.startsWith("retry-after"));
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static String handshake(Socket client) throws IOException {
        client.getOutputStream().write((
                "GET /chat HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: " + KEY + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        InputStream in = client.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("the connection closed before the response head ended: " + head);
            }
            head.append((char) next);
        }
        return head.toString();
    }

    private static List<String> lines(String reply) {
        return List.of(reply.toLowerCase(Locale.ROOT).split("\r\n"));
    }
}
