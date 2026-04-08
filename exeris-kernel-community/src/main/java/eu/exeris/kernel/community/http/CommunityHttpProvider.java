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
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

public final class CommunityHttpProvider implements HttpProvider {

    private static final String PROVIDER_ID = "community-http";
    private static final String PROVIDER_NAME = "ExerisCommunity/NativeTcpHttp";
    private static final HttpResponseBodyEncoderRegistry ENCODER_REGISTRY = buildDefaultRegistry();

    private static HttpResponseBodyEncoderRegistry buildDefaultRegistry() {
        JsonBodyEncoder encoder = new JsonBodyEncoder(new ObjectMapper());
        return payloadType -> encoder.supports(payloadType) ? encoder : null;
    }

    @Override
    public HttpResponseBodyEncoderRegistry responseBodyEncoderRegistry() {
        return ENCODER_REGISTRY;
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