/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.hpack.HpackEncoder;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityHttpRequestProcessorTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void expectedStreamCloseDoesNotEmitWarning() {
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(ALLOCATOR, HttpResponseBodyEncoderRegistry.empty());
        HttpHandler handler = exchange -> {
        };

        List<LogRecord> records = captureProcessorWarnings(
                () -> processor.process(new ThrowingTransportStream(new IllegalStateException("Stream is closed")), handler));

        assertThat(records).isEmpty();
    }

    @Test
    void runtimeStreamFaultStillEmitsWarning() {
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(ALLOCATOR, HttpResponseBodyEncoderRegistry.empty());
        IllegalStateException failure = new IllegalStateException("boom");
        HttpHandler handler = exchange -> {
        };

        List<LogRecord> records = captureProcessorWarnings(
                () -> processor.process(new ThrowingTransportStream(failure), handler));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getMessage()).isEqualTo("Community HTTP stream handling failed");
        assertThat(records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void detectsHttp2PriorKnowledgePrefaceWhenAtBufferStart() {
        byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        try (LoanedBuffer buffer = ALLOCATOR.allocateNetwork(preface.length + 8)) {
            MemorySegment.copy(MemorySegment.ofArray(preface), 0, buffer.segment(), 0, preface.length);
            buffer.setSize(preface.length);

            assertThat(CommunityHttpRequestProcessor.isHttp2PriorKnowledgePreface(buffer.segment(), buffer.size()))
                    .isTrue();
        }
    }

    @Test
    void doesNotDetectHttp2PriorKnowledgePrefaceForRegularHttp1Bytes() {
        byte[] request = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        try (LoanedBuffer buffer = ALLOCATOR.allocateNetwork(request.length)) {
            MemorySegment.copy(MemorySegment.ofArray(request), 0, buffer.segment(), 0, request.length);
            buffer.setSize(request.length);

            assertThat(CommunityHttpRequestProcessor.isHttp2PriorKnowledgePreface(buffer.segment(), buffer.size()))
                    .isFalse();
        }
    }

    @Test
    void detectsH2cUpgradeIntentWithMixedCaseHeadersAndValues() {
        List<HttpHeader> headers = List.of(
                new HttpHeader("cOnNeCtIoN", "keep-alive, UpGrAdE"),
                new HttpHeader("UpGrAdE", "H2C"));

        assertThat(CommunityHttpRequestProcessor.isH2cUpgradeIntent(headers)).isTrue();
    }

    @Test
    void doesNotDetectH2cUpgradeIntentWhenConnectionUpgradeTokenMissing() {
        List<HttpHeader> headers = List.of(
                new HttpHeader("Connection", "keep-alive"),
                new HttpHeader("Upgrade", "h2c"));

        assertThat(CommunityHttpRequestProcessor.isH2cUpgradeIntent(headers)).isFalse();
    }

    @Test
    void h2cUpgradeRequestSwitchesProtocolsAndProcessesBufferedHttp2Frames() {
        byte[] settings = emptySettingsFrame();
        byte[] hpackRequest = encodeHpackHeaders(List.of(
                new HttpHeader(":method", "GET"),
                new HttpHeader(":path", "/upgrade-demo"),
                new HttpHeader(":scheme", "http"),
                new HttpHeader(":authority", "localhost")
        ));
        byte[] headersFrame = headersFrame(3, hpackRequest, true, true);

        ByteArrayOutputStream inboundBytes = new ByteArrayOutputStream();
        inboundBytes.writeBytes(("""
            GET /upgrade HTTP/1.1\r
            Host: localhost\r
            Connection: keep-alive, Upgrade\r
            Upgrade: h2c\r
            \r
            """).getBytes(StandardCharsets.US_ASCII));
        inboundBytes.writeBytes(settings);
        inboundBytes.writeBytes(headersFrame);

        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                ALLOCATOR,
                HttpResponseBodyEncoderRegistry.empty(),
                HttpConfig.defaultServer());
        CapturingTransportStream stream = new CapturingTransportStream(inboundBytes.toByteArray());
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        processor.process(stream, exchange -> {
            handlerCalled.set(true);
            assertThat(exchange.request().version()).isEqualTo(HttpVersion.HTTP_2);
            assertThat(exchange.request().path()).isEqualTo("/upgrade-demo");
            exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
        });

        assertThat(handlerCalled.get()).isTrue();

        byte[] outbound = stream.responseBytes();
        int headerEnd = findHttp11HeadersEnd(outbound);
        assertThat(headerEnd).isGreaterThan(0);

        String http11Response = new String(outbound, 0, headerEnd, StandardCharsets.US_ASCII);
        assertThat(http11Response).startsWith("HTTP/1.1 101 Switching Protocols");
        assertThat(http11Response).contains("Connection: Upgrade");
        assertThat(http11Response).contains("Upgrade: h2c");

        List<FrameSnapshot> frames = decodeFramesFromOffset(outbound, headerEnd);
        assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
        assertThat(frames.get(0).header().frameType()).isEqualTo(Http2FrameType.SETTINGS);
        assertThat(frames.get(1).header().frameType()).isEqualTo(Http2FrameType.SETTINGS);
        assertThat(frames.get(1).header().isAck()).isTrue();
        assertThat(frames.get(2).header().frameType()).isEqualTo(Http2FrameType.HEADERS);
        assertThat(frames.get(2).header().streamId()).isEqualTo(3);
        assertThat(decodeStatusFromHeaderBlock(frames, 2)).isEqualTo("200");

        assertThat(stream.closed()).isTrue();
    }
        @Test
        void h2cOversizedFrameTerminatesFrameLoopWithGoAway() {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] settings = emptySettingsFrame();
            byte[] oversizedPayload = new byte[16385];
            byte[] oversizedFrame = buildFrame(Http2FrameType.HEADERS.code(), 0x05, 1, oversizedPayload);

            ByteArrayOutputStream inboundBytes = new ByteArrayOutputStream();
            inboundBytes.writeBytes(preface);
            inboundBytes.writeBytes(settings);
            inboundBytes.writeBytes(oversizedFrame);

            CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                    ALLOCATOR,
                    HttpResponseBodyEncoderRegistry.empty(),
                    HttpConfig.defaultServer());
            CapturingTransportStream stream = new CapturingTransportStream(inboundBytes.toByteArray());

            processor.process(stream, exchange -> {
                throw new AssertionError("Handler should not be called for oversized frame");
            });

            List<FrameSnapshot> frames = decodeFrames(stream.responseBytes());
            assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
            assertThat(frames.get(0).header().frameType()).isEqualTo(Http2FrameType.SETTINGS);
            assertThat(frames.get(1).header().frameType()).isEqualTo(Http2FrameType.SETTINGS);
            assertThat(frames.get(1).header().isAck()).isTrue();
            Http2FrameParser.FrameHeader goAway = frames.get(frames.size() - 1).header();
            assertThat(goAway.frameType()).isEqualTo(Http2FrameType.GOAWAY);
        }

        @Test
        void h2cHeadersFollowedByContinuationDeliversCompletedRequest() {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] settings = emptySettingsFrame();
            byte[] hpackBytes = encodeHpackHeaders(List.of(
                    new HttpHeader(":method", "GET"),
                    new HttpHeader(":path", "/multi-frame"),
                    new HttpHeader(":scheme", "http"),
                    new HttpHeader(":authority", "localhost")
            ));
            int split = hpackBytes.length / 2;
            byte[] firstHalf = new byte[split];
            byte[] secondHalf = new byte[hpackBytes.length - split];
            System.arraycopy(hpackBytes, 0, firstHalf, 0, split);
            System.arraycopy(hpackBytes, split, secondHalf, 0, secondHalf.length);

            byte[] headersFrame = headersFrame(1, firstHalf, false, true);
            byte[] continuationFrame = buildFrame(Http2FrameType.CONTINUATION.code(), 0x04, 1, secondHalf);

            ByteArrayOutputStream inboundBytes = new ByteArrayOutputStream();
            inboundBytes.writeBytes(preface);
            inboundBytes.writeBytes(settings);
            inboundBytes.writeBytes(headersFrame);
            inboundBytes.writeBytes(continuationFrame);

            CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                    ALLOCATOR,
                    HttpResponseBodyEncoderRegistry.empty(),
                    HttpConfig.defaultServer());
            CapturingTransportStream stream = new CapturingTransportStream(inboundBytes.toByteArray());
            AtomicBoolean handlerCalled = new AtomicBoolean(false);

            processor.process(stream, exchange -> {
                handlerCalled.set(true);
                assertThat(exchange.request().path()).isEqualTo("/multi-frame");
                assertThat(exchange.request().version()).isEqualTo(HttpVersion.HTTP_2);
                exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
            });

            assertThat(handlerCalled.get()).isTrue();
            List<FrameSnapshot> frames = decodeFrames(stream.responseBytes());
            assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
            assertThat(frames.get(0).header().frameType()).isEqualTo(Http2FrameType.SETTINGS);
            assertThat(frames.get(1).header().frameType()).isEqualTo(Http2FrameType.SETTINGS);
            assertThat(frames.get(1).header().isAck()).isTrue();
            assertThat(decodeStatusFromHeaderBlock(frames, 2)).isEqualTo("200");
        }

        @Test
        void h2cHeadersFrameWhileAwaitingContinuationSendsGoAway() {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] settings = emptySettingsFrame();
            byte[] hpackBytes = encodeHpackHeaders(List.of(
                    new HttpHeader(":method", "GET"),
                    new HttpHeader(":path", "/test"),
                    new HttpHeader(":scheme", "http"),
                    new HttpHeader(":authority", "localhost")
            ));
            int split = hpackBytes.length / 2;
            byte[] firstHalf = new byte[split];
            System.arraycopy(hpackBytes, 0, firstHalf, 0, split);

            byte[] partialHeadersFrame = headersFrame(1, firstHalf, false, true);
            byte[] intrudingHeadersFrame = headersFrame(3, new byte[]{(byte) 0x82}, true, true);

            ByteArrayOutputStream inboundBytes = new ByteArrayOutputStream();
            inboundBytes.writeBytes(preface);
            inboundBytes.writeBytes(settings);
            inboundBytes.writeBytes(partialHeadersFrame);
            inboundBytes.writeBytes(intrudingHeadersFrame);

            CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                    ALLOCATOR,
                    HttpResponseBodyEncoderRegistry.empty(),
                    HttpConfig.defaultServer());
            CapturingTransportStream stream = new CapturingTransportStream(inboundBytes.toByteArray());
            AtomicBoolean handlerCalled = new AtomicBoolean(false);

            processor.process(stream, exchange -> handlerCalled.set(true));

            assertThat(handlerCalled.get()).isFalse();
            List<FrameSnapshot> frames = decodeFrames(stream.responseBytes());
            assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
            Http2FrameParser.FrameHeader last = frames.get(frames.size() - 1).header();
            assertThat(last.frameType()).isEqualTo(Http2FrameType.GOAWAY);
        }

        @Test
        void h2cDataFrameWhileAwaitingContinuationSendsGoAway() {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] settings = emptySettingsFrame();
            byte[] hpackBytes = encodeHpackHeaders(List.of(
                    new HttpHeader(":method", "GET"),
                    new HttpHeader(":path", "/test"),
                    new HttpHeader(":scheme", "http"),
                    new HttpHeader(":authority", "localhost")
            ));
            int split = hpackBytes.length / 2;
            byte[] firstHalf = new byte[split];
            System.arraycopy(hpackBytes, 0, firstHalf, 0, split);

            byte[] partialHeadersFrame = headersFrame(1, firstHalf, false, true);
            byte[] intrudingDataFrame = dataFrame(1, new byte[]{0x41}, true);

            ByteArrayOutputStream inboundBytes = new ByteArrayOutputStream();
            inboundBytes.writeBytes(preface);
            inboundBytes.writeBytes(settings);
            inboundBytes.writeBytes(partialHeadersFrame);
            inboundBytes.writeBytes(intrudingDataFrame);

            CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                    ALLOCATOR,
                    HttpResponseBodyEncoderRegistry.empty(),
                    HttpConfig.defaultServer());
            CapturingTransportStream stream = new CapturingTransportStream(inboundBytes.toByteArray());
            AtomicBoolean handlerCalled = new AtomicBoolean(false);

            processor.process(stream, exchange -> handlerCalled.set(true));

            assertThat(handlerCalled.get()).isFalse();
            List<FrameSnapshot> frames = decodeFrames(stream.responseBytes());
            assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
            Http2FrameParser.FrameHeader last = frames.get(frames.size() - 1).header();
            assertThat(last.frameType()).isEqualTo(Http2FrameType.GOAWAY);
        }


    @Test
    void h2cPriorKnowledgePrefaceEmitsSettingsAckAndGoAway() {
        byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] emptyClientSettingsFrameHeader = new byte[]{0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};

        ByteArrayOutputStream inboundBytes = new ByteArrayOutputStream();
        inboundBytes.writeBytes(preface);
        inboundBytes.writeBytes(emptyClientSettingsFrameHeader);

        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                ALLOCATOR,
                HttpResponseBodyEncoderRegistry.empty(),
                HttpConfig.defaultServer());
        CapturingTransportStream stream = new CapturingTransportStream(inboundBytes.toByteArray());

        processor.process(stream, exchange -> {
            throw new AssertionError("Handler should not be called on unsupported h2c prior-knowledge path");
        });

        assertThat(stream.closed()).isTrue();

        byte[] outbound = stream.responseBytes();
        assertThat(outbound).hasSizeGreaterThanOrEqualTo(27);

        MemorySegment outboundSegment = MemorySegment.ofArray(outbound);
        Http2FrameParser.FrameHeader frame1 = Http2FrameParser.parseHeaderBigEndian(outboundSegment, 0);
        assertThat(frame1.frameType()).isEqualTo(Http2FrameType.SETTINGS);
        assertThat(frame1.streamId()).isZero();

        Http2FrameParser.FrameHeader frame2 = Http2FrameParser.parseHeaderBigEndian(outboundSegment, 9);
        assertThat(frame2.frameType()).isEqualTo(Http2FrameType.SETTINGS);
        assertThat(frame2.isAck()).isTrue();

        Http2FrameParser.FrameHeader frame3 = Http2FrameParser.parseHeaderBigEndian(outboundSegment, 18);
        assertThat(frame3.frameType()).isEqualTo(Http2FrameType.GOAWAY);
        assertThat(frame3.streamId()).isZero();
    }

    @Test
    void h2cPriorKnowledgePingEmitsPingAckBeforeGoAway() {
        byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] emptyClientSettingsFrameHeader = new byte[]{0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] pingHeader = new byte[]{0x00, 0x00, 0x08, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] pingPayload = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

        ByteArrayOutputStream inboundBytes = new ByteArrayOutputStream();
        inboundBytes.writeBytes(preface);
        inboundBytes.writeBytes(emptyClientSettingsFrameHeader);
        inboundBytes.writeBytes(pingHeader);
        inboundBytes.writeBytes(pingPayload);

        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                ALLOCATOR,
                HttpResponseBodyEncoderRegistry.empty(),
                HttpConfig.defaultServer());
        CapturingTransportStream stream = new CapturingTransportStream(inboundBytes.toByteArray());

        processor.process(stream, exchange -> {
            throw new AssertionError("Handler should not be called on unsupported h2c prior-knowledge path");
        });

        assertThat(stream.closed()).isTrue();

        byte[] outbound = stream.responseBytes();
        assertThat(outbound).hasSizeGreaterThanOrEqualTo(52);

        MemorySegment outboundSegment = MemorySegment.ofArray(outbound);
        Http2FrameParser.FrameHeader frame1 = Http2FrameParser.parseHeaderBigEndian(outboundSegment, 0);
        assertThat(frame1.frameType()).isEqualTo(Http2FrameType.SETTINGS);

        Http2FrameParser.FrameHeader frame2 = Http2FrameParser.parseHeaderBigEndian(outboundSegment, 9);
        assertThat(frame2.frameType()).isEqualTo(Http2FrameType.SETTINGS);
        assertThat(frame2.isAck()).isTrue();

        Http2FrameParser.FrameHeader frame3 = Http2FrameParser.parseHeaderBigEndian(outboundSegment, 18);
        assertThat(frame3.frameType()).isEqualTo(Http2FrameType.PING);
        assertThat(frame3.isAck()).isTrue();

        byte[] echoedPingPayload = new byte[8];
        MemorySegment.copy(outboundSegment, 27, MemorySegment.ofArray(echoedPingPayload), 0, 8);
        assertThat(echoedPingPayload).containsExactly(pingPayload);

        Http2FrameParser.FrameHeader frame4 = Http2FrameParser.parseHeaderBigEndian(outboundSegment, 35);
        assertThat(frame4.frameType()).isEqualTo(Http2FrameType.GOAWAY);
    }

    private static byte[] emptySettingsFrame() {
            return new byte[]{0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};
    }

    private static byte[] headersFrame(int streamId, byte[] payload, boolean endHeaders, boolean endStream) {
        int flags = 0;
        if (endHeaders) {
            flags |= 0x04;
        }
        if (endStream) {
            flags |= 0x01;
        }
        return buildFrame(Http2FrameType.HEADERS.code(), flags, streamId, payload);
    }

    private static byte[] dataFrame(int streamId, byte[] payload, boolean endStream) {
        int flags = endStream ? 0x01 : 0;
        return buildFrame(Http2FrameType.DATA.code(), flags, streamId, payload);
    }

    private static byte[] paddedDataFrame(int streamId, byte[] payload, int padLength, boolean endStream) {
        byte[] framePayload = new byte[1 + payload.length + padLength];
        framePayload[0] = (byte) padLength;
        System.arraycopy(payload, 0, framePayload, 1, payload.length);

        int flags = 0x08;
        if (endStream) {
            flags |= 0x01;
        }
        return buildFrame(Http2FrameType.DATA.code(), flags, streamId, framePayload);
    }

    private static byte[] goAwayFrame(int lastStreamId, int errorCode) {
        byte[] payload = new byte[8];
        payload[0] = (byte) ((lastStreamId >>> 24) & 0x7F);
        payload[1] = (byte) ((lastStreamId >>> 16) & 0xFF);
        payload[2] = (byte) ((lastStreamId >>> 8) & 0xFF);
        payload[3] = (byte) (lastStreamId & 0xFF);
        payload[4] = (byte) ((errorCode >>> 24) & 0xFF);
        payload[5] = (byte) ((errorCode >>> 16) & 0xFF);
        payload[6] = (byte) ((errorCode >>> 8) & 0xFF);
        payload[7] = (byte) (errorCode & 0xFF);
        return buildFrame(Http2FrameType.GOAWAY.code(), 0, 0, payload);
    }

    private static byte[] buildFrame(int type, int flags, int streamId, byte[] payload) {
        byte[] frame = new byte[9 + payload.length];
        int len = payload.length;
        frame[0] = (byte) ((len >>> 16) & 0xFF);
        frame[1] = (byte) ((len >>> 8) & 0xFF);
        frame[2] = (byte) (len & 0xFF);
        frame[3] = (byte) (type & 0xFF);
        frame[4] = (byte) (flags & 0xFF);
        frame[5] = (byte) ((streamId >>> 24) & 0x7F);
        frame[6] = (byte) ((streamId >>> 16) & 0xFF);
        frame[7] = (byte) ((streamId >>> 8) & 0xFF);
        frame[8] = (byte) (streamId & 0xFF);
        System.arraycopy(payload, 0, frame, 9, payload.length);
        return frame;
    }

    private static byte[] encodeHpackHeaders(List<HttpHeader> headers) {
        try (LoanedBuffer block = ALLOCATOR.allocateNetwork(4096)) {
            HpackEncoder encoder = new HpackEncoder(new HpackDynamicTable(4096), ALLOCATOR, false);
            long pos = 0;
            for (HttpHeader header : headers) {
                pos = encoder.encodeHeader(block.segment(), pos, header.name(), header.value(), false);
            }
            byte[] bytes = new byte[(int) pos];
            MemorySegment.copy(block.segment(), 0, MemorySegment.ofArray(bytes), 0, bytes.length);
            return bytes;
        }
    }

    private static List<FrameSnapshot> decodeFrames(byte[] bytes) {
        List<FrameSnapshot> frames = new ArrayList<>();
        MemorySegment segment = MemorySegment.ofArray(bytes);
        int offset = 0;
        while (offset + 9 <= bytes.length) {
            Http2FrameParser.FrameHeader header = Http2FrameParser.parseHeaderBigEndian(segment, offset);
            int payloadOffset = offset + 9;
            int frameEnd = payloadOffset + header.length();
            if (frameEnd > bytes.length) {
                break;
            }
            byte[] payload = new byte[header.length()];
            if (header.length() > 0) {
                MemorySegment.copy(segment, payloadOffset, MemorySegment.ofArray(payload), 0, header.length());
            }
            frames.add(new FrameSnapshot(header, payload));
            offset = frameEnd;
        }
        return frames;
    }

    private static List<FrameSnapshot> decodeFramesFromOffset(byte[] bytes, int offset) {
        byte[] frameBytes = new byte[bytes.length - offset];
        System.arraycopy(bytes, offset, frameBytes, 0, frameBytes.length);
        return decodeFrames(frameBytes);
    }

    private static int findHttp11HeadersEnd(byte[] bytes) {
        byte[] marker = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int index = 0; index <= bytes.length - marker.length; index++) {
            for (int markerIndex = 0; markerIndex < marker.length; markerIndex++) {
                if (bytes[index + markerIndex] != marker[markerIndex]) {
                    continue outer;
                }
            }
            return index + marker.length;
        }
        return -1;
    }

    private static String decodeStatusFromHeaderBlock(List<FrameSnapshot> frames, int headersFrameIndex) {
        FrameSnapshot head = frames.get(headersFrameIndex);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.writeBytes(head.payload());
        int idx = headersFrameIndex + 1;
        while (!frames.get(idx - 1).header().isEndHeaders()) {
            FrameSnapshot continuation = frames.get(idx);
            assertThat(continuation.header().frameType()).isEqualTo(Http2FrameType.CONTINUATION);
            encoded.writeBytes(continuation.payload());
            idx++;
        }

        byte[] headerBlock = encoded.toByteArray();
        HpackDecoder decoder = new HpackDecoder(new HpackDynamicTable(4096), ALLOCATOR, 65_536);
        String[] status = new String[1];
        decoder.decode(MemorySegment.ofArray(headerBlock), 0, headerBlock.length,
                (name, value, _) -> {
                    if (":status".equals(name)) {
                        status[0] = value;
                    }
                });
        return status[0];
    }

    private static int readErrorCode(byte[] payload) {
        return ((payload[0] & 0xFF) << 24)
                | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8)
                | (payload[3] & 0xFF);
    }

    private record FrameSnapshot(Http2FrameParser.FrameHeader header, byte[] payload) {
    }

    private static List<LogRecord> captureProcessorWarnings(Runnable operation) {
        Logger logger = Logger.getLogger(CommunityHttpRequestProcessor.class.getName());
        RecordingHandler handler = new RecordingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();

        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            operation.run();
            return handler.warningRecords();
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousUseParentHandlers);
            logger.setLevel(previousLevel);
        }
    }

    private static final class RecordingHandler extends Handler {

        private final List<LogRecord> warningRecords = new ArrayList<>();

        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord != null && logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                warningRecords.add(logRecord);
            }
        }

        @Override
        public void flush() {
            // No buffered resources to flush in this in-memory test handler.
        }

        @Override
        public void close() {
            // No external resources to close in this in-memory test handler.
        }

        List<LogRecord> warningRecords() {
            return List.copyOf(warningRecords);
        }
    }

    private static final class ThrowingTransportStream implements TransportStream {

        private final RuntimeException failure;

        private ThrowingTransportStream(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            throw failure;
        }

        @Override
        public void write(MemorySegment source, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long streamId() {
            return 0;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            // Intentionally a no-op for a synthetic throwing stream used in tests.
        }
    }

    private static final class CapturingTransportStream implements TransportStream {

        private final byte[] inbound;
        private int readOffset;
        private final ByteArrayOutputStream writes = new ByteArrayOutputStream();
        private boolean closed;

        private CapturingTransportStream(String requestText) {
            this(requestText.getBytes(StandardCharsets.US_ASCII));
        }

        private CapturingTransportStream(byte[] inbound) {
            this.inbound = inbound;
            this.readOffset = 0;
            this.closed = false;
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
            if (readOffset >= inbound.length) {
                return -1;
            }
            int remaining = inbound.length - readOffset;
            int toCopy = Math.min(maxBytes, remaining);
            MemorySegment.copy(MemorySegment.ofArray(inbound), readOffset, target, 0, toCopy);
            readOffset += toCopy;
            return toCopy;
        }

        @Override
        public void write(MemorySegment source, int length) {
            byte[] bytes = new byte[length];
            MemorySegment.copy(source, 0, MemorySegment.ofArray(bytes), 0, length);
            writes.writeBytes(bytes);
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long streamId() {
            return 1;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            closed = true;
        }

        private String responseText() {
            return new String(responseBytes(), StandardCharsets.US_ASCII);
        }

        private byte[] responseBytes() {
            return writes.toByteArray();
        }

        private boolean closed() {
            return closed;
        }
    }
}
