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

import java.util.List;
import java.util.Objects;

/**
 * Community driver: Jackson 3 implementation of {@link HttpRequestBodyEncoder}.
 *
 * <p>Streams a non-null payload as JSON <em>straight into</em> a kernel-allocated
 * {@link LoanedBuffer} through {@link SegmentSink} — no intermediate heap {@code byte[]} and no
 * heap&rarr;off-heap {@code MemorySegment.copy} per request (No-Waste-Compute; mirrors the
 * server-side {@code JsonBodyEncoder}). The sink grows to a larger buffer if the initial estimate
 * is exceeded. The returned {@link HttpEncodedBody} carries the buffer plus a
 * {@code content-type: application/json} header (the encoder owns the content-type; the façade adds
 * {@code content-length} from the buffer size). Buffer ownership transfers to the returned body.
 *
 * <p>Jackson-specific exceptions are wrapped in {@link IllegalStateException} to keep driver-specific
 * types out of any SPI / Core surface (The Wall — ADR-006).
 *
 * @since 0.8.0
 */
public final class CommunityJsonRequestBodyEncoder implements HttpRequestBodyEncoder {

    private static final List<HttpHeader> JSON_HEADERS =
            List.of(new HttpHeader("content-type", "application/json"));

    /**
     * Initial off-heap buffer size (bytes) — a conservative default; larger request bodies grow
     * transparently via {@link SegmentSink}. Sizing is a tracked tuning follow-up (mirrors
     * {@code JsonBodyEncoder}).
     */
    private static final int INITIAL_BUFFER_BYTES = 4096;

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
    // CloseResource: the sink's buffer ownership transfers to the returned HttpEncodedBody on success,
    // and the finally releases it on every non-committed exit — no path leaks the off-heap loan.
    @SuppressWarnings("PMD.CloseResource")
    public HttpEncodedBody encode(Object payload, HttpRequestEncodingContext context) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(context, "context must not be null");
        SegmentSink sink = new SegmentSink(
                context.allocator().allocateNetwork(INITIAL_BUFFER_BYTES), context.allocator());
        boolean committed = false;
        try {
            mapper.writeValue(sink, payload);
            sink.setSizeToWritten();
            HttpEncodedBody body = new HttpEncodedBody(JSON_HEADERS, sink.buffer());
            committed = true;
            return body;
        } catch (JacksonException ex) {
            // Do not let a tools.jackson type cross out of the encoder (The Wall — ADR-006).
            throw new IllegalStateException(
                    "JSON serialization failed for payload type " + payload.getClass().getName(), ex);
        } finally {
            if (!committed) {
                sink.discard();
            }
        }
    }
}
