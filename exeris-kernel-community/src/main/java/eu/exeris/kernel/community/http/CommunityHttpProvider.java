/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.community.json.CommunityJsonMappers;
import eu.exeris.kernel.community.json.JsonMapperScope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Community: the {@code ServiceLoader}-discovered {@link HttpProvider} that builds the native TCP
 * HTTP server engine (HTTP/1.1, plus HTTP/2 via h2c/prior-knowledge upgrade) and an HTTP/1.x-only
 * client engine, and the default JSON body-codec registries.
 *
 * <p>Registers at {@link #priority()} {@code 0}, the Community-tier value in the convention
 * {@link HttpProvider#priority()} documents; an Enterprise provider on the classpath at a higher
 * priority is selected instead. Every returned encoder and decoder registry sources its Jackson
 * mapper per codec quadrant through the {@link CommunityJsonMappers} customization seam; with no
 * {@code JsonMapperCustomizer} registered, each mapper is the plain Jackson default.
 */
// TooManyMethods: SPI contract surface. Every public method but the two-argument createClientEngine
// implements HttpProvider, and that overload is the Community-internal outbound TLS requirement.
@SuppressWarnings("PMD.TooManyMethods")
public final class CommunityHttpProvider implements HttpProvider {

    private static final String PROVIDER_ID = "community-http";
    private static final String PROVIDER_NAME = "ExerisCommunity/NativeTcpHttp";

    // Each JSON codec sources its Jackson mapper per-scope through the ADR-052 customization seam.
    // With no JsonMapperCustomizer registered, every scope yields a bare default mapper (unchanged).
    private static final HttpResponseBodyEncoderRegistry ENCODER_REGISTRY = buildDefaultRegistry();
    private static final HttpRequestBodyEncoderRegistry REQUEST_BODY_ENCODER_REGISTRY =
            HttpRequestBodyEncoderRegistry.of(List.of(new CommunityJsonRequestBodyEncoder(
                    CommunityJsonMappers.forScope(JsonMapperScope.HTTP_REQUEST_ENCODE))));
    private static final HttpResponseBodyDecoderRegistry RESPONSE_BODY_DECODER_REGISTRY =
            HttpResponseBodyDecoderRegistry.of(List.of(new CommunityJsonResponseBodyDecoder(
                    CommunityJsonMappers.forScope(JsonMapperScope.HTTP_RESPONSE_DECODE))));
    private static final HttpRequestBodyDecoderRegistry REQUEST_BODY_DECODER_REGISTRY =
            HttpRequestBodyDecoderRegistry.of(List.of(new CommunityJsonRequestBodyDecoder(
                    CommunityJsonMappers.forScope(JsonMapperScope.HTTP_REQUEST_DECODE))));

    /**
     * Constructs the provider that {@link java.util.ServiceLoader} instantiates to resolve the
     * Community {@link HttpProvider}, per this module's registration under
     * {@code META-INF/services/eu.exeris.kernel.spi.http.HttpProvider}.
     */
    public CommunityHttpProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    private static HttpResponseBodyEncoderRegistry buildDefaultRegistry() {
        JsonBodyEncoder encoder =
                new JsonBodyEncoder(CommunityJsonMappers.forScope(JsonMapperScope.HTTP_RESPONSE_ENCODE));
        return payloadType -> encoder.supports(payloadType) ? encoder : null;
    }

    @Override
    public HttpResponseBodyEncoderRegistry responseBodyEncoderRegistry() {
        return ENCODER_REGISTRY;
    }

    @Override
    public Optional<HttpRequestBodyEncoderRegistry> requestBodyEncoderRegistry() {
        return Optional.of(REQUEST_BODY_ENCODER_REGISTRY);
    }

    @Override
    public Optional<HttpResponseBodyDecoderRegistry> responseBodyDecoderRegistry() {
        return Optional.of(RESPONSE_BODY_DECODER_REGISTRY);
    }

    @Override
    public Optional<HttpRequestBodyDecoderRegistry> requestBodyDecoderRegistry() {
        return Optional.of(REQUEST_BODY_DECODER_REGISTRY);
    }

    @Override
    public HttpServerEngine createServerEngine(HttpConfig config) {
        return new CommunityHttpServerEngine(
                Objects.requireNonNull(config, "config must not be null"), ENCODER_REGISTRY);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link #createClientEngine(HttpConfig, CommunityOutboundTls)} with
     * {@link CommunityOutboundTls#AMBIENT}: the engine's transport decides its outbound TLS from what
     * is bound where the engine is built.
     */
    @Override
    public HttpClientEngine createClientEngine(HttpConfig config) {
        return createClientEngine(config, CommunityOutboundTls.AMBIENT);
    }

    /**
     * Builds a client engine whose transport holds its outbound connections to {@code outboundTls}.
     *
     * <p>Community-internal, and not an {@link HttpProvider} method: for an owner whose engine dials
     * peers of one known scheme, such as the S3 blob client. {@link CommunityOutboundTls#PLAINTEXT}
     * dials plaintext whatever crypto provider is bound; {@link CommunityOutboundTls#VERIFIED} dials
     * TLS that verifies the server, or builds no engine.
     *
     * @param config      the engine configuration
     * @param outboundTls what the owner requires of the engine's outbound connections
     * @return a new, unstarted client engine
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException ({@code EX-NET-4004}) when
     *         the engine's transport cannot be built, including a {@code VERIFIED} requirement that
     *         cannot be met: {@code exeris.transport.tls=false}, no crypto provider bound, or a bound
     *         provider that cannot verify an outbound peer
     * @since 0.12
     */
    public HttpClientEngine createClientEngine(HttpConfig config, CommunityOutboundTls outboundTls) {
        return new CommunityHttpClientEngine(Objects.requireNonNull(config, "config must not be null"),
                Objects.requireNonNull(outboundTls, "outboundTls must not be null"));
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
    }
}