/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.http.Http2ScriptedTransport.Frame;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FlowController;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.core.http.http2.Http2Settings;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frame loop's flow-control behaviour end to end: a request enters through
 * {@code CommunityHttpRequestProcessor} over a scripted transport, and what the peer would see on the
 * wire is asserted frame by frame.
 *
 * <p>Whether a continuation task has parked is never waited for by time. A PING sent after the
 * requests is answered by the frame loop after every response write it triggered, so its ACK is the
 * point after which the absence of further frames is a fact; a window update is then fed and the
 * frames it releases are awaited as conditions.
 */
@DisplayName("HTTP/2 outbound flow control through the frame loop")
class Http2FlowControlProcessorTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final int DATA = Http2FrameType.DATA.code();
    private static final int HEADERS = Http2FrameType.HEADERS.code();
    private static final int RST_STREAM = Http2FrameType.RST_STREAM.code();
    private static final int GOAWAY = Http2FrameType.GOAWAY.code();
    private static final int PING = Http2FrameType.PING.code();
    private static final int ACK = 0x01;

    private final List<Thread> started = new ArrayList<>();
    private LeakTrackingAllocator allocator;
    private Http2ScriptedTransport transport;

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @AfterEach
    void endConnection() {
        if (transport != null) {
            transport.feedEof();
        }
        for (Thread thread : started) {
            try {
                thread.join(BOUND);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ respond-once bodies

    @Test
    @DisplayName("a body beyond the connection window waits for a connection WINDOW_UPDATE")
    void respondOnceBodyBeyondTheConnectionWindowWaitsForWindowUpdate() {
        byte[] bodyBytes = pattern(40_000);
        Connection connection = connect(respondWith(bodyBytes));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/a"), true),
                Http2Frames.headers(3, get("/b"), true)));
        connection.barrier();

        assertThat(dataBytes(1)).as("stream 1 fits the window and is written whole").isEqualTo(40_000);
        assertThat(endStreamSeen(1)).isTrue();
        assertThat(dataBytes(3)).as("stream 3 gets what is left of the connection window")
                .isEqualTo(Http2FlowController.DEFAULT_WINDOW_SIZE - 40_000);
        assertThat(endStreamSeen(3)).isFalse();

        connection.feed(Http2Frames.windowUpdate(0, 14_465));
        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 3 && f.isEndStream(), BOUND);

        assertThat(dataBytes(3)).isEqualTo(40_000);
        assertThat(reassembled(3)).isEqualTo(bodyBytes);
    }

    @Test
    @DisplayName("a body beyond the stream window waits for a stream WINDOW_UPDATE")
    void respondOnceBodyBeyondTheStreamWindowWaitsForStreamWindowUpdate() {
        byte[] bodyBytes = pattern(3000);
        Connection connection = connect(respondWith(bodyBytes));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 1000),
                Http2Frames.headers(1, get("/a"), true)));
        connection.barrier();

        assertThat(dataBytes(1)).isEqualTo(1000);
        assertThat(endStreamSeen(1)).isFalse();

        connection.feed(Http2Frames.windowUpdate(1, 2000));
        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);

        assertThat(reassembled(1)).isEqualTo(bodyBytes);
    }

    @Test
    @DisplayName("a WINDOW_UPDATE that arrives while the request body is still arriving is credited to the response")
    void windowUpdateWhileRequestBodyArrivesIsCreditedToTheResponse() {
        byte[] bodyBytes = pattern(500);
        Connection connection = connect(respondWith(bodyBytes));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0),
                Http2Frames.headers(1, post("/echo"), false),
                Http2Frames.windowUpdate(1, 500),
                Http2Frames.data(1, new byte[]{1, 2, 3}, true)));

        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);

        assertThat(reassembled(1)).isEqualTo(bodyBytes);
    }

    // ------------------------------------------------------------------ protocol errors

    @Test
    @DisplayName("a WINDOW_UPDATE whose length is not four is a FRAME_SIZE_ERROR")
    void windowUpdateWithWrongLengthIsFrameSizeError() {
        Connection connection = connect(respondWith(pattern(1)));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.windowUpdateOfLength(0, 3)));

        assertThat(goAwayCode()).isEqualTo(Http2ErrorCode.FRAME_SIZE_ERROR.code());
    }

    @Test
    @DisplayName("a connection WINDOW_UPDATE of zero is a PROTOCOL_ERROR")
    void connectionWindowUpdateOfZeroIsProtocolError() {
        Connection connection = connect(respondWith(pattern(1)));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(), Http2Frames.windowUpdate(0, 0)));

        assertThat(goAwayCode()).isEqualTo(Http2ErrorCode.PROTOCOL_ERROR.code());
    }

    @Test
    @DisplayName("a SETTINGS_INITIAL_WINDOW_SIZE above the maximum is a FLOW_CONTROL_ERROR")
    void settingsInitialWindowSizeAboveMaximumIsFlowControlError() {
        Connection connection = connect(respondWith(pattern(1)));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 1L << 31)));

        assertThat(goAwayCode()).isEqualTo(Http2ErrorCode.FLOW_CONTROL_ERROR.code());
    }

    @Test
    @DisplayName("a zero WINDOW_UPDATE on a stream whose request is arriving resets that stream")
    void zeroWindowUpdateOnArrivingRequestResetsTheStream() {
        Connection connection = connect(respondWith(pattern(1)));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, post("/echo"), false),
                Http2Frames.windowUpdate(1, 0)));

        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);

        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.PROTOCOL_ERROR.code());
    }

    @Test
    @DisplayName("a WINDOW_UPDATE that would overflow the stream window while the request arrives resets that stream")
    void overflowingWindowUpdateOnArrivingRequestResetsTheStream() {
        Connection connection = connect(respondWith(pattern(1)));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, post("/echo"), false),
                Http2Frames.windowUpdate(1, Http2FlowController.MAX_WINDOW_SIZE)));

        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);

        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.FLOW_CONTROL_ERROR.code());
    }

    // ------------------------------------------------------------------ slots and teardown

    @Test
    @DisplayName("a response a continuation still holds counts against the concurrent stream cap")
    void continuationsCountAgainstMaxConcurrentStreams() {
        HttpHandler handler = exchange -> {
            if (exchange.request().path().equals("/nobody")) {
                exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
            } else {
                exchange.respond(new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_2, List.of(), bodyOf(pattern(10))));
            }
        };
        Connection connection = connect(handler);
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        requests.writeBytes(Http2Frames.PREFACE);
        requests.writeBytes(Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0));
        for (int i = 0; i < 100; i++) {
            requests.writeBytes(Http2Frames.headers(2 * i + 1, get("/held"), true));
        }
        requests.writeBytes(Http2Frames.headers(201, get("/nobody"), true));
        connection.feed(requests.toByteArray());

        Frame refused = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 201, BOUND);
        assertThat(Http2Frames.readInt32(refused.payload(), 0))
                .as("100 held responses leave no slot for stream 201")
                .isEqualTo(Http2ErrorCode.REFUSED_STREAM.code());

        connection.feed(Http2Frames.windowUpdate(1, 10));
        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);
        // The slot is freed when the continuation task returns, just after its last frame is
        // written, so a request is offered until one is served rather than after a delay.
        boolean served = false;
        for (int id = 203; id < 203 + 2 * 50 && !served; id += 2) {
            int offered = id;
            connection.feed(Http2Frames.headers(offered, get("/nobody"), true));
            Frame answer = transport.awaitFrame(
                    f -> f.streamId() == offered && (f.type() == RST_STREAM || f.type() == HEADERS), BOUND);
            served = answer.type() == HEADERS;
        }

        assertThat(served).as("a freed continuation slot admits a new stream").isTrue();
    }

    @Test
    @DisplayName("a peer reset stops a parked continuation and releases its body")
    void peerResetDuringContinuationStopsTheBody() throws Exception {
        CountDownLatch bodyReleased = new CountDownLatch(1);
        Connection connection = connect(exchange -> {
            LoanedBuffer body = bodyOf(pattern(100));
            body.addCloseAction(bodyReleased::countDown);
            exchange.respond(new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_2, List.of(), body));
        });
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0),
                Http2Frames.headers(1, get("/held"), true)));
        connection.barrier();
        assertThat(bodyReleased.getCount()).as("the continuation owns the body").isEqualTo(1);

        connection.feed(Http2Frames.rstStream(1, Http2ErrorCode.CANCEL.code()));

        assertThat(bodyReleased.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
        connection.barrier();
        assertThat(dataBytes(1)).isZero();
    }

    @Test
    @DisplayName("DATA after the request ended resets a continuation-served stream once, with STREAM_CLOSED")
    void dataOnAContinuationServedStreamResetsItThroughTheWriter() throws Exception {
        CountDownLatch bodyReleased = new CountDownLatch(1);
        Connection connection = connect(exchange -> {
            LoanedBuffer body = bodyOf(pattern(100));
            body.addCloseAction(bodyReleased::countDown);
            exchange.respond(new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_2, List.of(), body));
        });
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0),
                Http2Frames.headers(1, get("/held"), true),
                Http2Frames.data(1, new byte[]{1}, false)));
        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);
        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.STREAM_CLOSED.code());

        connection.feed(Http2Frames.windowUpdate(1, 100));
        assertThat(bodyReleased.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
        connection.barrier();

        List<Frame> seen = transport.seen();
        assertThat(seen.stream().filter(f -> f.type() == RST_STREAM && f.streamId() == 1)).hasSize(1);
        assertThat(dataBytes(1)).as("nothing is sent on a stream we reset").isZero();
    }

    @Test
    @DisplayName("an initial window that overflows a stream whose request is arriving is a FLOW_CONTROL_ERROR")
    void initialWindowThatOverflowsAnArrivingRequestIsFlowControlError() {
        Connection connection = connect(respondWith(pattern(1)));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, post("/echo"), false),
                Http2Frames.windowUpdate(1, Http2FlowController.MAX_WINDOW_SIZE
                        - Http2FlowController.DEFAULT_WINDOW_SIZE),
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE,
                        Http2FlowController.DEFAULT_WINDOW_SIZE + 1)));

        assertThat(goAwayCode()).isEqualTo(Http2ErrorCode.FLOW_CONTROL_ERROR.code());
    }

    @Test
    @DisplayName("ending the connection tears down continuations and releases their bodies")
    void eofTearsDownContinuationsAndReleasesTheirBodies() throws Exception {
        Connection connection = connect(respondWith(pattern(100)));
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0),
                Http2Frames.headers(1, get("/held"), true)));
        connection.barrier();
        assertThat(allocator.outstanding()).as("the held body is still owned").isGreaterThan(0);

        transport.feedEof();
        connection.processThread.join(BOUND);

        assertThat(connection.processThread.isAlive()).isFalse();
        assertThat(allocator.outstanding())
                .as("no buffer and no continuation outlives the connection")
                .isZero();
    }

    // ------------------------------------------------------------------ support

    private Connection connect(HttpHandler handler) {
        allocator = new LeakTrackingAllocator(ALLOCATOR);
        transport = new Http2ScriptedTransport();
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                allocator, HttpResponseBodyEncoderRegistry.empty(), HttpConfig.defaultServer());
        Thread thread = Thread.ofVirtual().unstarted(() -> processor.process(transport, handler));
        started.add(thread);
        thread.start();
        return new Connection(thread);
    }

    private final class Connection {
        private final Thread processThread;

        private Connection(Thread processThread) {
            this.processThread = processThread;
        }

        void feed(byte[] bytes) {
            transport.feed(bytes);
        }

        /** After this returns the frame loop has handled everything fed so far. */
        void barrier() {
            transport.feed(Http2Frames.frame(PING, 0, 0, new byte[8]));
            transport.awaitFrame(f -> f.type() == PING && (f.flags() & ACK) != 0, BOUND);
        }
    }

    private HttpHandler respondWith(byte[] body) {
        return exchange -> exchange.respond(
                new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_2, List.of(), bodyOf(body)));
    }

    private LoanedBuffer bodyOf(byte[] bytes) {
        LoanedBuffer buffer = allocator.allocateNetwork(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buffer.segment(), 0, bytes.length);
        buffer.setSize(bytes.length);
        return buffer;
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return bytes;
    }

    private static byte[] get(String path) {
        return Http2Frames.requestBlock(ALLOCATOR, "GET", path);
    }

    private static byte[] post(String path) {
        return Http2Frames.requestBlock(ALLOCATOR, "POST", path);
    }

    private int dataBytes(int streamId) {
        return transport.seen().stream()
                .filter(f -> f.type() == DATA && f.streamId() == streamId)
                .mapToInt(f -> f.payload().length)
                .sum();
    }

    private boolean endStreamSeen(int streamId) {
        return transport.seen().stream().anyMatch(f -> f.streamId() == streamId && f.isEndStream());
    }

    private byte[] reassembled(int streamId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transport.seen().stream()
                .filter(f -> f.type() == DATA && f.streamId() == streamId)
                .forEach(f -> out.writeBytes(f.payload()));
        return out.toByteArray();
    }

    private int goAwayCode() {
        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);
        return goAway.payload().length >= 8
                ? ((goAway.payload()[4] & 0xFF) << 24) | ((goAway.payload()[5] & 0xFF) << 16)
                | ((goAway.payload()[6] & 0xFF) << 8) | (goAway.payload()[7] & 0xFF)
                : -1;
    }
}
