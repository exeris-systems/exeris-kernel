/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.client;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpClientRequestEnricher;
import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpRequestEncodingContext;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseDecodingContext;
import eu.exeris.kernel.spi.http.HttpRetryPolicy;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RetryDecision;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request-body release contract of {@link KernelWebClient}, driven through Core-only fixtures.
 *
 * <p>The client is the caller of {@link HttpClientEngine#send(HttpRequest)}, so it owns the encoded
 * request body: the engine must find it live during {@code send}, and the client must release it
 * exactly once on every path out of the attempt — before the retry policy decides and before any
 * retry wait. Every buffer here comes from a {@link TrackingAllocator}, so a zero
 * {@code outstanding()} after a call means the call returned every buffer it took.
 */
@DisplayName("L1: KernelWebClient request-body release")
class KernelWebClientRequestBodyReleaseTest {

    private static final String TEXT_PLAIN = "text/plain";

    private final TrackingAllocator allocator = new TrackingAllocator();

    private KernelWebClient client(HttpClientEngine engine, HttpClientRequestEnricher enricher,
                                   HttpRetryPolicy policy) {
        return client(engine, enricher, policy, new TextEncoder());
    }

    private KernelWebClient client(HttpClientEngine engine, HttpClientRequestEnricher enricher,
                                   HttpRetryPolicy policy, HttpRequestBodyEncoder encoder) {
        return new KernelWebClient(engine, allocator,
                HttpRequestBodyEncoderRegistry.of(List.of(encoder)),
                HttpResponseBodyDecoderRegistry.of(List.<HttpResponseBodyDecoder>of(new TextDecoder())),
                enricher, policy);
    }

    @Test
    @DisplayName("a successful POST: the engine reads a live body, and the call releases it once")
    void successfulPostReleasesRequestBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(text(200, "created")));

        String result = client(engine, HttpClientRequestEnricher.noop(), HttpRetryPolicy.none())
                .post("/widget", "cogwheel", String.class);

        assertThat(result).isEqualTo("created");
        assertThat(engine.bodyAliveAtSend).as("the body the engine read was live").containsExactly(true);
        assertThat(engine.bodyTextAtSend).as("the engine read the encoded payload").containsExactly("cogwheel");
        assertThat(engine.received.getFirst().body().isAlive())
                .as("the request body sent is released once the call returns").isFalse();
        assertThat(allocator.allocated()).as("one request body and one response body").isEqualTo(2);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
        assertThat(allocator.everyBufferClosedExactlyOnce()).as("no buffer released twice").isTrue();
    }

    @Test
    @DisplayName("send throws, the policy retries once, then 200: each attempt's body is released before the policy")
    void transportFailureRetriedOnceReleasesEveryAttemptsBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(boom(), text(200, "created")));
        List<Boolean> bodyAliveAtDecide = new ArrayList<>();
        HttpRetryPolicy retryOnce = (request, outcome, attemptIndex) -> {
            bodyAliveAtDecide.add(request.body().isAlive());
            return attemptIndex == 0 ? RetryDecision.retryAfter(0L) : RetryDecision.giveUp();
        };

        String result = client(engine, HttpClientRequestEnricher.noop(), retryOnce)
                .post("/widget", "cogwheel", String.class);

        assertThat(result).isEqualTo("created");
        assertThat(engine.received).hasSize(2);
        assertThat(engine.bodyAliveAtSend).as("each attempt sent a live body").containsExactly(true, true);
        assertThat(engine.received.get(0).body())
                .as("each attempt encodes its own body; none is carried across attempts")
                .isNotSameAs(engine.received.get(1).body());
        assertThat(bodyAliveAtDecide)
                .as("the policy, and any wait it asks for, run after the failed attempt's body is released")
                .containsExactly(false);
        assertThat(allocator.allocated()).as("two request bodies and one response body").isEqualTo(3);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
        assertThat(allocator.everyBufferClosedExactlyOnce()).as("no buffer released twice").isTrue();
    }

    @Test
    @DisplayName("send throws and the policy gives up: the failure surfaces unwrapped, the body already released")
    void transportFailureNotRetriedReleasesBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(boom()));

        assertThatThrownBy(() -> client(engine, HttpClientRequestEnricher.noop(), HttpRetryPolicy.none())
                .post("/widget", "cogwheel", String.class))
                .isInstanceOf(TransportBoom.class);

        assertThat(engine.bodyAliveAtSend).containsExactly(true);
        assertThat(allocator.allocated()).as("the request body was encoded").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("an enricher that throws: the encoded body is released and nothing is sent")
    void enricherFailureReleasesBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of());
        HttpClientRequestEnricher refusing = request -> {
            throw new IllegalArgumentException("header value carries CR");
        };

        assertThatThrownBy(() -> client(engine, refusing, HttpRetryPolicy.none())
                .post("/widget", "cogwheel", String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header value carries CR");

        assertThat(engine.received).as("nothing was sent").isEmpty();
        assertThat(allocator.allocated()).as("the request body was encoded").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("no bound authority: the engine's default addresses the request, and the enricher sees it")
    void defaultAuthorityAddressesAnUnboundClient() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(text(200, "ok")));
        engine.defaultAuthority = "default.internal:80";
        List<String> seenByEnricher = new ArrayList<>();
        HttpClientRequestEnricher recording = request -> {
            seenByEnricher.add(request.authority());
            return request;
        };

        client(engine, recording, HttpRetryPolicy.none()).post("/widget", "cogwheel", String.class);

        assertThat(seenByEnricher).containsExactly("default.internal:80");
        assertThat(engine.received).extracting(HttpRequest::authority).containsExactly("default.internal:80");
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("a bound authority is sent as is, without consulting the engine's default")
    void boundAuthorityDoesNotConsultTheDefault() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(text(200, "ok")));
        engine.defaultAuthorityFailure = new IllegalStateException("default must not be consulted");

        client(engine, HttpClientRequestEnricher.noop(), HttpRetryPolicy.none())
                .withAuthority("payments.internal:8443")
                .post("/widget", "cogwheel", String.class);

        assertThat(engine.received).extracting(HttpRequest::authority).containsExactly("payments.internal:8443");
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("a default-authority lookup that throws: the encoded body is released and nothing is sent")
    void defaultAuthorityFailureReleasesBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of());
        engine.defaultAuthorityFailure = new IllegalStateException("engine closed");

        assertThatThrownBy(() -> client(engine, HttpClientRequestEnricher.noop(), HttpRetryPolicy.none())
                .post("/widget", "cogwheel", String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("engine closed");

        assertThat(engine.received).as("nothing was sent").isEmpty();
        assertThat(allocator.allocated()).as("the request body was encoded").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("a bodyless GET: no request body exists to release, and the response body is released")
    void bodylessGetReleasesOnlyTheResponse() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of(text(200, "widget-1")));

        String result = client(engine, HttpClientRequestEnricher.noop(), HttpRetryPolicy.none())
                .get("/widget/1", String.class);

        assertThat(result).isEqualTo("widget-1");
        assertThat(engine.received).singleElement()
                .satisfies(request -> assertThat(request.hasBody()).isFalse());
        assertThat(allocator.allocated()).as("only the response body").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    @Test
    @DisplayName("an encoded body whose request cannot be built: the body is released before the failure surfaces")
    void unbuildableRequestReleasesEncodedBody() {
        ProgrammedEngine engine = new ProgrammedEngine(List.of());
        // A null header element passes HttpEncodedBody's own check and fails when the client copies
        // the merged header list — after the body was allocated, before any request carries it.
        HttpRequestBodyEncoder nullHeader = new TextEncoder() {
            @Override
            public HttpEncodedBody encode(Object payload, HttpRequestEncodingContext context) {
                HttpEncodedBody encoded = super.encode(payload, context);
                return new HttpEncodedBody(Arrays.asList((HttpHeader) null), encoded.body());
            }
        };

        assertThatThrownBy(() -> client(engine, HttpClientRequestEnricher.noop(), HttpRetryPolicy.none(),
                nullHeader).post("/widget", "cogwheel", String.class))
                .isInstanceOf(NullPointerException.class);

        assertThat(engine.received).as("nothing was sent").isEmpty();
        assertThat(allocator.allocated()).as("the request body was encoded").isEqualTo(1);
        assertThat(allocator.outstanding()).as("buffers still held after the call").isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private static Supplier<HttpResponse> boom() {
        return () -> {
            throw new TransportBoom();
        };
    }

    private Supplier<HttpResponse> text(int code, String text) {
        return () -> {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            LoanedBuffer body = allocator.allocateNetwork(bytes.length);
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, body.segment(), 0, bytes.length);
            body.setSize(bytes.length);
            return new HttpResponse(new HttpStatus(code, "Status " + code), HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("content-type", TEXT_PLAIN)), body);
        };
    }

    private static String utf8(LoanedBuffer body) {
        byte[] bytes = new byte[Math.toIntExact(body.size())];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(bytes), 0L, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Sentinel unchecked exception standing in for an engine-level transport failure. */
    private static final class TransportBoom extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TransportBoom() {
            super("simulated transport failure");
        }
    }

    /** Encodes a {@link String} payload as UTF-8 into a buffer taken from the context's allocator. */
    private static class TextEncoder implements HttpRequestBodyEncoder {

        @Override
        public boolean supports(Class<?> payloadType) {
            return payloadType == String.class;
        }

        @Override
        public HttpEncodedBody encode(Object payload, HttpRequestEncodingContext context) {
            byte[] bytes = ((String) payload).getBytes(StandardCharsets.UTF_8);
            LoanedBuffer body = context.allocator().allocateNetwork(bytes.length);
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, body.segment(), 0, bytes.length);
            body.setSize(bytes.length);
            return new HttpEncodedBody(List.of(new HttpHeader("content-type", TEXT_PLAIN)), body);
        }
    }

    /** Decodes a {@code text/plain} response body into a {@link String}. */
    private static final class TextDecoder implements HttpResponseBodyDecoder {

        @Override
        public boolean supports(Class<?> targetType, String contentType) {
            return targetType == String.class && TEXT_PLAIN.equals(contentType);
        }

        @Override
        public Object decode(LoanedBuffer body, Class<?> targetType, HttpResponseDecodingContext context) {
            return utf8(body);
        }
    }

    /**
     * A {@link HttpClientEngine} that answers from a script and records, for every {@code send}, the
     * request, whether its body was live, and the body's content as read at that moment.
     */
    private static final class ProgrammedEngine implements HttpClientEngine {

        private final Deque<Supplier<HttpResponse>> script;
        private final List<HttpRequest> received = new ArrayList<>();
        private final List<Boolean> bodyAliveAtSend = new ArrayList<>();
        private final List<String> bodyTextAtSend = new ArrayList<>();
        private String defaultAuthority;
        private RuntimeException defaultAuthorityFailure;

        ProgrammedEngine(List<Supplier<HttpResponse>> responses) {
            this.script = new ArrayDeque<>(responses);
        }

        @Override
        public String defaultAuthority() {
            if (defaultAuthorityFailure != null) {
                throw defaultAuthorityFailure;
            }
            return defaultAuthority;
        }

        @Override
        public String engineName() {
            return "programmed-stub";
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void start() {
            // no-op: this stub holds no transport resources.
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            received.add(request);
            if (request.hasBody()) {
                // The engine reads the body during send; a body released before this point would
                // hand the transport memory that is already back with the allocator.
                boolean alive = request.body().isAlive();
                bodyAliveAtSend.add(alive);
                if (alive) {
                    bodyTextAtSend.add(utf8(request.body()));
                }
            }
            Supplier<HttpResponse> next = script.poll();
            if (next == null) {
                throw new AssertionError("engine.send called more times than scripted");
            }
            return next.get();
        }

        @Override
        public void close() {
            // no-op.
        }
    }

    /**
     * A {@link MemoryAllocator} that hands out heap-backed {@link TrackedBuffer}s and remembers every
     * one, so a test can ask how many are still outstanding and whether any was closed twice.
     */
    private static final class TrackingAllocator implements MemoryAllocator {

        private final List<TrackedBuffer> buffers = new ArrayList<>();

        int allocated() {
            return buffers.size();
        }

        long outstanding() {
            return buffers.stream().filter(TrackedBuffer::isAlive).count();
        }

        boolean everyBufferClosedExactlyOnce() {
            return buffers.stream().allMatch(buffer -> buffer.closes == 1);
        }

        private LoanedBuffer track(long bytes) {
            TrackedBuffer buffer = new TrackedBuffer(Math.toIntExact(bytes));
            buffers.add(buffer);
            return buffer;
        }

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return track(hint.sizeBytes());
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            return track(estimatedBytes);
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            return allocate(AllocationHint.SMALL);
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            return track(sizeBytes);
        }

        @Override
        public MemoryStats stats() {
            return MemoryStats.zero();
        }

        @Override
        public void close() {
            // heap-backed buffers: nothing to free.
        }
    }

    /**
     * A heap-backed {@link LoanedBuffer} that counts its closes and refuses segment access once
     * closed, so reading a released body fails instead of reading stale bytes.
     */
    private static final class TrackedBuffer implements LoanedBuffer {

        private final MemorySegment segment;
        private long size;
        private int closes;

        TrackedBuffer(int capacity) {
            this.segment = MemorySegment.ofArray(new byte[capacity]);
            this.size = capacity;
        }

        @Override
        public MemorySegment segment() {
            if (!isAlive()) {
                throw new IllegalStateException("segment accessed after release");
            }
            return segment;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public void setSize(long newSize) {
            this.size = newSize;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            throw new UnsupportedOperationException("not used by KernelWebClient");
        }

        @Override
        public LoanedBuffer view() {
            throw new UnsupportedOperationException("not used by KernelWebClient");
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            throw new UnsupportedOperationException("not used by KernelWebClient");
        }

        @Override
        public void retain() {
            throw new UnsupportedOperationException("not used by KernelWebClient");
        }

        @Override
        public void close() {
            closes++;
        }

        @Override
        public int refCount() {
            return isAlive() ? 1 : 0;
        }

        @Override
        public boolean isAlive() {
            return closes == 0;
        }

        @Override
        public void addCloseAction(Runnable action) {
            throw new UnsupportedOperationException("not used by KernelWebClient");
        }
    }
}
