/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default peer of the deferred client engine, against a counting provider rather than a socket.
 *
 * <p>{@code CommunityHttpSubsystem#providerBindings} publishes this engine before {@code start()}
 * builds a delegate, and a caller resolving the peer ahead of enrichment (ADR-074) reads
 * {@link HttpClientEngine#defaultAuthority()} from it. What these cases pin is where that answer
 * comes from, the configuration, whether or not a delegate exists and whatever the delegate says;
 * and that an unaddressed request sent through the engine reaches the delegate addressed to that
 * same default, while any other request reaches it unchanged.
 */
@DisplayName("DeferredHttpClientEngine: default authority")
class DeferredHttpClientEngineTest {

    private static final String CONFIGURED = "peer.internal:8443";

    private final AtomicInteger created = new AtomicInteger();
    private final AtomicReference<HttpRequest> delivered = new AtomicReference<>();
    private final HttpProvider provider = new CountingProvider(created, delivered);

    @Test
    @DisplayName("reports the configured default peer before start, without building a delegate")
    void reportsConfiguredDefaultBeforeStart() {
        DeferredHttpClientEngine engine = new DeferredHttpClientEngine(provider, clientConfig(CONFIGURED));

        assertThat(engine.defaultAuthority())
                .as("the engine is published before start, so the default must be answerable then")
                .isEqualTo(CONFIGURED);
        assertThat(created.get())
                .as("answering must not build the delegate early")
                .isZero();
    }

    @Test
    @DisplayName("reports no default peer when none is configured, so an unaddressed request stays refused")
    void reportsNullWhenNoDefaultIsConfigured() {
        DeferredHttpClientEngine engine = new DeferredHttpClientEngine(provider, clientConfig(null));

        assertThat(engine.defaultAuthority())
                .as("an engine with no configured default must not invent one")
                .isNull();
    }

    @Test
    @DisplayName("reports the configured default after start and after close, even when the delegate inherits null")
    void reportsConfiguredDefaultWhenDelegateDeclaresNone() {
        DeferredHttpClientEngine engine = new DeferredHttpClientEngine(provider, clientConfig(CONFIGURED));

        engine.start();
        assertThat(created.get()).isOne();
        assertThat(engine.defaultAuthority())
                .as("the configured peer, not the interface default the delegate inherits")
                .isEqualTo(CONFIGURED);

        engine.close();
        assertThat(engine.defaultAuthority()).isEqualTo(CONFIGURED);
    }

    @Test
    @DisplayName("sends an unaddressed request to the configured default peer, even when the delegate reads no default")
    void addressesAnUnaddressedRequestToTheConfiguredDefault() {
        try (DeferredHttpClientEngine engine = new DeferredHttpClientEngine(provider, clientConfig(CONFIGURED))) {
            engine.start();

            engine.send(get());

            assertThat(delivered.get().authority())
                    .as("the peer the engine reports as its default is the peer an unaddressed request reaches")
                    .isEqualTo(CONFIGURED);
        }
    }

    @Test
    @DisplayName("passes a request that names its peer to the delegate unchanged")
    void leavesAnAddressedRequestUnchanged() {
        HttpRequest addressed = get().withAuthority("named.internal:9443");
        try (DeferredHttpClientEngine engine = new DeferredHttpClientEngine(provider, clientConfig(CONFIGURED))) {
            engine.start();

            engine.send(addressed);

            assertThat(delivered.get())
                    .as("a request naming its peer overrides the default, so it must not be readdressed")
                    .isSameAs(addressed);
        }
    }

    @Test
    @DisplayName("passes an unaddressed request unchanged when no default is configured, so the delegate refuses it")
    void leavesAnUnaddressedRequestUnchangedWithoutADefault() {
        HttpRequest unaddressed = get();
        try (DeferredHttpClientEngine engine = new DeferredHttpClientEngine(provider, clientConfig(null))) {
            engine.start();

            engine.send(unaddressed);

            assertThat(delivered.get())
                    .as("with no default there is no peer to supply; the delegate's refusal must stay reachable")
                    .isSameAs(unaddressed);
        }
    }

    private static HttpRequest get() {
        return HttpRequest.noBody(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, List.of());
    }

    private static HttpConfig clientConfig(String defaultAuthority) {
        return new HttpConfig(
                HttpMode.CLIENT,
                null,
                -1,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                false,
                HttpVersion.HTTP_1_1,
                defaultAuthority,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
    }

    private record CountingProvider(AtomicInteger created, AtomicReference<HttpRequest> delivered)
            implements HttpProvider {

        @Override
        public HttpServerEngine createServerEngine(HttpConfig config) {
            throw new UnsupportedOperationException("client-only provider");
        }

        @Override
        public HttpClientEngine createClientEngine(HttpConfig config) {
            created.incrementAndGet();
            return new DefaultlessEngine(delivered);
        }

        @Override
        public String providerId() {
            return "counting";
        }

        @Override
        public String providerName() {
            return "counting";
        }
    }

    /**
     * A delegate that leaves {@link HttpClientEngine#defaultAuthority()} at the interface default and
     * records the request it is given.
     */
    private static final class DefaultlessEngine implements HttpClientEngine {

        private final AtomicReference<HttpRequest> delivered;
        private boolean running;

        DefaultlessEngine(AtomicReference<HttpRequest> delivered) {
            this.delivered = delivered;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            delivered.set(request);
            return HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_1_1);
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public String engineName() {
            return "defaultless";
        }

        @Override
        public void close() {
            running = false;
        }
    }
}
