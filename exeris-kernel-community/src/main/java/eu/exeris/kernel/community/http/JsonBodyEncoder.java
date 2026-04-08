/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.http.HttpResponseEncodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Objects;

/**
 * Serializes a payload to JSON and wraps the result in an {@link HttpEncodedBody}
 * backed by a kernel-allocated {@link LoanedBuffer}.
 *
 * <p>Buffer ownership transfers to the returned encoded body; callers must
 * not close the buffer after calling {@link #encode}.
 *
 * @since 0.5.0
 */
final class JsonBodyEncoder implements HttpResponseBodyEncoder {

    private static final List<HttpHeader> JSON_HEADERS = List.of(new HttpHeader("content-type", "application/json"));

    private final ObjectMapper mapper;

    /* default */ JsonBodyEncoder(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean supports(Class<?> payloadType) {
        return payloadType != null;
    }

    @Override
    public HttpEncodedBody encode(Object payload, HttpResponseEncodingContext context) {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }

        LoanedBuffer buf = context.allocator().allocateNetwork(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buf.segment(), 0, bytes.length);
        buf.setSize(bytes.length);
        return new HttpEncodedBody(JSON_HEADERS, buf);
    }
}
