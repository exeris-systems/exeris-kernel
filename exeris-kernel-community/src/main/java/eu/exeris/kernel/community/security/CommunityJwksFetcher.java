/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Fetches the JWKS document as text, over a client engine it builds for the endpoint and owns.
 *
 * <p>The engine is built with the endpoint scheme's outbound TLS requirement
 * ({@link eu.exeris.kernel.community.http.CommunityEndpointScheme#outboundTls()}), never with what
 * happens to be bound where the fetcher is built: {@code https} is TLS that verifies the server
 * against the endpoint host, or no fetcher; {@code http} is plaintext by request.
 *
 * @since 0.12
 */
final class CommunityJwksFetcher implements Supplier<String>, AutoCloseable {

    /**
     * Positional {@link HttpConfig} arguments no field of which reaches a {@code CLIENT}-mode engine's
     * dial path, named rather than read as tuning. As in the S3 client: {@code maxConnections} bounds a
     * listener backlog and accept-time reservation, which a client carrier never has, and no carrier
     * reads {@code idleTimeoutMillis}.
     */
    private static final int MAX_CONNECTIONS = 4;
    private static final long IDLE_TIMEOUT_MS = 30_000L;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_SIZE = 8_192;

    /**
     * Ceiling on the JWKS response. A key set is a few kilobytes; the ceiling bounds what the
     * endpoint can make the client allocate on every refresh.
     */
    private static final long MAX_JWKS_BYTES = 1L << 20;

    private static final int HTTP_2XX_LOWER = 200;
    private static final int HTTP_2XX_UPPER = 300;
    private static final long EMPTY_SIZE = 0L;
    private static final List<HttpHeader> ACCEPT_JSON = List.of(new HttpHeader("Accept", "application/json"));

    private final HttpClientEngine engine;
    private final String requestTarget;

    private CommunityJwksFetcher(HttpClientEngine engine, String requestTarget) {
        this.engine = engine;
        this.requestTarget = requestTarget;
    }

    /**
     * Builds the engine for {@code endpoint} with {@code engineFactory} and starts it. The fetcher owns
     * the engine from then on; an engine whose {@code start()} throws is closed before the failure
     * propagates.
     *
     * @param endpoint      where the JWKS document is served
     * @param engineFactory builds the engine from its configuration and outbound TLS requirement
     * @return a fetcher over the started engine
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException ({@code EX-NET-4004}) when
     *         the endpoint is {@code https} and no engine that verifies its server can be built
     */
    /* default */ static CommunityJwksFetcher open(
            CommunityJwksEndpoint endpoint,
            BiFunction<HttpConfig, CommunityOutboundTls, HttpClientEngine> engineFactory) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(engineFactory, "engineFactory must not be null");
        HttpClientEngine engine = startOrClose(engineFactory.apply(new HttpConfig(
                HttpMode.CLIENT,
                endpoint.host(),
                endpoint.port(),
                MAX_CONNECTIONS,
                IDLE_TIMEOUT_MS,
                MAX_HEADER_COUNT,
                MAX_HEADER_SIZE,
                MAX_JWKS_BYTES,
                false,
                HttpVersion.HTTP_1_1,
                // ADR-074: every request dials this authority, and a TLS carrier verifies the server
                // against its host. bindHost and port above are listen fields a client never dials.
                endpoint.dialAuthority(),
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE), endpoint.scheme().outboundTls()));
        return new CommunityJwksFetcher(engine, endpoint.requestTarget());
    }

    /**
     * One {@code GET}; the body of a {@code 2xx} response as UTF-8 text, or {@code null} when it is
     * empty. Any other status throws, which the key-set source reports as a failed fetch.
     *
     * @return the JWKS document, or {@code null} for an empty body
     * @throws IllegalStateException if the endpoint answers with a status outside {@code 2xx}
     */
    @Override
    public String get() {
        HttpResponse response = engine.send(
                HttpRequest.noBody(HttpMethod.GET, requestTarget, HttpVersion.HTTP_1_1, ACCEPT_JSON));
        try (LoanedBuffer body = response.body()) {
            int status = response.status().code();
            if (status < HTTP_2XX_LOWER || status >= HTTP_2XX_UPPER) {
                throw new IllegalStateException("JWKS endpoint answered HTTP " + status);
            }
            return body == null ? null : text(body);
        }
    }

    /** Closes the engine. */
    @Override
    public void close() {
        engine.close();
    }

    private static String text(LoanedBuffer body) {
        long size = body.size();
        if (size == EMPTY_SIZE) {
            return null;
        }
        byte[] bytes = new byte[Math.toIntExact(size)];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(bytes), 0L, size);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // AvoidCatchingGenericException: the engine is closed on any start failure, then the failure
    // rethrown; a failed close is attached to it, never allowed to replace it.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static HttpClientEngine startOrClose(HttpClientEngine engine) {
        try {
            engine.start();
            return engine;
        } catch (RuntimeException | Error startFailure) {
            try {
                engine.close();
            } catch (RuntimeException | Error closeFailure) {
                startFailure.addSuppressed(closeFailure);
            }
            throw startFailure;
        }
    }
}
