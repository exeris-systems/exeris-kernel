/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.http.Http2ScriptedTransport.Frame;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frame size the server enforces on what it receives, and the frame size it keeps on what it
 * sends, against the {@code SETTINGS_MAX_FRAME_SIZE} a peer advertises (RFC 9113 sections 4.2 and
 * 6.5.2): the peer's value bounds the frames the peer receives, so it never moves the inbound limit,
 * and a value outside the protocol's range is a connection error.
 */
@DisplayName("HTTP/2 SETTINGS_MAX_FRAME_SIZE")
class Http2FrameSizeSettingsTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final int DATA = Http2FrameType.DATA.code();
    private static final int HEADERS = Http2FrameType.HEADERS.code();
    private static final int CONTINUATION = Http2FrameType.CONTINUATION.code();
    private static final int GOAWAY = Http2FrameType.GOAWAY.code();
    private static final int DEFAULT_FRAME_SIZE = 16_384;
    private static final int MAX_FRAME_SIZE = 16_777_215;

    private final List<Thread> started = new ArrayList<>();
    private final AtomicInteger handled = new AtomicInteger();
    private final List<Integer> bodyLengths = new CopyOnWriteArrayList<>();
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
    @DisplayName("a peer that advertises the largest frame size still cannot send a frame over 16384")
    void aFrameOverTheServersOwnLimitIsAFrameSizeErrorWhateverThePeerAdvertised() {
        connect(respondOnce());
        transport.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.settings(Http2Settings.ID_MAX_FRAME_SIZE, MAX_FRAME_SIZE),
                Http2Frames.headers(1, post("/echo"), false),
                Http2Frames.data(1, new byte[DEFAULT_FRAME_SIZE + 1], false)));

        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);

        assertThat(Http2Frames.readInt32(goAway.payload(), 4)).isEqualTo(Http2ErrorCode.FRAME_SIZE_ERROR.code());
        assertThat(handled).hasValue(0);
    }

    @Test
    @DisplayName("a peer that advertises the smallest frame size is served a frame of exactly 16384")
    void aFrameAtTheServersOwnLimitIsServed() {
        connect(respondOnce());
        transport.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.settings(Http2Settings.ID_MAX_FRAME_SIZE, DEFAULT_FRAME_SIZE),
                Http2Frames.headers(1, post("/echo"), false),
                Http2Frames.data(1, new byte[DEFAULT_FRAME_SIZE], true)));

        transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 1 && f.isEndStream(), BOUND);

        assertThat(bodyLengths).containsExactly(DEFAULT_FRAME_SIZE);
        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);
    }

    @ParameterizedTest(name = "advertised {0}")
    @ValueSource(longs = {DEFAULT_FRAME_SIZE - 1L, MAX_FRAME_SIZE + 1L})
    @DisplayName("a peer value outside [16384, 16777215] is GOAWAY(PROTOCOL_ERROR)")
    void aPeerValueOutsideTheProtocolRangeIsAProtocolError(long advertised) {
        connect(respondOnce());
        transport.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.settings(Http2Settings.ID_MAX_FRAME_SIZE, advertised)));

        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);

        assertThat(Http2Frames.readInt32(goAway.payload(), 4)).isEqualTo(Http2ErrorCode.PROTOCOL_ERROR.code());
    }

    @ParameterizedTest(name = "advertised {0}")
    @ValueSource(ints = {DEFAULT_FRAME_SIZE, MAX_FRAME_SIZE})
    @DisplayName("no outbound frame is larger than 16384 for any legal peer value")
    void noOutboundFrameExceedsTheDefaultFrameSize(int advertised) {
        int responseBytes = 100_000;
        connect(exchange -> exchange.respond(
                new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_2, List.of(), bodyOf(responseBytes))));
        transport.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_MAX_FRAME_SIZE, advertised),
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 200_000),
                Http2Frames.windowUpdate(0, 150_000),
                Http2Frames.headers(1, get("/big"), true)));

        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);

        List<Frame> outbound = transport.seen().stream()
                .filter(f -> f.type() == DATA || f.type() == HEADERS || f.type() == CONTINUATION)
                .toList();
        assertThat(outbound).allSatisfy(f -> assertThat(f.payload()).hasSizeLessThanOrEqualTo(DEFAULT_FRAME_SIZE));
        assertThat(outbound.stream().filter(f -> f.type() == DATA).mapToInt(f -> f.payload().length).sum())
                .isEqualTo(responseBytes);
    }

    @Test
    @DisplayName("applying a peer's 1 MiB frame size leaves the inbound limit at 16384")
    void applyingALargePeerFrameSizeLeavesTheInboundLimitAlone() {
        try (Http2SessionContext session = Http2SessionContext.create(ALLOCATOR, HttpConfig.defaultServer());
             LoanedBuffer settings = ALLOCATOR.allocateNetwork(6)) {
            writeSetting(settings, Http2Settings.ID_MAX_FRAME_SIZE, 1_048_576);
            Http2ConnectionWriter writer = Http2ConnectionWriter.create(
                    new DiscardingTransportStream(), ALLOCATOR, 100, 64 * 1024);

            Http2ErrorCode result = session.applyPeerSettings(settings, 0, 6, writer);

            assertThat(result).isEqualTo(Http2ErrorCode.NO_ERROR);
            assertThat(session.codec().maxFrameSize()).isEqualTo(DEFAULT_FRAME_SIZE);
        }
    }

    // ------------------------------------------------------------------ support

    private static void writeSetting(LoanedBuffer buffer, int id, long value) {
        byte[] entry = {(byte) (id >>> 8), (byte) id, (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
        MemorySegment.copy(MemorySegment.ofArray(entry), 0, buffer.segment(), 0, entry.length);
        buffer.setSize(entry.length);
    }

    private void connect(HttpHandler handler) {
        allocator = new LeakTrackingAllocator(ALLOCATOR);
        transport = new Http2ScriptedTransport();
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                allocator, HttpResponseBodyEncoderRegistry.empty(), HttpConfig.defaultServer());
        Thread thread = Thread.ofVirtual().unstarted(() -> processor.process(transport, handler));
        started.add(thread);
        thread.start();
    }

    private HttpHandler respondOnce() {
        return exchange -> {
            handled.incrementAndGet();
            LoanedBuffer body = exchange.request().body();
            bodyLengths.add(body == null ? 0 : (int) body.size());
            exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
        };
    }

    private LoanedBuffer bodyOf(int length) {
        LoanedBuffer buffer = allocator.allocateNetwork(length);
        buffer.segment().asSlice(0, length).fill((byte) 0x5A);
        buffer.setSize(length);
        return buffer;
    }

    private static byte[] get(String path) {
        return Http2Frames.requestBlock(ALLOCATOR, "GET", path);
    }

    private static byte[] post(String path) {
        return Http2Frames.requestBlock(ALLOCATOR, "POST", path);
    }
}
