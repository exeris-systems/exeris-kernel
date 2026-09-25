/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http.client;

import eu.exeris.kernel.community.http.CommunityHttpRetryPolicy;
import eu.exeris.kernel.community.http.CommunityJsonRequestBodyEncoder;
import eu.exeris.kernel.community.http.CommunityJsonResponseBodyDecoder;
import eu.exeris.kernel.community.http.LeakTrackingAllocator;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.client.KernelWebClient;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpClientRequestEnricher;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpRetryPolicy;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RetryDecision;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Community: KernelWebClient retry loop (ADR-045) over a programmed engine")
class KernelWebClientRetryTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpRequestBodyEncoderRegistry REQUEST_ENCODERS =
            HttpRequestBodyEncoderRegistry.of(
                    List.<HttpRequestBodyEncoder>of(new CommunityJsonRequestBodyEncoder(MAPPER)));
    private static final HttpResponseBodyDecoderRegistry RESPONSE_DECODERS =
            HttpResponseBodyDecoderRegistry.of(
                    List.<HttpResponseBodyDecoder>of(new CommunityJsonResponseBodyDecoder(MAPPER)));

    // base=0 / max=0 → zero-delay retries so the test never sleeps.
    private static final HttpRetryPolicy FAST_POLICY = new CommunityHttpRetryPolicy(3, 0L, 0L, () -> 0.0);

    // One per test: every buffer the client encodes and every response body the engine hands back
    // comes from here, so outstanding() == 0 after a call means the call released everything it took.
    private final LeakTrackingAllocator allocator = new LeakTrackingAllocator(ALLOCATOR);

    private KernelWebClient client(HttpClientEngine engine, HttpClientRequestEnricher enricher) {
        return client(engine, enricher, FAST_POLICY);
    }

    private KernelWebClient client(HttpClientEngine engine, HttpClientRequestEnricher enricher,
                                   HttpRetryPolicy policy) {
        return new KernelWebClient(engine, allocator, REQUEST_ENCODERS, RESPONSE_DECODERS, enricher, policy);
    }

    @Test
    @DisplayName("one engine serves two peers — withAuthority derives, it does not mutate")
    void oneEngineServesManyPeers() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(
                okJson("{\"ok\":\"a\"}"), okJson("{\"ok\":\"b\"}"), okJson("{\"ok\":\"c\"}")));
        engine.defaultAuthority = "default.internal:80";

        KernelWebClient base = client(engine, HttpClientRequestEnricher.noop());
        KernelWebClient payments = base.withAuthority("payments.internal:8443");

        // This is the whole point of putting the authority on the request rather than on the engine,
        // and until this method existed it was unreachable from the typed surface: every call went
        // to whatever single peer the engine was configured with.
        payments.get("/charge", Map.class);
        base.get("/health", Map.class);
        payments.get("/refund", Map.class);

        assertThat(engine.received).extracting(HttpRequest::authority)
                .as("the derived client addresses its peer and leaves the original addressing the default")
                .containsExactly("payments.internal:8443", "default.internal:80", "payments.internal:8443");
    }

    @Test
    @DisplayName("withAuthority returns the same instance when the peer is already the bound one")
    void withAuthorityIsIdentityWhenUnchanged() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of());
        KernelWebClient base = client(engine, HttpClientRequestEnricher.noop());

        assertThat(base.withAuthority(null)).isSameAs(base);
        KernelWebClient bound = base.withAuthority("a.internal:1");
        assertThat(bound.withAuthority("a.internal:1")).isSameAs(bound);
        assertThat(bound).isNotSameAs(base);
    }

    @Test
    @DisplayName("the enricher observes the resolved authority, not null (ADR-074 decision 5)")
    void enricherObservesTheResolvedAuthority() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(okJson("{\"ok\":\"yes\"}")));
        engine.defaultAuthority = "payments.internal:8443";
        AtomicReference<String> seenByEnricher = new AtomicReference<>();

        // ADR-074 decided the ordering as authority-THEN-enrich-THEN-send, and the reason is not
        // tidiness: an enricher binding an outbound credential's audience to its peer (ADR-040) has
        // only the request to read. If the engine substituted its default inside send(), enrichment
        // would already have run and this would be null on every request — which was the case until
        // KernelWebClient started resolving it first.
        HttpClientRequestEnricher recording = request -> {
            seenByEnricher.set(request.authority());
            return request;
        };

        client(engine, recording).get("/widget/1", Map.class);

        assertThat(seenByEnricher.get())
                .as("the enricher must see the peer the request is actually sent to")
                .isEqualTo("payments.internal:8443");
    }

    @Test
    @DisplayName("retries an idempotent GET on 503, then returns the success body")
    void retriesThenSucceeds() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(
                serviceUnavailable(), okJson("{\"ok\":\"yes\"}")));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = client(engine, HttpClientRequestEnricher.noop()).get("/widget/1", Map.class);

        assertThat(body).containsEntry("ok", "yes");
        assertThat(engine.received).hasSize(2);
    }

    @Test
    @DisplayName("gives up after the attempt cap and surfaces the final status")
    void givesUpAfterCap() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(
                serviceUnavailable(), serviceUnavailable(), serviceUnavailable()));

        assertThatThrownBy(() -> client(engine, HttpClientRequestEnricher.noop()).get("/widget/1", Map.class))
                .isInstanceOf(KernelWebClient.WebClientException.class)
                .extracting(ex -> ((KernelWebClient.WebClientException) ex).status())
                .isEqualTo(503);
        assertThat(engine.received).hasSize(3);
    }

    @Test
    @DisplayName("re-encodes the body on each attempt (POST carrying an Idempotency-Key)")
    void reEncodesBodyEachAttempt() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(
                serviceUnavailable(), okJson("{\"id\":\"7\"}")));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = client(engine, idempotencyKey("k-1"))
                .post("/widget", Map.of("name", "Cogwheel"), Map.class);

        assertThat(body).containsEntry("id", "7");
        assertThat(engine.received).hasSize(2);
        // Each attempt carried a freshly-encoded body — no LoanedBuffer reused across attempts.
        assertThat(engine.received).allSatisfy(request -> assertThat(request.body()).isNotNull());
    }

    @Test
    @DisplayName("re-throws the original transport failure unchanged after the cap (not a WebClientException)")
    void transportFailureGivesUpUnwrapped() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(boom(), boom()));
        KernelWebClient twoAttempts = client(engine, HttpClientRequestEnricher.noop(),
                new CommunityHttpRetryPolicy(2, 0L, 0L, () -> 0.0));

        assertThatThrownBy(() -> twoAttempts.get("/widget/1", Map.class))
                .isInstanceOf(TransportBoom.class)
                .isNotInstanceOf(KernelWebClient.WebClientException.class);
        assertThat(engine.received).hasSize(2);
    }

    @Test
    @DisplayName("a successful POST releases its request body — every buffer the call took is returned")
    void postReleasesRequestBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(okJson("{\"id\":\"1\"}")));

        client(engine, HttpClientRequestEnricher.noop()).post("/widget", Map.of("name", "Cogwheel"), Map.class);

        assertThat(allocator.allocated())
                .as("the request body and the response body were both allocated through the tracker")
                .isEqualTo(2);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
        assertThat(engine.received.getFirst().body().isAlive())
                .as("the request body sent is released once the call returns").isFalse();
    }

    @Test
    @DisplayName("503 then 200 on a POST: each attempt's request body is released before the policy decides")
    void retriedPostReleasesEachAttemptsBodyBeforeTheWait() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(
                serviceUnavailable(), okJson("{\"id\":\"7\"}")));
        List<Boolean> bodyAliveAtDecide = new ArrayList<>();
        HttpRetryPolicy recording = (request, outcome, attemptIndex) -> {
            bodyAliveAtDecide.add(request.body().isAlive());
            return FAST_POLICY.decide(request, outcome, attemptIndex);
        };

        client(engine, idempotencyKey("k-1"), recording).post("/widget", Map.of("name", "Cogwheel"), Map.class);

        assertThat(engine.received).hasSize(2);
        assertThat(bodyAliveAtDecide)
                .as("the policy, and the wait it asks for, run after the attempt's body is released")
                .containsExactly(false);
        assertThat(allocator.allocated()).as("two request bodies and one response body").isEqualTo(3);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("transport failure up to the retry cap: every attempt's request body is released")
    void transportFailureToCapReleasesEveryBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(boom(), boom(), boom()));

        KernelWebClient client = client(engine, idempotencyKey("k-2"));
        Map<String, String> widget = Map.of("name", "Cogwheel");

        assertThatThrownBy(() -> client.post("/widget", widget, Map.class))
                .isInstanceOf(TransportBoom.class);

        assertThat(engine.received).hasSize(3);
        assertThat(allocator.allocated()).as("one request body per attempt").isEqualTo(3);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("an enricher that throws: the encoded request body is released before the failure surfaces")
    void enricherFailureReleasesBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of());
        HttpClientRequestEnricher refusing = request -> {
            throw new IllegalArgumentException("header value carries CR");
        };

        KernelWebClient client = client(engine, refusing);
        Map<String, String> widget = Map.of("name", "Cogwheel");

        assertThatThrownBy(() -> client.post("/widget", widget, Map.class))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(engine.received).as("nothing was sent").isEmpty();
        assertThat(allocator.allocated()).as("the request body was encoded").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("a default-authority lookup that throws: the encoded request body is released")
    void defaultAuthorityFailureReleasesBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of());
        engine.defaultAuthorityFailure = new IllegalStateException("engine closed");

        KernelWebClient client = client(engine, HttpClientRequestEnricher.noop());
        Map<String, String> widget = Map.of("name", "Cogwheel");

        assertThatThrownBy(() -> client.post("/widget", widget, Map.class))
                .isInstanceOf(IllegalStateException.class);

        assertThat(engine.received).as("nothing was sent").isEmpty();
        assertThat(allocator.allocated()).as("the request body was encoded").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("a final non-2xx on a POST: request and response bodies of every attempt are released")
    void finalNonSuccessReleasesEveryBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(
                status(503, "busy"), status(503, "busy"), status(503, "still busy")));

        KernelWebClient client = client(engine, idempotencyKey("k-3"));
        Map<String, String> widget = Map.of("name", "Cogwheel");

        assertThatThrownBy(() -> client.post("/widget", widget, Map.class))
                .isInstanceOf(KernelWebClient.WebClientException.class)
                .extracting(ex -> ((KernelWebClient.WebClientException) ex).responseBody())
                .isEqualTo("still busy");

        assertThat(engine.received).hasSize(3);
        assertThat(allocator.allocated()).as("three request bodies and three response bodies").isEqualTo(6);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("an interrupted retry wait: the attempt's request body is already released")
    void interruptedRetryWaitReleasesBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(serviceUnavailable()));
        HttpRetryPolicy longWait = (request, outcome, attemptIndex) -> RetryDecision.retryAfter(60_000L);
        KernelWebClient client = client(engine, HttpClientRequestEnricher.noop(), longWait);

        // Set before the call: the wait is the only interruptible point on this path, so the sleep
        // throws at once instead of the test sleeping for a minute.
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.post("/widget", Map.of("name", "Cogwheel"), Map.class))
                    .isInstanceOf(KernelWebClient.WebClientException.class)
                    .hasMessageContaining("interrupted");
        } finally {
            Thread.interrupted();
        }

        assertThat(engine.received).hasSize(1);
        assertThat(allocator.allocated()).as("the request body was encoded").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    private static Supplier<HttpResponse> boom() {
        return () -> {
            throw new TransportBoom();
        };
    }

    /** Sentinel unchecked exception standing in for an engine-level transport failure. */
    private static final class TransportBoom extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TransportBoom() {
            super("simulated transport failure");
        }
    }

    private static HttpClientRequestEnricher idempotencyKey(String key) {
        return request -> {
            List<HttpHeader> headers = new ArrayList<>(request.headers());
            headers.add(new HttpHeader("Idempotency-Key", key));
            return new HttpRequest(request.method(), request.path(), request.version(),
                    List.copyOf(headers), request.body());
        };
    }

    private static Supplier<HttpResponse> serviceUnavailable() {
        return () -> HttpResponse.noBody(HttpStatus.SERVICE_UNAVAILABLE, HttpVersion.HTTP_1_1);
    }

    private Supplier<HttpResponse> okJson(String json) {
        return response(HttpStatus.OK, "application/json", json);
    }

    private Supplier<HttpResponse> status(int code, String text) {
        return response(new HttpStatus(code, "Status " + code), "text/plain", text);
    }

    private Supplier<HttpResponse> response(HttpStatus status, String contentType, String text) {
        return () -> {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            LoanedBuffer body = allocator.allocateNetwork(bytes.length);
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, body.segment(), 0, bytes.length);
            body.setSize(bytes.length);
            return new HttpResponse(status, HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("content-type", contentType)), body);
        };
    }

    /** A {@link HttpClientEngine} that returns a scripted sequence of responses and records requests. */
    private static final class ProgrammedEngine implements HttpClientEngine {

        private final Deque<Supplier<HttpResponse>> script;
        private final List<HttpRequest> received = new ArrayList<>();

        ProgrammedEngine(List<Supplier<HttpResponse>> responses) {
            this.script = new ArrayDeque<>(responses);
        }

        private String defaultAuthority;
        private RuntimeException defaultAuthorityFailure;

        @Override
        public String defaultAuthority() {
            if (defaultAuthorityFailure != null) {
                throw defaultAuthorityFailure;
            }
            return defaultAuthority;
        }

        @Override
        public String engineName() {
            return "programmed-stub";
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void start() {
            // no-op: this stub holds no transport resources.
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            // The engine reads the body during send, so it must still be live here: a caller that
            // released it early would hand the transport memory already back in the pool.
            if (request.hasBody() && !request.body().isAlive()) {
                throw new AssertionError("request body was released before engine.send read it");
            }
            received.add(request);
            Supplier<HttpResponse> next = script.poll();
            if (next == null) {
                throw new AssertionError("engine.send called more times than scripted");
            }
            return next.get();
        }

        @Override
        public void close() {
            // no-op.
        }
    }
}
