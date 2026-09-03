/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

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

    @Override
    public HttpClientEngine createClientEngine(HttpConfig config) {
        return new CommunityHttpClientEngine(Objects.requireNonNull(config, "config must not be null"));
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