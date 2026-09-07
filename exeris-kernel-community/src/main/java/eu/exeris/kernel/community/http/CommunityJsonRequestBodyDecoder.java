/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpRequestBodyDecoder;
import eu.exeris.kernel.spi.http.HttpRequestDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Community driver: Jackson 3 implementation of {@link HttpRequestBodyDecoder}.
 *
 * <p>Supports any non-null {@code targetType} when {@code contentType} is either
 * absent (client omitted the header — decoder is content-type-tolerant per
 * {@link HttpRequestBodyDecoder#supports(Class, String)}) or matches the JSON
 * family — {@code application/json} or any {@code application/*+json} structured
 * syntax suffix per RFC 6838 §4.2.8 ({@code application/vnd.exeris+json}, etc.).
 *
 * <p>Returns {@code null} when the request body is empty ({@code body.size() == 0})
 * — the caller (the generated handler) decides whether {@code null} is acceptable
 * for the call's static request type. A Jackson decode failure is wrapped in
 * {@link eu.exeris.kernel.spi.exceptions.http.RequestBodyDecodeException} — an SPI-owned type, so
 * no driver-specific type reaches any SPI / Core surface, and a handler can answer
 * {@code 400 Bad Request} without distinguishing it from a missing decoder by message text
 * (ADR-036 §2).
 *
 * <p>No-Waste-Compute (ADR-036 §3): the segment is consumed with a single
 * {@code byte[]} copy straight into {@code mapper.readValue(bytes, targetType)} —
 * no intermediate {@code String} allocation on the server ingress hot path.
 *
 * <p>This decoder does NOT close, retain, or otherwise extend the lifetime of
 * the {@link LoanedBuffer}; ownership stays with the caller, which closes the
 * buffer when the exchange ends.
 *
 * @since 0.8
 */
public final class CommunityJsonRequestBodyDecoder implements HttpRequestBodyDecoder {

    private final ObjectMapper mapper;

    /**
     * Creates a Jackson-backed request body decoder.
     *
     * @param mapper application-owned {@link ObjectMapper}; never null
     */
    public CommunityJsonRequestBodyDecoder(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean supports(Class<?> targetType, String contentType) {
        return targetType != null && JsonBodyCodecs.isJsonCompatible(contentType);
    }

    /**
     * {@inheritDoc}
     *
     * @throws eu.exeris.kernel.spi.exceptions.http.RequestBodyDecodeException ({@code EX-HTTP-4013})
     *     if the body does not decode into {@code targetType}
     */
    @Override
    public Object decode(LoanedBuffer body, Class<?> targetType, HttpRequestDecodingContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return JsonBodyCodecs.readRequestValue(mapper, body, targetType);
    }
}
