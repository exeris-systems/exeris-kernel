/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.testing.HeldTimeSource;
import eu.exeris.kernel.core.contract.DeveloperWorkloadAdmission;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.http.AbstractWorkloadAdmissionTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link AbstractWorkloadAdmissionTck} to the Community HTTP server.
 *
 * <p>The engine is created inside the scope that binds the memory allocator, as a server engine
 * requires, and started by the suite inside the scope that binds the workload gate.
 */
@DisplayName("Community: HTTP workload admission TCK")
class CommunityWorkloadAdmissionTckTest extends AbstractWorkloadAdmissionTck {

    private static final String HOST = "127.0.0.1";

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected HttpHandler emptyRouter() {
        return HttpRouter.builder().build();
    }

    @Override
    protected StartedServer startServer(HttpHandler handler) {
        int port = nextFreePort();
        HttpConfig config = new HttpConfig(
                HttpMode.SERVER,
                HOST,
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2,
                null,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
        HttpServerEngine[] holder = new HttpServerEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new CommunityHttpProvider().createServerEngine(config));
        holder[0].setHandler(handler);
        holder[0].start();
        return new StartedServer(holder[0], HOST, port);
    }

    @Test
    @DisplayName("the 101 that completes an h2c upgrade carries the licence marker once")
    void h2cUpgradeSwitchingProtocolsCarriesTheMarker() throws Exception {
        WorkloadAdmission gate = new DeveloperWorkloadAdmission(
                "EXR-H2C-UPGRADE", new WorkloadEnvelope(1_000, 100, 0), new HeldTimeSource());
        StartedServer[] holder = new StartedServer[1];
        ScopedValue.where(ContractKernelProviders.WORKLOAD_ADMISSION, gate)
                .run(() -> holder[0] = startServer(exchange -> exchange.respond(HttpStatus.OK)));
        try (StartedServer server = holder[0];
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress(server.host(), server.port()), 5_000);
            client.setSoTimeout(10_000);
            client.getOutputStream().write("""
                    GET / HTTP/1.1\r
                    Host: localhost\r
                    Connection: Upgrade, HTTP2-Settings\r
                    Upgrade: h2c\r
                    HTTP2-Settings: AAMAAABkAAQAoAAAAAIAAAAA\r
                    \r
                    """.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            StringBuilder head = new StringBuilder();
            InputStream in = client.getInputStream();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int next = in.read();
                assertThat(next).as("the connection stays open until the 101 head ends: %s", head).isNotNegative();
                head.append((char) next);
            }

            assertThat(head.toString()).startsWith("HTTP/1.1 101");
            assertThat(List.of(head.toString().toLowerCase(Locale.ROOT).split("\r\n")))
                    .containsOnlyOnce("exeris-license: development");
        }
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }
}
