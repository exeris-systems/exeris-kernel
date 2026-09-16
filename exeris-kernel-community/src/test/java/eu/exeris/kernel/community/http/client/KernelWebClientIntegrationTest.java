/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http.client;

import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.community.http.CommunityJsonRequestBodyEncoder;
import eu.exeris.kernel.community.http.CommunityJsonResponseBodyDecoder;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.client.KernelWebClient;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Community: KernelWebClient integration (loopback HttpServerEngine round-trip)")
class KernelWebClientIntegrationTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final HttpRequestBodyEncoderRegistry REQUEST_ENCODERS =
            HttpRequestBodyEncoderRegistry.of(
                    List.<HttpRequestBodyEncoder>of(new CommunityJsonRequestBodyEncoder(MAPPER)));
    private static final HttpResponseBodyDecoderRegistry RESPONSE_DECODERS =
            HttpResponseBodyDecoderRegistry.of(
                    List.<HttpResponseBodyDecoder>of(new CommunityJsonResponseBodyDecoder(MAPPER)));

    private final HttpProvider provider = new CommunityHttpProvider();
    private final AtomicReference<HandlerHook> handlerHook = new AtomicReference<>();

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("GET 200 round-trip deserialises JSON body into the requested type")
    void getRoundTrip() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> {
                assertThat(method).isEqualTo(HttpMethod.GET);
                assertThat(path).isEqualTo("/widget/42");
                respondWithJson(exchange, HttpStatus.OK, Map.of("id", "42", "name", "Cogwheel"));
            });

            @SuppressWarnings("unchecked") Map<String, Object> body = client.get("/widget/42", Map.class);

            assertThat(body).containsEntry("id", "42").containsEntry("name", "Cogwheel");
        });
    }

    @Test
    @DisplayName("POST 201 round-trip serialises request body and deserialises response")
    void postRoundTrip() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> {
                assertThat(method).isEqualTo(HttpMethod.POST);
                assertThat(path).isEqualTo("/widget");
                byte[] requestBytes = readRequestBody(exchange.request().body());
                assertThat(new String(requestBytes, StandardCharsets.UTF_8))
                        .contains("\"name\":\"Cogwheel\"");
                respondWithJson(exchange, new HttpStatus(201, "Created"),
                        Map.of("id", "1", "name", "Cogwheel"));
            });

            @SuppressWarnings("unchecked") Map<String, Object> created =
                    client.post("/widget", Map.of("name", "Cogwheel"), Map.class);

            assertThat(created).containsEntry("id", "1").containsEntry("name", "Cogwheel");
        });
    }

    @Test
    @DisplayName("PATCH 200 round-trip serialises request body and deserialises response")
    void patchRoundTrip() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> {
                assertThat(method).isEqualTo(HttpMethod.PATCH);
                assertThat(path).isEqualTo("/widget/42");
                byte[] requestBytes = readRequestBody(exchange.request().body());
                assertThat(new String(requestBytes, StandardCharsets.UTF_8))
                        .contains("\"name\":\"Updated\"");
                respondWithJson(exchange, HttpStatus.OK, Map.of("id", "42", "name", "Updated"));
            });

            @SuppressWarnings("unchecked") Map<String, Object> updated =
                    client.patch("/widget/42", Map.of("name", "Updated"), Map.class);

            assertThat(updated).containsEntry("id", "42").containsEntry("name", "Updated");
        });
    }

    @Test
    @DisplayName("DELETE 204 with Void.class returns null and consumes no body")
    void deleteNoBodyReturnsNull() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> {
                assertThat(method).isEqualTo(HttpMethod.DELETE);
                assertThat(path).isEqualTo("/widget/42");
                exchange.respond(HttpResponse.noBody(new HttpStatus(204, "No Content"), HttpVersion.HTTP_1_1));
            });

            Void result = client.delete("/widget/42", Void.class);

            assertThat(result).isNull();
        });
    }

    @Test
    @DisplayName("GET 404 throws WebClientException whose isNotFound() is true")
    void notFoundMapping() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) ->
                    exchange.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, HttpVersion.HTTP_1_1)));

            assertThatThrownBy(() -> client.get("/widget/missing", Map.class))
                    .isInstanceOf(KernelWebClient.WebClientException.class)
                    .satisfies(ex -> {
                        KernelWebClient.WebClientException wce = (KernelWebClient.WebClientException) ex;
                        assertThat(wce.status()).isEqualTo(404);
                        assertThat(wce.isNotFound()).isTrue();
                    });
        });
    }

    @Test
    @DisplayName("GET 500 throws WebClientException with status + body but does not retry")
    void serverErrorMapping() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> respondWithBytes(
                    exchange,
                    new HttpStatus(500, "Internal Server Error"),
                    "boom".getBytes(StandardCharsets.UTF_8),
                    "text/plain"));

            assertThatThrownBy(() -> client.get("/widget/broken", Map.class))
                    .isInstanceOf(KernelWebClient.WebClientException.class)
                    .satisfies(ex -> {
                        KernelWebClient.WebClientException wce = (KernelWebClient.WebClientException) ex;
                        assertThat(wce.status()).isEqualTo(500);
                        assertThat(wce.isNotFound()).isFalse();
                        assertThat(wce.responseBody()).isEqualTo("boom");
                    });
        });
    }

    @Test
    @DisplayName("Constructor rejects null engine (Objects.requireNonNull contract)")
    void constructorRejectsNullEngine() {
        assertThatNullPointerException()
                .isThrownBy(() -> new KernelWebClient(null, ALLOCATOR, REQUEST_ENCODERS, RESPONSE_DECODERS));
    }

    @Test
    @DisplayName("Verb methods reject null path / responseType / body-for-POST")
    void verbMethodsRejectNullArguments() {
        runScopedTest(client -> {
            assertThatNullPointerException()
                    .isThrownBy(() -> client.get(null, Map.class));
            assertThatNullPointerException()
                    .isThrownBy(() -> client.get("/p", null));
            assertThatNullPointerException()
                    .isThrownBy(() -> client.post("/p", null, Map.class));
            assertThatNullPointerException()
                    .isThrownBy(() -> client.patch("/p", null, Map.class));
        });
    }

    @Test
    @DisplayName("getList decodes a JSON array into the element type")
    void getListDecodesElements() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> respondWithJson(exchange, HttpStatus.OK,
                    List.of(Map.of("id", "1", "name", "Cogwheel"), Map.of("id", "2", "name", "Sprocket"))));

            List<Widget> widgets = client.getList("/widgets", Widget.class);

            assertThat(widgets).containsExactly(new Widget("1", "Cogwheel"), new Widget("2", "Sprocket"));
        });
    }

    @Test
    @DisplayName("the same response read through get(path, List.class) does not produce elements of that type")
    void rawListReadLosesTheElementType() {
        // The defect getList exists to remove, pinned against the same wire bytes: a raw List.class
        // hands the decoder no element type, so every element decodes to the driver's default mapping
        // and the caller's first field access fails on a type it never wrote. Without this control,
        // the test above would pass just as happily if getList delegated to get(path, List.class).
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> respondWithJson(exchange, HttpStatus.OK,
                    List.of(Map.of("id", "1", "name", "Cogwheel"))));

            List<?> raw = client.get("/widgets", List.class);

            assertThat(raw).hasSize(1);
            assertThat(raw.get(0)).isInstanceOf(Map.class).isNotInstanceOf(Widget.class);
        });
    }

    @Test
    @DisplayName("getList returns an empty list for an empty JSON array")
    void getListEmptyArray() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) ->
                    respondWithJson(exchange, HttpStatus.OK, List.of()));

            assertThat(client.getList("/widgets", Widget.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("getList returns an empty list for a literal null JSON body")
    void getListLiteralNullBody() {
        // Not the same case as an empty body, which finishAttempt rejects before any decoder runs:
        // "null" is four bytes on the wire, reaches the decoder, and decodes to null. This is the
        // only way the decoded == null branch is reachable, and pinning it is what keeps that branch
        // from reading as dead code to the next person.
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> respondWithBytes(exchange, HttpStatus.OK,
                    "null".getBytes(StandardCharsets.UTF_8), "application/json"));

            assertThat(client.getList("/widgets", Widget.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("getList hands back an unmodifiable list")
    void getListIsUnmodifiable() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> respondWithJson(exchange, HttpStatus.OK,
                    List.of(Map.of("id", "1", "name", "Cogwheel"))));

            List<Widget> widgets = client.getList("/widgets", Widget.class);

            // Both, and for different reasons: a bare Arrays.asList view refuses add() as well,
            // but honours set() straight into the decoded array. Asserting only add() would
            // therefore accept a list that is not immutable.
            assertThatThrownBy(() -> widgets.add(new Widget("2", "Sprocket")))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> widgets.set(0, new Widget("2", "Sprocket")))
                    .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    @DisplayName("getList passes a null element through rather than rejecting the response")
    void getListPassesNullElementThrough() {
        // What the peer sent is what the caller is told it sent. List.of(...) would reject this with
        // an NPE naming nothing the caller can act on.
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) ->
                    respondWithBytes(exchange, HttpStatus.OK,
                            "[null]".getBytes(StandardCharsets.UTF_8), "application/json"));

            assertThat(client.getList("/widgets", Widget.class)).containsExactly((Widget) null);
        });
    }

    @Test
    @DisplayName("getList surfaces a non-2xx response as WebClientException, like get")
    void getListNonSuccess() {
        runScopedTest(client -> {
            handlerHook.set((method, path, exchange) -> respondWithBytes(exchange,
                    HttpStatus.NOT_FOUND, "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8),
                    "application/json"));

            assertThatThrownBy(() -> client.getList("/widgets", Widget.class))
                    .isInstanceOf(KernelWebClient.WebClientException.class);
        });
    }

    @Test
    @DisplayName("getList rejects a null element type")
    void getListRejectsNullElementType() {
        runScopedTest(client ->
                assertThatNullPointerException().isThrownBy(() -> client.getList("/widgets", null)));
    }

    private record Widget(String id, String name) {}

    private void runScopedTest(Consumer<KernelWebClient> testCase) {
        java.lang.ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            int port = nextFreePort();
            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine engine = provider.createClientEngine(clientConfig(port))) {

                server.setHandler(exchange -> {
                    HandlerHook hook = handlerHook.get();
                    if (hook != null) {
                        hook.handle(exchange.request().method(), exchange.request().path(), exchange);
                        return;
                    }
                    exchange.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, HttpVersion.HTTP_1_1));
                });

                server.start();
                engine.start();
                KernelWebClient client = new KernelWebClient(engine, ALLOCATOR, REQUEST_ENCODERS, RESPONSE_DECODERS);

                testCase.accept(client);
            }
        });
    }

    private void respondWithJson(HttpExchange exchange,
                                 HttpStatus status,
                                 Object payload) {
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("test serialisation failed", ex);
        }
        respondWithBytes(exchange, status, bytes, "application/json");
    }

    private void respondWithBytes(HttpExchange exchange,
                                  HttpStatus status,
                                  byte[] bytes,
                                  String contentType) {
        LoanedBuffer body = ALLOCATOR.allocateNetwork(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, body.segment(), 0, bytes.length);
        body.setSize(bytes.length);
        List<HttpHeader> headers = List.of(
                new HttpHeader("content-type", contentType),
                new HttpHeader("content-length", Integer.toString(bytes.length))
        );
        exchange.respond(new HttpResponse(status, HttpVersion.HTTP_1_1, headers, body));
    }

    private static byte[] readRequestBody(LoanedBuffer body) {
        if (body == null) {
            return new byte[0];
        }
        long size = body.size();
        byte[] out = new byte[Math.toIntExact(size)];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(out), 0L, size);
        return out;
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    private static HttpConfig serverConfig(int port) {
        return new HttpConfig(
                HttpMode.SERVER,
                "127.0.0.1",
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                false,
                HttpVersion.HTTP_1_1
        );
    }

    private static HttpConfig clientConfig(int port) {
        return new HttpConfig(
                HttpMode.CLIENT,
                "127.0.0.1",
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                false,
                HttpVersion.HTTP_1_1,
                "127.0.0.1" + ":" + port,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE
        );
    }

    @FunctionalInterface
    private interface HandlerHook {
        void handle(HttpMethod method, String path, HttpExchange exchange);
    }
}
