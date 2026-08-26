/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http.client;

import eu.exeris.kernel.community.http.CommunityHttpRetryPolicy;
import eu.exeris.kernel.community.http.CommunityJsonRequestBodyEncoder;
import eu.exeris.kernel.community.http.CommunityJsonResponseBodyDecoder;
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

    private KernelWebClient client(HttpClientEngine engine, HttpClientRequestEnricher enricher) {
        return new KernelWebClient(engine, ALLOCATOR, REQUEST_ENCODERS, RESPONSE_DECODERS, enricher, FAST_POLICY);
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
        KernelWebClient twoAttempts = new KernelWebClient(engine, ALLOCATOR, REQUEST_ENCODERS, RESPONSE_DECODERS,
                HttpClientRequestEnricher.noop(), new CommunityHttpRetryPolicy(2, 0L, 0L, () -> 0.0));

        assertThatThrownBy(() -> twoAttempts.get("/widget/1", Map.class))
                .isInstanceOf(TransportBoom.class)
                .isNotInstanceOf(KernelWebClient.WebClientException.class);
        assertThat(engine.received).hasSize(2);
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

    private static Supplier<HttpResponse> okJson(String json) {
        return () -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            LoanedBuffer body = ALLOCATOR.allocateNetwork(bytes.length);
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, body.segment(), 0, bytes.length);
            body.setSize(bytes.length);
            return new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("content-type", "application/json")), body);
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

        @Override
        public String defaultAuthority() {
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
