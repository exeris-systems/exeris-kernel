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
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credit the frame loop returns for request bodies (RFC 9113 section 6.9): when it is returned,
 * how much, and what the server refuses when a peer ignores its windows.
 *
 * <p>The scripted peer is compliant: it tracks the connection window and each stream window from the
 * WINDOW_UPDATE frames it has observed, sends no more than both allow, and waits, bounded and without
 * sleeping, when either is zero. A server that returns no credit therefore stalls the peer until the
 * bound instead of being fed bytes it never granted.
 */
@DisplayName("HTTP/2 inbound flow control through the frame loop")
class Http2InboundFlowControlTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final int DATA = Http2FrameType.DATA.code();
    private static final int PING = Http2FrameType.PING.code();
    private static final int RST_STREAM = Http2FrameType.RST_STREAM.code();
    private static final int GOAWAY = Http2FrameType.GOAWAY.code();
    private static final int WINDOW_UPDATE = Http2FrameType.WINDOW_UPDATE.code();
    private static final int ACK = 0x01;
    private static final int FRAME_BYTES = 16_384;
    private static final int INITIAL_WINDOW = Http2FlowController.DEFAULT_WINDOW_SIZE;

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

    @Test
    @DisplayName("a 200 000-byte body is served byte for byte, the peer sending only what it is granted")
    void bodyBeyondTheInitialWindowIsServedByteForByte() {
        byte[] body = pattern(200_000);
        Connection connection = connect(HttpConfig.defaultServer());
        connection.open();
        connection.feed(Http2Frames.headers(1, post("/upload"), false));

        connection.peer.upload(1, body, FRAME_BYTES, true);

        assertThat(connection.responseText(1)).isEqualTo(summary(body));
    }

    @Test
    @DisplayName("credit is returned once a window is half spent, to the stream and the connection together")
    void creditIsReturnedAtHalfAWindowAndCoalesced() {
        Connection connection = connect(HttpConfig.defaultServer());
        connection.open();
        connection.feed(Http2Frames.headers(1, post("/upload"), false));

        connection.feed(Http2Frames.data(1, new byte[FRAME_BYTES], false));
        connection.barrier();
        assertThat(windowUpdates()).as("a quarter of the window spent returns nothing").isEmpty();

        connection.feed(Http2Frames.data(1, new byte[FRAME_BYTES], false));
        connection.barrier();

        assertThat(windowUpdates()).containsExactly(
                new Update(1, 32_768), new Update(0, 32_768));
    }

    @Test
    @DisplayName("padded frames are charged whole: padding and the pad length count toward both windows")
    void paddedFramesAreChargedWhole() {
        Connection connection = connect(HttpConfig.defaultServer());
        connection.open();
        connection.feed(Http2Frames.headers(1, post("/upload"), false));
        byte[] payload = new byte[FRAME_BYTES - 256];

        connection.feed(Http2Frames.paddedData(1, payload, 255, false));
        connection.feed(Http2Frames.paddedData(1, payload, 255, false));
        connection.barrier();

        assertThat(windowUpdates()).containsExactly(
                new Update(1, 32_768), new Update(0, 32_768));
    }

    @Test
    @DisplayName("credit never invites more than http.maxRequestBodyBytes allows for the stream")
    void streamCreditIsCappedByTheBodyLimit() {
        byte[] body = pattern(100_000);
        Connection connection = connect(config(100_000));
        connection.open();
        connection.feed(Http2Frames.headers(1, post("/upload"), false));

        connection.peer.upload(1, body, FRAME_BYTES, true);

        assertThat(connection.responseText(1)).isEqualTo(summary(body));
        long granted = INITIAL_WINDOW + windowUpdates().stream()
                .filter(update -> update.streamId() == 1).mapToLong(Update::increment).sum();
        assertThat(granted).isLessThanOrEqualTo(100_000);
    }

    @Test
    @DisplayName("a peer that overruns the stream window gets RST_STREAM(FLOW_CONTROL_ERROR) and the connection lives")
    void streamWindowOverrunResetsTheStream() {
        Connection connection = connect(config(70_000));
        connection.open();
        connection.feed(Http2Frames.concat(
                Http2Frames.headers(1, post("/upload"), false),
                Http2Frames.data(1, new byte[FRAME_BYTES], false),
                Http2Frames.data(1, new byte[FRAME_BYTES], false),
                Http2Frames.data(1, new byte[FRAME_BYTES], false),
                Http2Frames.data(1, new byte[FRAME_BYTES], false),
                Http2Frames.headers(3, get("/ping"), true)));

        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);

        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.FLOW_CONTROL_ERROR.code());
        assertThat(connection.responseText(3)).isEqualTo(summary(new byte[0]));
        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);
    }

    @Test
    @DisplayName("DATA for a cancelled stream still returns connection credit and none for the stream")
    void discardedDataStillReturnsConnectionCredit() {
        Connection connection = connect(config(20_000));
        connection.open();
        connection.feed(Http2Frames.concat(
                Http2Frames.headers(1, post("/upload"), false),
                Http2Frames.data(1, new byte[FRAME_BYTES], false),
                Http2Frames.rstStream(1, Http2ErrorCode.CANCEL.code()),
                Http2Frames.data(1, new byte[FRAME_BYTES], false),
                Http2Frames.data(1, new byte[FRAME_BYTES], false)));
        connection.barrier();

        assertThat(windowUpdates()).containsExactly(new Update(0, 32_768));
    }

    @Test
    @DisplayName("a stream that would take the bodies held on one connection past the limit is refused")
    void connectionBudgetRefusesTheStreamThatExceedsIt() {
        byte[] first = pattern(60_000);
        byte[] rest = pattern(40_000);
        Connection connection = connect(config(100_000));
        connection.open();
        connection.feed(Http2Frames.concat(
                Http2Frames.headers(1, post("/upload"), false),
                Http2Frames.headers(3, post("/upload"), false)));
        connection.peer.upload(1, first, FRAME_BYTES, false);

        connection.peer.upload(3, new byte[40_001], FRAME_BYTES, false);
        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 3, BOUND);

        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.REFUSED_STREAM.code());
        connection.peer.upload(1, rest, FRAME_BYTES, true);
        assertThat(connection.responseText(1)).isEqualTo(summary(Http2Frames.concat(first, rest)));
        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);

        transport.feedEof();
        connection.join();
        assertThat(allocator.outstanding()).as("a refused body is released").isZero();
    }

    @Test
    @DisplayName("a session rejects a DATA frame larger than the connection window it granted")
    void sessionReportsAConnectionWindowViolation() {
        try (Http2SessionContext session = Http2SessionContext.create(ALLOCATOR, HttpConfig.defaultServer())) {
            assertThat(session.consumeConnectionReceiveWindow(INITIAL_WINDOW + 1)).isFalse();
            assertThat(session.consumeConnectionReceiveWindow(INITIAL_WINDOW)).isTrue();
            assertThat(session.consumeConnectionReceiveWindow(1)).isFalse();
        }
    }

    // ------------------------------------------------------------------ support

    private record Update(int streamId, int increment) {
    }

    private List<Update> windowUpdates() {
        return transport.seen().stream()
                .filter(f -> f.type() == WINDOW_UPDATE)
                .map(f -> new Update(f.streamId(), Http2Frames.readInt32(f.payload(), 0)))
                .toList();
    }

    private static HttpConfig config(long maxRequestBodyBytes) {
        HttpConfig base = HttpConfig.defaultServer();
        return new HttpConfig(HttpMode.SERVER, base.bindHost(), base.port(), base.maxConnections(),
                base.idleTimeoutMillis(), base.maxRequestHeaderCount(), base.maxRequestHeaderSize(),
                maxRequestBodyBytes, true, HttpVersion.HTTP_2, null, base.maxHeaderBlockSize(),
                base.maxHeaderListSize(), base.maxStringLiteralSize());
    }

    private Connection connect(HttpConfig config) {
        allocator = new LeakTrackingAllocator(ALLOCATOR);
        transport = new Http2ScriptedTransport();
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                allocator, HttpResponseBodyEncoderRegistry.empty(), config);
        Thread thread = Thread.ofVirtual().unstarted(() -> processor.process(transport, summarising()));
        started.add(thread);
        thread.start();
        return new Connection(thread);
    }

    /** Answers with the length and CRC-32 of the request body it received. */
    private HttpHandler summarising() {
        return exchange -> {
            LoanedBuffer body = exchange.request().body();
            byte[] received = body == null
                    ? new byte[0]
                    : body.segment().asSlice(0, body.size()).toArray(ValueLayout.JAVA_BYTE);
            byte[] text = summary(received).getBytes(StandardCharsets.US_ASCII);
            LoanedBuffer response = allocator.allocateNetwork(text.length);
            MemorySegment.copy(MemorySegment.ofArray(text), 0, response.segment(), 0, text.length);
            response.setSize(text.length);
            exchange.respond(new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_2, List.of(), response));
        };
    }

    private static String summary(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return body.length + ":" + crc.getValue();
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

    private final class Connection {
        private final Thread processThread;
        private final Peer peer = new Peer();

        private Connection(Thread processThread) {
            this.processThread = processThread;
        }

        void open() {
            feed(Http2Frames.concat(Http2Frames.PREFACE, Http2Frames.emptySettings()));
        }

        void feed(byte[] bytes) {
            transport.feed(bytes);
        }

        /** After this returns the frame loop has handled everything fed so far. */
        void barrier() {
            transport.feed(Http2Frames.frame(PING, 0, 0, new byte[8]));
            transport.awaitFrame(f -> f.type() == PING && (f.flags() & ACK) != 0, BOUND);
        }

        String responseText(int streamId) {
            transport.awaitFrame(f -> f.type() == DATA && f.streamId() == streamId && f.isEndStream(), BOUND);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transport.seen().stream()
                    .filter(f -> f.type() == DATA && f.streamId() == streamId)
                    .forEach(f -> out.writeBytes(f.payload()));
            return out.toString(StandardCharsets.US_ASCII);
        }

        void join() {
            try {
                processThread.join(BOUND);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            assertThat(processThread.isAlive()).isFalse();
        }
    }

    /**
     * A compliant sender: it never has more DATA in flight than the connection and the stream window
     * the server has granted, and it learns of new credit only from WINDOW_UPDATE frames it observed.
     */
    private final class Peer {
        private int connectionWindow = INITIAL_WINDOW;
        private final Map<Integer, Integer> streamWindows = new HashMap<>();
        private int absorbed;

        void upload(int streamId, byte[] body, int frameBytes, boolean endStream) {
            int sent = 0;
            while (sent < body.length) {
                int window = awaitWindow(streamId);
                int length = Math.min(Math.min(frameBytes, body.length - sent), window);
                byte[] chunk = new byte[length];
                System.arraycopy(body, sent, chunk, 0, length);
                sent += length;
                connectionWindow -= length;
                streamWindows.put(streamId, streamWindows.getOrDefault(streamId, INITIAL_WINDOW) - length);
                transport.feed(Http2Frames.data(streamId, chunk, endStream && sent == body.length));
            }
        }

        private int awaitWindow(int streamId) {
            while (true) {
                absorbObservedUpdates();
                int window = Math.min(connectionWindow, streamWindows.getOrDefault(streamId, INITIAL_WINDOW));
                if (window > 0) {
                    return window;
                }
                transport.awaitFrame(f -> f.type() == WINDOW_UPDATE, BOUND);
            }
        }

        private void absorbObservedUpdates() {
            List<Frame> seen = transport.seen();
            for (; absorbed < seen.size(); absorbed++) {
                Frame frame = seen.get(absorbed);
                if (frame.type() != WINDOW_UPDATE) {
                    continue;
                }
                int increment = Http2Frames.readInt32(frame.payload(), 0);
                if (frame.streamId() == 0) {
                    connectionWindow += increment;
                } else {
                    streamWindows.put(frame.streamId(),
                            streamWindows.getOrDefault(frame.streamId(), INITIAL_WINDOW) + increment);
                }
            }
        }
    }
}
