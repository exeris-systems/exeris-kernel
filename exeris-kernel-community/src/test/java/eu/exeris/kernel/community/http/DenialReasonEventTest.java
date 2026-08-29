/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three unrelated situations produce one {@code 401}, and until 0.12 two of them produced no
 * telemetry at all — while the JFR event's own description advertised both by name.
 *
 * <p>The contract has two halves and they pull against each other, so both are asserted here: the
 * <b>response must be identical</b> for every reason, because telling an unauthenticated caller
 * which one applies hands them a probe oracle; and the <b>event must differ</b>, because otherwise a
 * deployment that never bound a provider is indistinguishable from clients that forgot their tokens
 * and gets diagnosed against the clients.
 */
@DisplayName("Community: a 401 says nothing to the caller and everything to the recording")
class DenialReasonEventTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.security.SecurityContextMissing";

    // A real allocator, not a mock: the TOKEN_INVALID path copies the bearer token into a loaned
    // buffer, so a mock returning null would fail that leg for a reason unrelated to the contract.
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final String PATH_NO_PROVIDER = "/no-provider";
    private static final String PATH_NO_TOKEN = "/no-token";
    private static final String PATH_BAD_TOKEN = "/bad-token";

    /** Captures the response so the three denials can be compared byte for byte. */
    private static final class CapturingExchange implements HttpExchange {
        private final HttpRequest request;
        private HttpResponse captured;

        private CapturingExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            this.captured = response;
        }
    }

    /** A provider that rejects every credential, which is the TOKEN_INVALID path. */
    private static final class RejectingProvider implements SecurityProvider {
        @Override
        public String providerId() {
            return "rejecting-test-provider";
        }

        @Override
        public String providerName() {
            return "Rejecting Test Provider";
        }

        @Override
        public AuthenticationResult authenticate(LoanedBuffer rawToken) {
            throw new SecurityAuthenticationException("JWT", "expired");
        }

        @Override
        public StorageContext systemStorageContext() {
            throw new UnsupportedOperationException("not reached — this provider never authenticates");
        }
    }

    private static HttpResponse dispatch(SecurityInterceptor interceptor,
                                         String path,
                                         List<HttpHeader> headers) {
        HttpRequest request =
                new HttpRequest(HttpMethod.GET, path, HttpVersion.HTTP_1_1, headers, null);
        CapturingExchange exchange = new CapturingExchange(request);
        new CommunityHttpRequestDispatcher(
                ALLOCATOR, interceptor, null, null,
                (method, requested) -> RouteRequirement.authenticated())
                .dispatch(request, exchange,
                        ex -> ex.respond(HttpResponse.noBody(HttpStatus.OK, request.version())));
        return exchange.captured;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("each denial names its own reason, and every response is the same 401")
    void reasonsDifferWhileResponsesDoNot() throws Exception {
        Map<String, RecordedEvent> byReason = new ConcurrentHashMap<>();
        CountDownLatch allThree = new CountDownLatch(3);
        List<HttpResponse> responses = new ArrayList<>();

        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(EVENT_NAME);
            stream.onEvent(EVENT_NAME, event -> {
                // Keyed on the reason, so a run that emits the same reason three times counts once
                // and the latch never reaches zero — the failure this test exists to catch.
                if (byReason.putIfAbsent(event.getString("dropReason"), event) == null) {
                    allThree.countDown();
                }
            });
            stream.startAsync();

            responses.add(dispatch(null, PATH_NO_PROVIDER, List.of()));
            responses.add(dispatch(new SecurityInterceptor(new RejectingProvider()),
                    PATH_NO_TOKEN, List.of()));
            responses.add(dispatch(new SecurityInterceptor(new RejectingProvider()),
                    PATH_BAD_TOKEN, List.of(new HttpHeader("authorization", "Bearer nonsense"))));

            assertThat(allThree.await(45, TimeUnit.SECONDS))
                    .as("three distinct reasons must reach the recording; saw %s", byReason.keySet())
                    .isTrue();
        }

        assertThat(byReason.keySet())
                .as("the two that were advertised and never emitted are NO_PROVIDER and TOKEN_MISSING")
                .containsExactlyInAnyOrder("NO_PROVIDER", "TOKEN_MISSING", "TOKEN_INVALID");

        assertThat(byReason.get("NO_PROVIDER").getString("errorCode"))
                .as("nothing to validate")
                .isEqualTo("EX-SEC-2001");
        assertThat(byReason.get("TOKEN_MISSING").getString("errorCode"))
                .as("nothing to validate")
                .isEqualTo("EX-SEC-2001");
        assertThat(byReason.get("TOKEN_INVALID").getString("errorCode"))
                .as("validation was attempted and failed — a different code on purpose")
                .isEqualTo("EX-SEC-2002");

        assertThat(responses).hasSize(3).allSatisfy(response -> {
            assertThat(response).as("every denial answers").isNotNull();
            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.body())
                    .as("a body differing by reason would be the probe oracle this avoids")
                    .isNull();
        });
        assertThat(responses.stream().map(HttpResponse::status).distinct())
                .as("the caller must not be able to tell the three apart")
                .hasSize(1);
    }
}
