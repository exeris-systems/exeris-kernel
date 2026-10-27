/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.http.Http2ScriptedTransport.Frame;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
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

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code h2c} upgrade end to end through the frame loop over a scripted transport: after the
 * {@code 101} the client sends the connection preface, its SETTINGS and then frames, the upgrade
 * request is answered on stream 1 once that SETTINGS is applied, and a client that does not follow
 * the sequence is refused.
 *
 * <p>Nothing here waits by time: every wait is a bound on an outbound frame the server writes.
 */
@DisplayName("h2c upgrade: the client connection preface and stream 1")
class Http2UpgradePrefaceTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final int HEADERS = Http2FrameType.HEADERS.code();
    private static final int SETTINGS = Http2FrameType.SETTINGS.code();
    private static final int GOAWAY = Http2FrameType.GOAWAY.code();
    private static final int PING = Http2FrameType.PING.code();
    private static final int ACK = 0x01;

    private static final byte[] UPGRADE_REQUEST = ascii(
            "GET /upgrade HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, Upgrade, HTTP2-Settings\r\n"
                    + "Upgrade: h2c\r\nHTTP2-Settings: AAMAAABkAARAAAAAAAIAAAAA\r\n\r\n");

    private final List<Thread> started = new ArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private LeakTrackingAllocator allocator;
    private Http2ScriptedTransport transport;
    private Thread processThread;

    private record Call(HttpMethod method, String path, HttpVersion version, List<HttpHeader> headers,
                        byte[] body) {
    }

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
    @DisplayName("the preface, SETTINGS and a request in one read answer stream 1 and then stream 3")
    void prefaceSettingsAndARequestInOneReadAnswerStreamOneThenStreamThree() {
        connect();
        transport.feed(Http2Frames.concat(
                UPGRADE_REQUEST, Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(3, get("/demo"), true)));

        transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 3 && f.isEndStream(), BOUND);

        assertThat(transport.upgradeHead()).startsWith("HTTP/1.1 101 Switching Protocols");
        List<Frame> frames = transport.seen();
        assertThat(frames).hasSize(4);
        assertThat(frames.get(0).type()).isEqualTo(SETTINGS);
        assertThat(frames.get(0).flags()).isZero();
        assertThat(frames.get(1).type()).isEqualTo(SETTINGS);
        assertThat(frames.get(1).flags()).isEqualTo(ACK);
        assertThat(frames.get(2).type()).isEqualTo(HEADERS);
        assertThat(frames.get(2).streamId()).isEqualTo(1);
        assertThat(frames.get(2).isEndStream()).isTrue();
        assertThat(frames.get(3).type()).isEqualTo(HEADERS);
        assertThat(frames.get(3).streamId()).isEqualTo(3);

        assertThat(calls).extracting(Call::path).containsExactly("/upgrade", "/demo");
        assertThat(calls).allSatisfy(call -> assertThat(call.version()).isEqualTo(HttpVersion.HTTP_2));
        assertThat(calls.getFirst().headers()).extracting(h -> h.name().toLowerCase(java.util.Locale.ROOT))
                .as("Host is kept; the connection-specific headers and HTTP2-Settings are not")
                .containsExactly("host");
    }

    @Test
    @DisplayName("a preface split across two reads is accepted")
    void aPrefaceSplitAcrossTwoReadsIsAccepted() {
        connect();
        byte[] first = Arrays.copyOfRange(Http2Frames.PREFACE, 0, 10);
        byte[] second = Arrays.copyOfRange(Http2Frames.PREFACE, 10, Http2Frames.PREFACE.length);
        transport.feed(Http2Frames.concat(UPGRADE_REQUEST, first));
        transport.feed(Http2Frames.concat(second, Http2Frames.emptySettings(),
                Http2Frames.headers(3, get("/demo"), true)));

        transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 3 && f.isEndStream(), BOUND);

        assertThat(calls).extracting(Call::path).containsExactly("/upgrade", "/demo");
        assertThat(transport.seen().stream().filter(f -> f.type() == GOAWAY)).isEmpty();
    }

    @Test
    @DisplayName("a SETTINGS frame in place of the preface is GOAWAY(PROTOCOL_ERROR) and the handler is never called")
    void settingsInPlaceOfThePrefaceIsAProtocolErrorAndTheHandlerIsNeverCalled() {
        connect();
        transport.feed(Http2Frames.concat(
                UPGRADE_REQUEST, Http2Frames.emptySettings(), Http2Frames.headers(3, get("/demo"), true)));

        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);

        assertGoAway(goAway, 0, Http2ErrorCode.PROTOCOL_ERROR);
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("a preface of the right length with a wrong byte is GOAWAY(PROTOCOL_ERROR), and the held body is released")
    void aWrongPrefaceIsAProtocolErrorAndTheHeldBodyIsReleased() throws InterruptedException {
        connect();
        byte[] wrongPreface = ascii("PRI * HTTP/2.0\r\n\r\nXX\r\n\r\n");
        transport.feed(Http2Frames.concat(
                ascii("POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n"
                        + "Content-Length: 5\r\n\r\nhello"),
                wrongPreface, Http2Frames.emptySettings()));

        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);
        transport.feedEof();
        processThread.join(BOUND);

        assertGoAway(goAway, 0, Http2ErrorCode.PROTOCOL_ERROR);
        assertThat(calls).isEmpty();
        assertThat(allocator.outstanding()).as("the request body held for stream 1 is closed").isZero();
    }

    @Test
    @DisplayName("end of stream part-way through the preface is GOAWAY(NO_ERROR), and the held body is released")
    void endOfStreamPartWayThroughThePrefaceIsANoErrorGoAwayAndTheHeldBodyIsReleased() throws InterruptedException {
        connect();
        byte[] partialPreface = Arrays.copyOf(Http2Frames.PREFACE, 10);
        transport.feed(Http2Frames.concat(
                ascii("POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n"
                        + "Content-Length: 5\r\n\r\nhello"),
                partialPreface));
        transport.feedEof();

        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);
        processThread.join(BOUND);

        assertGoAway(goAway, 0, Http2ErrorCode.NO_ERROR);
        assertThat(calls).isEmpty();
        assertThat(allocator.outstanding()).as("the request body held for stream 1 is closed").isZero();
    }

    @Test
    @DisplayName("a frame other than SETTINGS first is GOAWAY(PROTOCOL_ERROR) and stream 1 is not dispatched")
    void aFirstFrameThatIsNotSettingsIsAProtocolErrorAndStreamOneIsNotDispatched() {
        connect();
        transport.feed(Http2Frames.concat(
                UPGRADE_REQUEST, Http2Frames.PREFACE,
                Http2Frames.frame(PING, 0, 0, new byte[8]), Http2Frames.emptySettings()));

        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);

        assertGoAway(goAway, 0, Http2ErrorCode.PROTOCOL_ERROR);
        assertThat(calls).isEmpty();
        assertThat(transport.seen()).noneMatch(f -> f.type() == PING)
                .noneMatch(f -> f.type() == HEADERS);
    }

    @Test
    @DisplayName("the upgrade request's Content-Length body reaches the stream-1 handler exactly")
    void theUpgradeRequestBodyReachesTheStreamOneHandler() {
        connect();
        transport.feed(Http2Frames.concat(
                ascii("POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n"
                        + "Content-Length: 5\r\n\r\nhello"),
                Http2Frames.PREFACE, Http2Frames.emptySettings()));

        transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 1 && f.isEndStream(), BOUND);

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().method()).isEqualTo(HttpMethod.POST);
        assertThat(calls.getFirst().body()).isEqualTo(ascii("hello"));
    }

    @Test
    @DisplayName("HEADERS on stream 1 after a compliant upgrade is GOAWAY(PROTOCOL_ERROR)")
    void headersOnStreamOneAfterAnUpgradeIsAProtocolError() {
        connect();
        transport.feed(Http2Frames.concat(
                UPGRADE_REQUEST, Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/again"), true)));

        Frame goAway = transport.awaitFrame(f -> f.type() == GOAWAY, BOUND);

        assertGoAway(goAway, 1, Http2ErrorCode.PROTOCOL_ERROR);
        assertThat(calls).extracting(Call::path).containsExactly("/upgrade");
    }

    // ------------------------------------------------------------------ support

    private void connect() {
        allocator = new LeakTrackingAllocator(ALLOCATOR);
        transport = new Http2ScriptedTransport();
        transport.expectUpgradeResponse();
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                allocator, HttpResponseBodyEncoderRegistry.empty(), HttpConfig.defaultServer());
        processThread = Thread.ofVirtual().unstarted(() -> processor.process(transport, exchange -> {
            LoanedBuffer body = exchange.request().body();
            byte[] bytes = new byte[body == null ? 0 : (int) body.size()];
            if (body != null) {
                MemorySegment.copy(body.segment(), 0, MemorySegment.ofArray(bytes), 0, bytes.length);
            }
            calls.add(new Call(exchange.request().method(), exchange.request().path(),
                    exchange.request().version(), exchange.request().headers(), bytes));
            exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
        }));
        started.add(processThread);
        processThread.start();
    }

    private static void assertGoAway(Frame goAway, int lastStreamId, Http2ErrorCode code) {
        assertThat(Http2Frames.readInt32(goAway.payload(), 0)).as("last-stream-id").isEqualTo(lastStreamId);
        assertThat(Http2Frames.readInt32(goAway.payload(), 4)).as("error code").isEqualTo(code.code());
    }

    private static byte[] get(String path) {
        return Http2Frames.requestBlock(ALLOCATOR, "GET", path);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
