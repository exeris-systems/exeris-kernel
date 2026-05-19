/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.lang.foreign.MemorySegment;
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

    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_PREFIX = "application/";
    private static final String JSON_SUFFIX = "+json";
    private static final long EMPTY_SIZE = 0L;

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
        if (targetType == null) {
            return false;
        }
        if (contentType == null || contentType.isEmpty()) {
            // Server omitted content-type — tolerate per decoder contract.
            return true;
        }
        // Strip parameters (e.g. "application/json; charset=utf-8").
        int semi = contentType.indexOf(';');
        String base = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
        // application/json exact match OR application/*+json structured syntax suffix (RFC 6838 §4.2.8).
        return APPLICATION_JSON.equalsIgnoreCase(base)
                || (base.regionMatches(true, 0, APPLICATION_PREFIX, 0, APPLICATION_PREFIX.length())
                        && base.regionMatches(true, base.length() - JSON_SUFFIX.length(),
                                JSON_SUFFIX, 0, JSON_SUFFIX.length()));
    }

    @Override
    public Object decode(LoanedBuffer body, Class<?> targetType, HttpResponseDecodingContext context) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(context, "context must not be null");
        long size = body.size();
        if (size == EMPTY_SIZE) {
            return null;
        }
        byte[] bytes = new byte[Math.toIntExact(size)];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(bytes), 0L, size);
        try {
            return mapper.readValue(bytes, targetType);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "JSON deserialization failed for target type " + targetType.getName(), ex);
        }
    }
}
