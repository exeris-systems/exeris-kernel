/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CommunityHttpProvider implements HttpProvider {

    private static final String PROVIDER_ID = "community-http";
    private static final String PROVIDER_NAME = "ExerisCommunity/NativeTcpHttp";
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();
    private static final HttpResponseBodyEncoderRegistry ENCODER_REGISTRY = buildDefaultRegistry();
    private static final HttpRequestBodyEncoderRegistry REQUEST_BODY_ENCODER_REGISTRY =
            HttpRequestBodyEncoderRegistry.of(List.of(new CommunityJsonRequestBodyEncoder(DEFAULT_MAPPER)));
    private static final HttpResponseBodyDecoderRegistry RESPONSE_BODY_DECODER_REGISTRY =
            HttpResponseBodyDecoderRegistry.of(List.of(new CommunityJsonResponseBodyDecoder(DEFAULT_MAPPER)));

    private static HttpResponseBodyEncoderRegistry buildDefaultRegistry() {
        JsonBodyEncoder encoder = new JsonBodyEncoder(DEFAULT_MAPPER);
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