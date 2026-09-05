/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.http.HttpResponseEncodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * Serializes a payload to JSON and wraps the result in an {@link HttpEncodedBody}
 * backed by a kernel-allocated {@link LoanedBuffer}.
 *
 * <p>Jackson streams straight into the network-registered off-heap buffer via
 * {@link SegmentSink} — no intermediate heap {@code byte[]} and no heap&rarr;off-heap
 * {@code MemorySegment.copy} per response (No-Waste-Compute; zero-copy transport contract).
 * The sink grows to a larger buffer if the initial estimate is exceeded.
 *
 * <p>Buffer ownership transfers to the returned encoded body; callers must
 * not close the buffer after calling {@link #encode}.
 *
 * @since 0.5
 */
final class JsonBodyEncoder implements HttpResponseBodyEncoder {

    private static final List<HttpHeader> JSON_HEADERS = List.of(new HttpHeader("content-type", "application/json"));

    /**
     * Initial off-heap buffer size (bytes) — a conservative default; responses larger than this grow
     * transparently via {@link SegmentSink} (one intra-buffer copy of the bytes written so far, still far
     * cheaper than the removed heap {@code byte[]} + full copy). The optimal value is workload-dependent;
     * tuning it from the real response-size distribution (or making it configurable) is a tracked follow-up.
     */
    private static final int INITIAL_BUFFER_BYTES = 4096;

    private final ObjectMapper mapper;

    /* default */ JsonBodyEncoder(ObjectMapper mapper) {
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
    public HttpEncodedBody encode(Object payload, HttpResponseEncodingContext context) {
        SegmentSink sink = new SegmentSink(
                context.allocator().allocateNetwork(INITIAL_BUFFER_BYTES), context.allocator());
        boolean committed = false;
        try {
            mapper.writeValue(sink, payload);
            sink.setSizeToWritten();
            HttpEncodedBody body = new HttpEncodedBody(JSON_HEADERS, sink.buffer());
            committed = true;
            return body;
        } catch (JacksonException e) {
            // Do not let a tools.jackson type cross out of the encoder (The Wall — ADR-006).
            throw new IllegalStateException("JSON serialization failed", e);
        } finally {
            if (!committed) {
                sink.discard();
            }
        }
    }
}
