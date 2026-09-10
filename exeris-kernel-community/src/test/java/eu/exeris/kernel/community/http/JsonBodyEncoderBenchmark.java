/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponseEncodingContext;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.perf.AbstractExerisBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * JMH Benchmark: JSON response-body encoding, streaming vs. materialize-and-copy.
 *
 * <h2>Why this exists</h2>
 * <p>The response-encode hot path had no JMH coverage — the only allocation/CPU evidence came from
 * whole-stack perfbox runs, which cannot isolate the encoder. This benchmark measures the encode step
 * directly, comparing the three strategies side by side in one run via {@link #strategy}:
 * <ul>
 *   <li>{@code STREAMING} — {@link JsonBodyEncoder} writing straight into the loaned off-heap segment
 *       through {@link SegmentSink} (0.10.2+).</li>
 *   <li>{@code STREAMING_REUSED_WRITER} — streaming through a pre-built, reused {@link ObjectWriter}; a
 *       documented <em>negative result</em> (allocation-neutral vs {@code STREAMING}) — writer reuse is
 *       not a lever on the residual. See {@link #encodeStreamingReusedWriter(Object)}.</li>
 *   <li>{@code MATERIALIZE_AND_COPY} — the pre-0.10.2 path: {@code ObjectMapper.writeValueAsBytes}
 *       to a heap {@code byte[]}, then {@link MemorySegment#copy} into the loaned buffer. Kept here
 *       only as the A/B baseline.</li>
 * </ul>
 *
 * <h2>Metric of record</h2>
 * <p>Run with the GC profiler ({@code -prof gc}) — {@code gc.alloc.rate.norm} (bytes allocated per op)
 * is the primary signal: streaming should drop the per-op heap {@code byte[]} entirely. In CI the
 * benchmarks job additionally records JFR ({@code settings=default}), from which the same per-op
 * allocation is derivable. Throughput ({@code ops/s}) is the secondary signal — the removed copy pass.
 *
 * <p>Payload is a list of small records approximating a benchmark {@code /users} response (a few KB of
 * JSON), so the streaming path exercises {@link SegmentSink}'s grow once past the initial estimate.
 */
public class JsonBodyEncoderBenchmark extends AbstractExerisBenchmark {

    /** Encoding strategy under measurement; JMH runs one trial per value. */
    @Param({"STREAMING", "STREAMING_REUSED_WRITER", "MATERIALIZE_AND_COPY"})
    public Strategy strategy;

    private static final List<HttpHeader> JSON_HEADERS =
            List.of(new HttpHeader("content-type", "application/json"));

    private ObjectMapper mapper;
    private ObjectWriter writer;
    private MemoryAllocator allocator;
    private HttpResponseEncodingContext context;
    private JsonBodyEncoder streamingEncoder;
    private Object payload;

    @Setup(Level.Trial)
    public void setUp() {
        mapper = new ObjectMapper();
        writer = mapper.writer();
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        HttpRequest request = new HttpRequest(HttpMethod.GET, "/users", HttpVersion.HTTP_1_1, List.of(), null);
        context = new HttpResponseEncodingContext(request, allocator);
        streamingEncoder = new JsonBodyEncoder(mapper);
        payload = sampleUsers(50);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    @Benchmark
    public void encode(Blackhole bh) {
        HttpEncodedBody body = switch (strategy) {
            case STREAMING -> streamingEncoder.encode(payload, context);
            case STREAMING_REUSED_WRITER -> encodeStreamingReusedWriter(payload);
            case MATERIALIZE_AND_COPY -> encodeMaterializeAndCopy(payload);
        };
        // Read the produced bytes (forces the write) then release the loan so the pool recycles —
        // symmetric downstream cost for both strategies, so the delta is the encode step itself.
        bh.consume(body.body().size());
        body.body().close();
    }

    /**
     * Streaming through a pre-built (reused) {@link ObjectWriter}. <b>Documented negative result:</b>
     * measured allocation-neutral vs {@code STREAMING} (~1192 vs ~1192 B/op under {@code -prof gc}) —
     * Jackson allocates the per-call generator + write/IO/serialization contexts regardless of whether
     * the call goes through the {@code ObjectMapper} or a reused {@code ObjectWriter}, so writer reuse is
     * <em>not</em> a lever on the residual encode allocation. Kept so the finding is not re-litigated.
     */
    private HttpEncodedBody encodeStreamingReusedWriter(Object value) {
        SegmentSink sink = new SegmentSink(context.allocator().allocateNetwork(4096), context.allocator());
        boolean committed = false;
        try {
            writer.writeValue(sink, value);
            sink.setSizeToWritten();
            HttpEncodedBody body = new HttpEncodedBody(JSON_HEADERS, sink.buffer());
            committed = true;
            return body;
        } finally {
            if (!committed) {
                sink.discard();
            }
        }
    }

    /** Pre-0.10.2 baseline path, inlined here for A/B only — not used by production code. */
    private HttpEncodedBody encodeMaterializeAndCopy(Object value) {
        byte[] bytes = mapper.writeValueAsBytes(value);
        LoanedBuffer buffer = context.allocator().allocateNetwork(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buffer.segment(), 0, bytes.length);
        buffer.setSize(bytes.length);
        return new HttpEncodedBody(JSON_HEADERS, buffer);
    }

    private static List<UserRow> sampleUsers(int count) {
        List<UserRow> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            users.add(new UserRow(
                    i,
                    "user-" + i,
                    "user-" + i + "@example.com",
                    List.of("interest-" + i, "topic-" + (i % 7), "tag-" + (i % 13))));
        }
        return users;
    }

    /** Small DTO approximating a row of the benchmark {@code /users} response. */
    public record UserRow(long id, String name, String email, List<String> interests) {
    }

    /** The three encode strategies compared in one run (see the class Javadoc for each). */
    public enum Strategy {
        STREAMING,
        STREAMING_REUSED_WRITER,
        MATERIALIZE_AND_COPY
    }
}
