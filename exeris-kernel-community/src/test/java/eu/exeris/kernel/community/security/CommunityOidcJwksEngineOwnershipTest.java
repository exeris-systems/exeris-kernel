/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What {@link CommunityOidcIdentityProvider#overJwksEndpoint} asks of the engine it builds, and when it
 * closes it, against an engine that records {@code start()} and {@code close()} and never dials.
 */
@DisplayName("CommunityOidcIdentityProvider — the JWKS engine it builds and owns")
class CommunityOidcJwksEngineOwnershipTest {

    private static final URI HTTPS = URI.create("https://idp.example.com/realms/exeris/certs?v=2");
    private static final URI HTTP = URI.create("http://keycloak.internal:8080/realms/exeris/certs");

    @Test
    @DisplayName("an https URI builds a VERIFIED engine that dials the URI's authority")
    void httpsBuildsAVerifiedEngine() {
        AtomicReference<HttpConfig> config = new AtomicReference<>();
        AtomicReference<CommunityOutboundTls> outboundTls = new AtomicReference<>();
        RecordingEngine engine = new RecordingEngine(null, null);

        try (CommunityOidcIdentityProvider ignored = build(HTTPS, (built, tls) -> {
            config.set(built);
            outboundTls.set(tls);
            return engine;
        })) {
            assertThat(outboundTls.get()).isEqualTo(CommunityOutboundTls.VERIFIED);
            assertThat(config.get().defaultAuthority()).isEqualTo("idp.example.com:443");
        }
    }

    @Test
    @DisplayName("an http URI builds a PLAINTEXT engine, never AMBIENT")
    void httpBuildsAPlaintextEngine() {
        AtomicReference<HttpConfig> config = new AtomicReference<>();
        AtomicReference<CommunityOutboundTls> outboundTls = new AtomicReference<>();
        RecordingEngine engine = new RecordingEngine(null, null);

        try (CommunityOidcIdentityProvider ignored = build(HTTP, (built, tls) -> {
            config.set(built);
            outboundTls.set(tls);
            return engine;
        })) {
            assertThat(outboundTls.get()).isEqualTo(CommunityOutboundTls.PLAINTEXT);
            assertThat(config.get().defaultAuthority()).isEqualTo("keycloak.internal:8080");
        }
    }

    @Test
    @DisplayName("the provider owns a started engine until it is closed, and a second close is harmless")
    void startedEngineClosesWithTheProvider() {
        RecordingEngine engine = new RecordingEngine(null, null);

        CommunityOidcIdentityProvider provider = build(HTTPS, (config, tls) -> engine);
        assertThat(engine.starts).hasValue(1);
        assertThat(engine.closes).as("the provider owns a started engine").hasValue(0);

        provider.close();
        provider.close();
        assertThat(engine.closes).as("closed with the provider").hasPositiveValue();
    }

    @Test
    @DisplayName("a derived provider shares the engine; closing it closes the engine")
    void derivedProviderSharesTheEngine() {
        RecordingEngine engine = new RecordingEngine(null, null);

        CommunityOidcIdentityProvider provider = build(HTTPS, (config, tls) -> engine);
        CommunityOidcIdentityProvider derived = provider.enforcingSharedScope().withClaimsMapper(
                new CommunityClaimsMapper());

        derived.close();
        assertThat(engine.closes).hasValue(1);
    }

    @Test
    @DisplayName("an engine whose start throws is closed, and no provider is returned")
    void engineWhoseStartThrowsIsClosed() {
        IllegalStateException startFailure = new IllegalStateException("start refused");
        IllegalStateException closeFailure = new IllegalStateException("close refused");
        RecordingEngine engine = new RecordingEngine(startFailure, closeFailure);

        Throwable thrown = catchThrowable(() -> build(HTTPS, (config, tls) -> engine));

        assertThat(thrown).isSameAs(startFailure);
        assertThat(thrown.getSuppressed()).containsExactly(closeFailure);
        assertThat(engine.closes).hasValue(1);
    }

    @Test
    @DisplayName("an assembly failure after the engine started closes it before propagating")
    void assemblyFailureClosesTheEngine() {
        RecordingEngine engine = new RecordingEngine(null, null);

        Throwable thrown = catchThrowable(() -> CommunityOidcIdentityProvider.overJwksEndpoint(
                HTTPS, Map.of(), null, Clock.systemUTC(),
                TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE, (config, tls) -> engine));

        assertThat(thrown).isInstanceOf(NullPointerException.class);
        assertThat(engine.starts).hasValue(1);
        assertThat(engine.closes).as("no provider exists to close it").hasValue(1);
    }

    @Test
    @DisplayName("a URI that is not http or https builds no engine")
    void unusableUriBuildsNoEngine() {
        AtomicInteger built = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> build(URI.create("ftp://idp.example.com/certs"), (config, tls) -> {
            built.incrementAndGet();
            return new RecordingEngine(null, null);
        }));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwksUri must use the http or https scheme");
        assertThat(built).hasValue(0);
    }

    private static CommunityOidcIdentityProvider build(
            URI uri, BiFunction<HttpConfig, CommunityOutboundTls, HttpClientEngine> factory) {
        return CommunityOidcIdentityProvider.overJwksEndpoint(uri, Map.of(), KeyRotationPolicy.defaults(),
                Clock.systemUTC(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE, factory);
    }

    /** An engine that counts {@code start()} and {@code close()}, and throws what it is given. */
    private static final class RecordingEngine implements HttpClientEngine {

        private final RuntimeException startFailure;
        private final RuntimeException closeFailure;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        RecordingEngine(RuntimeException startFailure, RuntimeException closeFailure) {
            this.startFailure = startFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void start() {
            starts.incrementAndGet();
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            throw new UnsupportedOperationException("no request is sent in these cases");
        }

        @Override
        public boolean isRunning() {
            return starts.get() > 0 && closes.get() == 0;
        }

        @Override
        public String engineName() {
            return "RecordingEngine";
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
