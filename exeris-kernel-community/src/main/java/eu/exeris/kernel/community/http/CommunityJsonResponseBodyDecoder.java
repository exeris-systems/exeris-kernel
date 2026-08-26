/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Community driver: Jackson 3 implementation of {@link HttpResponseBodyDecoder}.
 *
 * <p>Supports any non-null {@code targetType} when {@code contentType} is either
 * absent (server omitted the header — decoder is content-type-tolerant per
 * {@link HttpResponseBodyDecoder#supports(Class, String)}) or matches the JSON
 * family — {@code application/json} or any {@code application/*+json} structured
 * syntax suffix per RFC 6838 §4.2.8 ({@code application/vnd.exeris+json}, etc.).
 *
 * <p>Returns {@code null} when the response body is empty ({@code body.size() == 0})
 * — the façade decides whether {@code null} is acceptable for the call's static
 * response type. Jackson-specific exceptions are wrapped in
 * {@link IllegalStateException} to keep driver-specific types out of any SPI / Core
 * surface (mirrors the server-side {@code JsonBodyEncoder} pattern).
 *
 * <p>This decoder does NOT close, retain, or otherwise extend the lifetime of
 * the {@link LoanedBuffer}; ownership stays with the façade, which closes the
 * buffer in a {@code finally} after the call returns.
 *
 * @since 0.8.0
 */
public final class CommunityJsonResponseBodyDecoder implements HttpResponseBodyDecoder {

    private final ObjectMapper mapper;

    /**
     * Creates a Jackson-backed response body decoder.
     *
     * @param mapper application-owned {@link ObjectMapper}; never null
     */
    public CommunityJsonResponseBodyDecoder(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean supports(Class<?> targetType, String contentType) {
        return targetType != null && JsonBodyCodecs.isJsonCompatible(contentType);
    }

    @Override
    public Object decode(LoanedBuffer body, Class<?> targetType, HttpResponseDecodingContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return JsonBodyCodecs.readValue(mapper, body, targetType);
    }
}
