/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testing.http;

import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixture;
import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixtures;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpRequestDecodingContext;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-boot e2e for the W7 boot-path reachability fixes (ROADMAP: "Generated-App Boot-Path
 * Reachability — Path-Parameter Routing + Request-Decoder Scope"). Drives an {@link HttpRouter}
 * over a real kernel HTTP boot via a socket — the gaps it covers are invisible to handler unit
 * tests (which bypass the router) and to substring assertions on generated source.
 *
 * <ul>
 *   <li><b>T21 / path-parameter routing.</b> A by-id template route {@code /x/{id}} registered for
 *       GET/PUT/DELETE must <em>resolve</em> (not 404) on a real boot, and the captured {@code id}
 *       must reach the handler via {@link eu.exeris.kernel.spi.http.HttpExchange#pathParams()}.</li>
 *   <li><b>Request-decoder request-scope.</b> A {@code POST} with a JSON body must reach a handler
 *       whose {@link HttpKernelProviders#httpRequestBodyDecoderRegistry()} is bound — i.e. it
 *       <em>decodes</em> (not 500). The registry is bound into the kernel carrier scope by
 *       {@code CommunityHttpSubsystem.providerBindings()} but the native transport runs the handler
 *       on a reactor thread that does not inherit that scope; the per-request rebind at the dispatch
 *       seam is what makes the slot visible here.</li>
 * </ul>
 */
@DisplayName("W7 boot-path: path-parameter routing + request-decoder scope over a real boot")
class GeneratedAppBootPathReachabilityIntegrationTest {

    private static final String JSON_BODY = "{\"name\":\"vega\"}";

    @Test
    @DisplayName("by-id GET/PUT/DELETE resolve (not 404) and a JSON POST decodes (not 500)")
    void byIdRoutesResolveAndJsonPostDecodes() {
        HttpRouter router = HttpRouter.builder()
                .route(GeneratedAppBootPathReachabilityIntegrationTest::respondWithCapturedId, "/x/{id}",
                        HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)
                .route(HttpMethod.POST, "/x", GeneratedAppBootPathReachabilityIntegrationTest::decodeAndEcho)
                .build();

        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            fixture.start(router);
            int port = fixture.boundPort();

            // T21: every id-bearing verb resolves to the handler (literal {id} would 404).
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                String response = exchange(port, method.name() + " /x/42 HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Connection: close\r\n\r\n");
                assertThat(response)
                        .as("%s /x/{id} must resolve, not 404", method)
                        .contains("HTTP/1.1 200 OK")
                        .contains("X-Path-Id: 42");
            }

            // Control: a genuinely unregistered path still 404s.
            assertThat(exchange(port, "GET /nope HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"))
                    .contains("HTTP/1.1 404");

            // Write-over-HTTP: the decoder registry is in the handler's request scope → decode succeeds.
            String postResponse = exchange(port, "POST /x HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + JSON_BODY.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + JSON_BODY);
            assertThat(postResponse)
                    .as("POST with a JSON body must decode, not 500")
                    .contains("HTTP/1.1 200 OK")
                    .contains("X-Decoded-Name: vega");
        }
    }

    private static void respondWithCapturedId(eu.exeris.kernel.spi.http.HttpExchange exchange) {
        String id = exchange.pathParams().get("id");
        exchange.respond(HttpResponse.noBody(
                HttpStatus.OK,
                exchange.request().version(),
                List.of(new HttpHeader("X-Path-Id", id == null ? "" : id))));
    }

    private static void decodeAndEcho(eu.exeris.kernel.spi.http.HttpExchange exchange) {
        HttpRequestBodyDecoderRegistry registry =
                HttpKernelProviders.httpRequestBodyDecoderRegistry().orElse(null);
        if (registry == null) {
            // The pre-fix failure mode: registry not in request scope → server-side config error.
            exchange.respond(HttpStatus.INTERNAL_SERVER_ERROR);
            return;
        }
        String contentType = exchange.request().firstHeader("Content-Type").orElse(null);
        HttpRequestBodyDecoder decoder = registry.resolve(Map.class, contentType);
        if (decoder == null) {
            exchange.respond(HttpStatus.INTERNAL_SERVER_ERROR);
            return;
        }
        HttpRequestDecodingContext context = new HttpRequestDecodingContext(
                exchange.request().method(),
                exchange.request().path(),
                exchange.request().headers(),
                ThrowingTestAllocator.INSTANCE);
        Map<?, ?> decoded = (Map<?, ?>) decoder.decode(exchange.request().body(), Map.class, context);
        exchange.respond(HttpResponse.noBody(
                HttpStatus.OK,
                exchange.request().version(),
                List.of(new HttpHeader("X-Decoded-Name", String.valueOf(decoded.get("name"))))));
    }

    private static String exchange(int port, String rawRequest) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(2_000);
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read = in.read(chunk);
            while (read >= 0) {
                sink.write(chunk, 0, read);
                read = in.read(chunk);
            }
            return sink.toString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("HTTP exchange failed: " + rawRequest, exception);
        }
    }

    /**
     * The JSON request-body decoder never touches the decoding context's allocator (it copies the
     * off-heap body straight into Jackson), so this e2e supplies a placeholder rather than standing up
     * a real allocator on the handler thread. Every allocation method throws on purpose: if a future
     * decoder change starts allocating through the context, this fails loudly instead of silently
     * leaking an unbacked buffer.
     */
    private enum ThrowingTestAllocator implements MemoryAllocator {
        INSTANCE;

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MemoryStats stats() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
