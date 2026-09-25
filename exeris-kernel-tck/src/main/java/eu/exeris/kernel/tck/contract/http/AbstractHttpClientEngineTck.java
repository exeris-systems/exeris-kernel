/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Abstract base for {@link HttpClientEngine} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code engineName()} is non-null and non-blank, stable across calls</li>
 *   <li>Engine is NOT running after creation (before {@code start()})</li>
 *   <li>{@code start()} transitions engine to RUNNING state</li>
 *   <li>{@code send(null)} throws {@link NullPointerException}</li>
 *   <li>{@code close()} is idempotent — multiple calls do not throw</li>
 *   <li>{@code start()} after {@code close()} throws {@link IllegalStateException}</li>
 *   <li>{@code send} of a request naming no peer authority is refused with
 *       {@link IllegalStateException} when the engine carries no configured default authority</li>
 *   <li>{@code send} of a request whose authority carries no explicit port is refused with
 *       {@link IllegalStateException}</li>
 *   <li>{@code send} neither closes nor retains {@code request.body()}: after a refused send, and
 *       after a send that fails once it has started dialling an unreachable peer, the body is still
 *       alive, holds its one caller reference, and was never closed; the caller releases it</li>
 * </ul>
 *
 * @since 0.5
 */
public abstract class AbstractHttpClientEngineTck {

    /**
     * Creates the contract; subclasses supply the engine under test via {@link #createEngine(HttpConfig)}.
     */
    public AbstractHttpClientEngineTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates an {@link HttpClientEngine} under test.
     *
     * @param config client configuration
     * @return a fresh engine in CREATED state; never {@code null}
     */
    protected abstract HttpClientEngine createEngine(HttpConfig config);

    /**
     * Returns a valid client {@link HttpConfig}.
     *
     * @return a valid client config; never {@code null}
     */
    protected HttpConfig testConfig() {
        return HttpConfig.defaultClient();
    }

    private HttpClientEngine engine;

    @BeforeEach
    final void createTestEngine() {
        engine = createEngine(testConfig());
    }

    @AfterEach
    final void closeTestEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Nested
    @DisplayName("Engine identity")
    class Identity {

        @Test
        @DisplayName("engineName() is non-blank")
        void engineNameNonBlank() {
            assertThat(engine.engineName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("engineName() is stable across calls")
        void engineNameStable() {
            assertThat(engine.engineName()).isEqualTo(engine.engineName());
        }
    }

    @Nested
    @DisplayName("Lifecycle: CREATED state")
    class CreatedState {

        @Test
        @DisplayName("Engine is not running after creation")
        void notRunningAfterCreation() {
            assertThat(engine.isRunning()).isFalse();
        }

        @Test
        @DisplayName("send(null) throws NullPointerException")
        void sendNullThrows() {
            assertThatThrownBy(() -> engine.send(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Lifecycle: START")
    class StartState {

        @Test
        @DisplayName("Engine is running after start()")
        void runningAfterStart() {
            engine.start();
            assertThat(engine.isRunning()).isTrue();
        }
    }

    @Nested
    @DisplayName("Peer addressing (ADR-074)")
    class PeerAddressing {

        @Test
        @DisplayName("An unaddressed request with no configured default peer is refused, not guessed")
        void unaddressedRequestWithNoDefaultIsRefused() {
            engine.start();

            // HttpConfig.defaultClient() carries no default authority, and this request names none.
            // The engine must refuse rather than fall back to a host it was never given: before
            // ADR-074 the fallback was HttpConfig.bindHost — the SERVER/DUAL *listener* address —
            // so an unaddressed request was silently sent to whatever the local server bound.
            assertThatThrownBy(() -> engine.send(HttpRequest.noBody(
                    HttpMethod.GET, "/health", HttpVersion.HTTP_1_1, List.of())))
                    .as("a request naming no peer, against an engine configured with none, must fail")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("authority");
        }

        @Test
        @DisplayName("An authority without an explicit port is refused")
        void authorityWithoutPortIsRefused() {
            engine.start();

            // There is no scheme on HttpRequest, so there is no basis for defaulting to 80 or 443 —
            // and defaulting to the listener port is what this ADR removed. Refusing names the fix.
            assertThatThrownBy(() -> engine.send(HttpRequest
                    .noBody(HttpMethod.GET, "/health", HttpVersion.HTTP_1_1, List.of())
                    .withAuthority("service.internal")))
                    .as("an authority carrying no port must be refused rather than assigned one")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Request body ownership")
    class RequestBodyOwnership {

        private static final byte[] PAYLOAD =
                "{\"contract\":\"the caller releases the request body\"}".getBytes(StandardCharsets.US_ASCII);

        @Test
        @DisplayName("A send refused before start() neither closes nor retains the request body")
        void sendRefusedBeforeStartLeavesTheBodyToTheCaller() {
            CountingRequestBody body = CountingRequestBody.of(PAYLOAD);
            try {
                HttpRequest request = postWithBody(body).withAuthority("127.0.0.1:9");

                assertThatThrownBy(() -> engine.send(request))
                        .as("an engine that has not been started must refuse the send")
                        .isInstanceOf(IllegalStateException.class);

                body.assertStillOwnedByCaller(PAYLOAD, "throws");
            } finally {
                body.close();
            }
        }

        @Test
        @DisplayName("A send refused for a port-less authority neither closes nor retains the request body")
        void sendRefusedForPortlessAuthorityLeavesTheBodyToTheCaller() {
            engine.start();
            CountingRequestBody body = CountingRequestBody.of(PAYLOAD);
            try {
                HttpRequest request = postWithBody(body).withAuthority("service.internal");

                assertThatThrownBy(() -> engine.send(request))
                        .as("an authority carrying no port must be refused")
                        .isInstanceOf(IllegalStateException.class);

                body.assertStillOwnedByCaller(PAYLOAD, "throws");
            } finally {
                body.close();
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("A send that fails dialling an unreachable peer neither closes nor retains the request body")
        void sendFailingOnUnreachablePeerLeavesTheBodyToTheCaller() {
            engine.start();
            int port = unusedLoopbackPort();
            CountingRequestBody body = CountingRequestBody.of(PAYLOAD);
            try {
                HttpRequest request = postWithBody(body).withAuthority("127.0.0.1:" + port);

                Throwable failure = catchThrowable(() -> {
                    HttpResponse response = engine.send(request);
                    if (response.body() != null) {
                        response.body().close();
                    }
                });

                body.assertStillOwnedByCaller(PAYLOAD, failure == null ? "returns" : "throws");
                // The address and port pass every refusal check, so an engine that dials fails here
                // in its I/O path. An engine that answers without dialling never reaches that path,
                // and the case reports the exception path as not exercised rather than as passed.
                assumeTrue(failure != null,
                        "the engine answered an unreachable peer without dialling it; the exception path is not exercised");
                assertThat(failure)
                        .as("a send to a peer nobody listens on must fail with an unchecked exception")
                        .isInstanceOf(RuntimeException.class);
            } finally {
                body.close();
            }
        }

        private HttpRequest postWithBody(CountingRequestBody body) {
            HttpRequest request = new HttpRequest(
                    HttpMethod.POST,
                    "/ownership",
                    HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Content-Type", "application/json")),
                    body);
            assertThat(request.hasBody())
                    .as("the request must carry the body, or the case asserts nothing about it")
                    .isTrue();
            assertThat(request.body().size()).isEqualTo(PAYLOAD.length);
            return request;
        }
    }

    @Nested
    @DisplayName("Lifecycle: CLOSE (idempotency)")
    class Close {

        @Test
        @DisplayName("close() is idempotent — second call does not throw")
        void closeIdempotent() {
            assertThatCode(() -> {
                engine.close();
                engine.close();
            })
                    .as("close() must be idempotent across repeated invocations")
                    .doesNotThrowAnyException();
            assertThat(engine.isRunning())
                    .as("Closed client engine must not remain in running state")
                    .isFalse();
        }

        @Test
        @DisplayName("start() after close() throws IllegalStateException")
        void startAfterCloseThrows() {
            engine.close();
            assertThatThrownBy(() -> engine.start())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Returns a loopback port that nothing listens on, by binding an ephemeral port and releasing it.
     *
     * <p>Another process may claim the port between the release and the dial; the window is the same
     * one every loopback contract that allocates its own port accepts.
     */
    private static int unusedLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate a free TCP port", ex);
        }
    }
}
