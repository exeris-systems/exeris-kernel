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
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.http.HttpRequestEncodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Objects;

/**
 * Community driver: Jackson 3 implementation of {@link HttpRequestBodyEncoder}.
 *
 * <p>Serialises any non-null payload to JSON via {@link ObjectMapper#writeValueAsBytes(Object)},
 * copies the bytes into a kernel-allocated {@link LoanedBuffer}, and returns an
 * {@link HttpEncodedBody} carrying the buffer plus a {@code content-type:
 * application/json} header (the encoder owns the content-type header; the façade
 * adds {@code content-length} from the resulting buffer size).
 *
 * <p>Jackson-specific exceptions are wrapped in {@link IllegalStateException} to
 * keep driver-specific types out of any SPI / Core surface (mirrors the server-side
 * {@code JsonBodyEncoder} pattern).
 *
 * @since 0.8.0
 */
public final class CommunityJsonRequestBodyEncoder implements HttpRequestBodyEncoder {

    private static final List<HttpHeader> JSON_HEADERS =
            List.of(new HttpHeader("content-type", "application/json"));

    private final ObjectMapper mapper;

    /**
     * Creates a Jackson-backed request body encoder.
     *
     * @param mapper application-owned {@link ObjectMapper}; never null
     */
    public CommunityJsonRequestBodyEncoder(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean supports(Class<?> payloadType) {
        return payloadType != null;
    }

    @Override
    public HttpEncodedBody encode(Object payload, HttpRequestEncodingContext context) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(context, "context must not be null");
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "JSON serialization failed for payload type " + payload.getClass().getName(), ex);
        }
        LoanedBuffer buf = context.allocator().allocateNetwork(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buf.segment(), 0, bytes.length);
        buf.setSize(bytes.length);
        return new HttpEncodedBody(JSON_HEADERS, buf);
    }
}
