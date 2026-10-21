/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.http.Http2ScriptedTransport.Frame;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.core.http.http2.Http2Settings;
import eu.exeris.kernel.core.http.sse.SseEventEncoder;
import eu.exeris.kernel.core.http.sse.StreamAdmissionController;
import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stream route over HTTP/2, end to end through the frame loop over a scripted transport: what the
 * peer sees for the head, the events and the end, how the stream's task and its slot live and die, and
 * how a failure of one stream leaves the connection and its siblings alone.
 *
 * <p>Nothing here waits by time. A PING after a frame is answered by the frame loop after it has handled
 * that frame, so its ACK is the point after which the effect of the frame is a fact; what a stream's
 * thread does is awaited as a condition with a bound.
 */
@DisplayName("HTTP/2 stream routes")
class Http2StreamRouteTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final int DATA = Http2FrameType.DATA.code();
    private static final int HEADERS = Http2FrameType.HEADERS.code();
    private static final int RST_STREAM = Http2FrameType.RST_STREAM.code();
    private static final int GOAWAY = Http2FrameType.GOAWAY.code();
    private static final int PING = Http2FrameType.PING.code();
    private static final int ACK = 0x01;
    private static final int CAP = 100;

    private final List<Thread> started = new ArrayList<>();
    private final Routes routes = new Routes();
    private final HpackDecoder decoder =
            new HpackDecoder(new HpackDynamicTable(4096), ALLOCATOR, 65_536, 65_536);
    private final LeakTrackingAllocator streamAllocator = new LeakTrackingAllocator(ALLOCATOR);
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

    // ------------------------------------------------------------------ the wire

    @Test
    @DisplayName("over prior knowledge a stream route answers a head, one DATA frame per event, then END_STREAM")
    void streamRouteOverPriorKnowledgeAnswersAHeadThenOneDataFramePerEmit() {
        StreamEvent[] events = {StreamEvent.of("tick", "0"), StreamEvent.of("tick", "1"), StreamEvent.of("tick", "2")};
        routes.stream("/stream", exchange -> {
            for (StreamEvent event : events) {
                exchange.emit(event);
            }
        });
        Connection connection = connect();
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/stream"), true)));

        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);

        List<Frame> onStream = framesOn(1);
        assertThat(onStream).hasSize(1 + events.length + 1);
        Frame head = onStream.getFirst();
        assertThat(head.type()).isEqualTo(HEADERS);
        assertThat(head.flags()).as("END_HEADERS, and not END_STREAM").isEqualTo(0x04);
        assertThat(decode(head)).containsExactly(
                Map.entry(":status", "200"),
                Map.entry("content-type", "text/event-stream; charset=utf-8"),
                Map.entry("cache-control", "no-cache"));
        for (int i = 0; i < events.length; i++) {
            Frame frame = onStream.get(1 + i);
            assertThat(frame.type()).isEqualTo(DATA);
            assertThat(frame.isEndStream()).isFalse();
            assertThat(frame.payload()).isEqualTo(SseEventEncoder.encode(events[i]));
        }
        Frame end = onStream.getLast();
        assertThat(end.type()).isEqualTo(DATA);
        assertThat(end.payload()).isEmpty();
        assertThat(end.isEndStream()).isTrue();
        assertThat(routes.respondOnceCalls).as("the stream path never reaches the respond-once handler").isEmpty();
    }

    @Test
    @DisplayName("closing a stream ends that stream and leaves the connection serving")
    void closeEndsTheStreamNotTheConnection() {
        routes.stream("/stream", exchange -> exchange.emit(StreamEvent.of("only")));
        Connection connection = connect();
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/stream"), true)));
        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);

        connection.feed(Http2Frames.headers(3, get("/plain"), true));
        transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 3, BOUND);
        connection.barrier();

        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);
        assertThat(transport.isClosed()).as("the connection's transport is still open").isFalse();
        assertThat(routes.respondOnceCalls).containsExactly("/plain");
    }

    @Test
    @DisplayName("a peer reset makes the next emit throw EX-HTTP-4011 and leaves a sibling stream running")
    void peerResetMakesTheNextEmitThrowAndLeavesTheSiblingRunning() throws Exception {
        CountDownLatch firstEmitted = new CountDownLatch(2);
        CountDownLatch reset = new CountDownLatch(1);
        AtomicReference<Throwable> failureOnOne = new AtomicReference<>();
        routes.stream("/one", exchange -> {
            exchange.emit(StreamEvent.of("a"));
            firstEmitted.countDown();
            awaitUninterruptibly(reset);
            try {
                exchange.emit(StreamEvent.of("b"));
            } catch (StreamClosedException closed) {
                failureOnOne.set(closed);
                throw closed;
            }
        });
        routes.stream("/three", exchange -> {
            exchange.emit(StreamEvent.of("a"));
            firstEmitted.countDown();
            awaitUninterruptibly(reset);
            exchange.emit(StreamEvent.of("b"));
        });
        Connection connection = connect();
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/one"), true),
                Http2Frames.headers(3, get("/three"), true)));
        assertThat(firstEmitted.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();

        connection.feed(Http2Frames.rstStream(1, Http2ErrorCode.CANCEL.code()));
        connection.barrier();
        reset.countDown();

        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 3 && f.isEndStream(), BOUND);
        awaitCondition(() -> failureOnOne.get() != null, "stream 1's handler saw its stream end");
        assertThat(failureOnOne.get()).isInstanceOfSatisfying(StreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4011));
        assertThat(dataBytes(3)).as("the sibling sent both events")
                .isEqualTo(SseEventEncoder.encode(StreamEvent.of("a")).length
                        + SseEventEncoder.encode(StreamEvent.of("b")).length);
        assertThat(framesOn(1)).as("nothing is written to a stream the peer reset")
                .noneMatch(f -> f.type() == DATA && f.payload().length == 0)
                .filteredOn(f -> f.type() == DATA).hasSize(1);
    }

    @Test
    @DisplayName("an emit parks on the stream window and resumes on a WINDOW_UPDATE")
    void emitParksOnTheStreamWindowAndResumesOnWindowUpdate() {
        StreamEvent event = StreamEvent.of("x".repeat(32));
        int eventBytes = SseEventEncoder.encodedLength(event);
        AtomicReference<Thread> emitter = new AtomicReference<>();
        routes.stream("/stream", exchange -> {
            emitter.set(Thread.currentThread());
            for (int i = 0; i < 3; i++) {
                exchange.emit(event);
            }
        });
        Connection connection = connect();
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 64),
                Http2Frames.headers(1, get("/stream"), true)));

        awaitParkedInCredit(emitter);
        assertThat(dataBytes(1)).as("no more than the window is sent before the park").isEqualTo(64);

        connection.feed(Http2Frames.windowUpdate(1, 100));
        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);

        assertThat(dataBytes(1)).isEqualTo(3 * eventBytes);
        assertThat(reassembled(1)).startsWith(SseEventEncoder.encode(event));
    }

    // ------------------------------------------------------------------ slots

    @Test
    @DisplayName("open response streams count against the concurrent stream cap")
    void openResponseStreamsCountAgainstMaxConcurrentStreams() throws Exception {
        CountDownLatch headsWritten = new CountDownLatch(CAP);
        Semaphore finish = new Semaphore(0);
        routes.stream("/held", exchange -> {
            headsWritten.countDown();
            acquireInterruptibly(finish);
        });
        Connection connection = connect();
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        requests.writeBytes(Http2Frames.PREFACE);
        requests.writeBytes(Http2Frames.emptySettings());
        for (int i = 0; i < CAP; i++) {
            requests.writeBytes(Http2Frames.headers(2 * i + 1, get("/held"), true));
        }
        connection.feed(requests.toByteArray());
        assertThat(headsWritten.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();

        connection.feed(Http2Frames.headers(201, get("/held"), true));
        Frame refused = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 201, BOUND);
        assertThat(Http2Frames.readInt32(refused.payload(), 0)).isEqualTo(Http2ErrorCode.REFUSED_STREAM.code());

        finish.release();
        assertThat(offerUntilServed(connection, 203)).as("a finished stream frees its slot").isTrue();
        finish.release(CAP + 1);
    }

    @Test
    @DisplayName("a reset stream keeps its slot until its handler returns")
    void aResetStreamHoldsItsSlotUntilItsHandlerReturns() throws Exception {
        CountDownLatch headsWritten = new CountDownLatch(CAP);
        Semaphore proceed = new Semaphore(0);
        routes.stream("/held", exchange -> {
            headsWritten.countDown();
            acquireInterruptibly(proceed);
            exchange.emit(StreamEvent.of("after the reset"));
        });
        Connection connection = connect();
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        requests.writeBytes(Http2Frames.PREFACE);
        requests.writeBytes(Http2Frames.emptySettings());
        for (int i = 0; i < CAP; i++) {
            requests.writeBytes(Http2Frames.headers(2 * i + 1, get("/held"), true));
        }
        connection.feed(requests.toByteArray());
        assertThat(headsWritten.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
        ByteArrayOutputStream resets = new ByteArrayOutputStream();
        for (int i = 0; i < CAP; i++) {
            resets.writeBytes(Http2Frames.rstStream(2 * i + 1, Http2ErrorCode.CANCEL.code()));
        }
        connection.feed(resets.toByteArray());
        connection.barrier();

        connection.feed(Http2Frames.headers(201, get("/held"), true));
        Frame refused = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 201, BOUND);
        assertThat(Http2Frames.readInt32(refused.payload(), 0))
                .as("100 handlers still run, so no slot is free although every stream was reset")
                .isEqualTo(Http2ErrorCode.REFUSED_STREAM.code());

        proceed.release(CAP + 1);
        assertThat(offerUntilServed(connection, 203)).as("slots come back as the handlers return").isTrue();
    }

    // ------------------------------------------------------------------ deadline, denial, shed

    @Test
    @DisplayName("an auth deadline passing while an emit waits for credit resets the stream with CANCEL")
    void authExpiryWhileParkedResetsTheStreamWithCancel() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        routes.stream("/stream", exchange -> {
            try {
                exchange.emit(StreamEvent.of("never fits"));
            } catch (StreamClosedException expired) {
                failure.set(expired);
                throw expired;
            }
        });
        ToLongFunction<HttpRequest> deadline = request -> System.currentTimeMillis() + 300L;
        Connection connection = connect(
                new CommunityHttpStreamDispatcher(streamAllocator, null, deadline), defaultRequestDispatcher());
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0),
                Http2Frames.headers(1, get("/stream"), true)));

        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);

        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.CANCEL.code());
        awaitCondition(() -> failure.get() != null, "the handler saw the stream end");
        assertThat(failure.get()).isInstanceOfSatisfying(StreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4012));
    }

    @Test
    @DisplayName("a stream open that authorization denies is answered on the stream, and the handler never runs")
    void streamOpenDenialIsFramedOnTheStream() {
        AtomicBoolean handlerRan = new AtomicBoolean();
        routes.stream("/stream", exchange -> handlerRan.set(true));
        CommunityHttpRequestDispatcher requiringIdentity = new CommunityHttpRequestDispatcher(
                ALLOCATOR, null, null, null, (method, path) -> RouteRequirement.authenticated());
        Connection connection = connect(new CommunityHttpStreamDispatcher(streamAllocator), requiringIdentity);
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/stream"), true)));

        Frame denial = transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 1, BOUND);
        connection.barrier();

        assertThat(denial.isEndStream()).isTrue();
        assertThat(decode(denial)).contains(Map.entry(":status", "401"));
        assertThat(handlerRan).isFalse();
        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);
    }

    @Test
    @DisplayName("a stream open the admission controller sheds is refused, and the connection stays open")
    void shedStreamOpenIsRefusedStream() {
        AtomicBoolean handlerRan = new AtomicBoolean();
        routes.stream("/stream", exchange -> handlerRan.set(true));
        StreamAdmissionController shedding =
                new StreamAdmissionController(() -> ResourceArbiter.Action.SHED_LOAD, "h2-test");
        Connection connection = connect(
                new CommunityHttpStreamDispatcher(streamAllocator, shedding), defaultRequestDispatcher());
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/stream"), true)));

        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);
        connection.barrier();

        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.REFUSED_STREAM.code());
        assertThat(handlerRan).isFalse();
        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);
        assertThat(transport.isClosed()).isFalse();
    }

    // ------------------------------------------------------------------ the stream's thread

    @Test
    @DisplayName("the handler runs on a thread of its own that carries the connection's transport scopes")
    void streamHandlerRunsOnItsOwnThreadWithTheConnectionTransportScopes() throws Exception {
        AtomicReference<Thread> frameLoop = new AtomicReference<>();
        AtomicReference<String> observed = new AtomicReference<>();
        routes.stream("/stream", exchange -> observed.set(String.join(",",
                "id=" + (TransportScopes.STREAM_ID.isBound() ? TransportScopes.STREAM_ID.get() : "unbound"),
                "engine=" + (TransportScopes.ENGINE_NAME.isBound() ? TransportScopes.ENGINE_NAME.get() : "unbound"),
                "allocator=" + KernelProviders.MEMORY_ALLOCATOR.isBound(),
                "persistence=" + KernelProviders.PERSISTENCE_ENGINE.isBound(),
                "ownThread=" + (Thread.currentThread() != frameLoop.get()))));
        transport = new Http2ScriptedTransport();
        CommunityHttpRequestProcessor processor = processor(
                new CommunityHttpStreamDispatcher(streamAllocator), defaultRequestDispatcher());
        Thread thread = Thread.ofVirtual().unstarted(() -> ScopedValue
                .where(TransportScopes.STREAM_ID, 42L)
                .where(TransportScopes.ENGINE_NAME, "h2-test")
                .run(() -> {
                    frameLoop.set(Thread.currentThread());
                    processor.process(transport, routes);
                }));
        started.add(thread);
        thread.start();
        new Connection(thread).feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/stream"), true)));

        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1 && f.isEndStream(), BOUND);

        assertThat(observed.get())
                .isEqualTo("id=42,engine=h2-test,allocator=true,persistence=false,ownThread=true");
    }

    @Test
    @DisplayName("a handler that fails resets its stream with INTERNAL_ERROR and the connection serves on")
    void handlerFailureResetsTheStreamWithInternalError() {
        routes.stream("/stream", exchange -> {
            exchange.emit(StreamEvent.of("before the failure"));
            throw new IllegalStateException("handler defect");
        });
        Connection connection = connect();
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE, Http2Frames.emptySettings(),
                Http2Frames.headers(1, get("/stream"), true)));

        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);
        connection.feed(Http2Frames.headers(3, get("/plain"), true));
        transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 3, BOUND);

        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.INTERNAL_ERROR.code());
        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);
    }

    @Test
    @DisplayName("ending the connection tears down every open stream and joins its handler")
    void connectionEofTearsDownOpenStreamsAndJoinsHandlers() throws Exception {
        AtomicReference<Thread> parkedHandler = new AtomicReference<>();
        AtomicReference<Thread> waitingHandler = new AtomicReference<>();
        AtomicReference<Throwable> parkedFailure = new AtomicReference<>();
        AtomicBoolean waitingInterrupted = new AtomicBoolean();
        CountDownLatch never = new CountDownLatch(1);
        routes.stream("/parked", exchange -> {
            parkedHandler.set(Thread.currentThread());
            try {
                exchange.emit(StreamEvent.of("waits for credit"));
            } catch (StreamClosedException closed) {
                parkedFailure.set(closed);
                throw closed;
            }
        });
        routes.stream("/waiting", exchange -> {
            waitingHandler.set(Thread.currentThread());
            try {
                never.await();
            } catch (InterruptedException _) {
                waitingInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        Connection connection = connect();
        connection.feed(Http2Frames.concat(
                Http2Frames.PREFACE,
                Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0),
                Http2Frames.headers(1, get("/parked"), true),
                Http2Frames.headers(3, get("/waiting"), true)));
        awaitParkedInCredit(parkedHandler);
        awaitCondition(() -> waitingHandler.get() != null, "the second handler started");

        transport.feedEof();
        connection.processThread.join(BOUND);

        assertThat(connection.processThread.isAlive()).as("process returned").isFalse();
        assertThat(parkedHandler.get().isAlive()).as("no handler outlives the connection").isFalse();
        assertThat(waitingHandler.get().isAlive()).isFalse();
        assertThat(parkedFailure.get()).isInstanceOfSatisfying(StreamClosedException.class,
                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4011));
        assertThat(waitingInterrupted).as("a blocked handler is interrupted").isTrue();
        assertThat(streamAllocator.outstanding()).as("every event buffer was released").isZero();
    }

    @Test
    @DisplayName("an h2c upgrade followed by a request on a new stream resolves the stream route")
    void h2cUpgradeThenAStreamRequestOnANewStreamResolvesTheRoute() {
        StreamEvent event = StreamEvent.of("tick", "0");
        routes.stream("/stream", exchange -> exchange.emit(event));
        transport = new Http2ScriptedTransport();
        transport.expectUpgradeResponse();
        Connection connection = start(processor(
                new CommunityHttpStreamDispatcher(streamAllocator), defaultRequestDispatcher()));
        connection.feed(Http2Frames.concat(
                ("GET /upgrade HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, Upgrade\r\n"
                        + "Upgrade: h2c\r\n\r\n").getBytes(StandardCharsets.US_ASCII),
                Http2Frames.emptySettings(),
                Http2Frames.headers(3, get("/stream"), true)));

        transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 3 && f.isEndStream(), BOUND);

        assertThat(transport.upgradeHead()).startsWith("HTTP/1.1 101 Switching Protocols");
        List<Frame> onStream = framesOn(3);
        assertThat(decode(onStream.getFirst())).contains(Map.entry(":status", "200"),
                Map.entry("content-type", "text/event-stream; charset=utf-8"));
        assertThat(onStream.get(1).payload()).isEqualTo(SseEventEncoder.encode(event));
    }

    // ------------------------------------------------------------------ support

    private CommunityHttpRequestDispatcher defaultRequestDispatcher() {
        return new CommunityHttpRequestDispatcher(ALLOCATOR, null, null, null, null);
    }

    private Connection connect() {
        return connect(new CommunityHttpStreamDispatcher(streamAllocator), defaultRequestDispatcher());
    }

    private Connection connect(CommunityHttpStreamDispatcher streamDispatcher,
                               CommunityHttpRequestDispatcher requestDispatcher) {
        transport = new Http2ScriptedTransport();
        return start(processor(streamDispatcher, requestDispatcher));
    }

    private CommunityHttpRequestProcessor processor(CommunityHttpStreamDispatcher streamDispatcher,
                                                    CommunityHttpRequestDispatcher requestDispatcher) {
        return new CommunityHttpRequestProcessor(ALLOCATOR, HttpResponseBodyEncoderRegistry.empty(),
                HttpConfig.defaultServer(), requestDispatcher, streamDispatcher);
    }

    private Connection start(CommunityHttpRequestProcessor processor) {
        Thread thread = Thread.ofVirtual().unstarted(() -> processor.process(transport, routes));
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

    /**
     * Offers a stream on {@code firstId} and then each next odd id until one is answered with a head
     * rather than a refusal: the slot of a finished stream is freed just after its last frame is
     * written, so the offer is repeated rather than delayed.
     */
    private boolean offerUntilServed(Connection connection, int firstId) {
        for (int id = firstId; id < firstId + 2 * 100; id += 2) {
            int offered = id;
            connection.feed(Http2Frames.headers(offered, get("/held"), true));
            Frame answer = transport.awaitFrame(
                    f -> f.streamId() == offered && (f.type() == RST_STREAM || f.type() == HEADERS), BOUND);
            if (answer.type() == HEADERS) {
                return true;
            }
        }
        return false;
    }

    private List<Map.Entry<String, String>> decode(Frame head) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        MemorySegment block = MemorySegment.ofArray(head.payload());
        decoder.decode(block, 0, head.payload().length,
                (name, value, sensitive) -> fields.add(Map.entry(name, value)));
        return fields;
    }

    private List<Frame> framesOn(int streamId) {
        return transport.seen().stream().filter(f -> f.streamId() == streamId).toList();
    }

    private int dataBytes(int streamId) {
        return transport.seen().stream()
                .filter(f -> f.type() == DATA && f.streamId() == streamId)
                .mapToInt(f -> f.payload().length)
                .sum();
    }

    private byte[] reassembled(int streamId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transport.seen().stream()
                .filter(f -> f.type() == DATA && f.streamId() == streamId)
                .forEach(f -> out.writeBytes(f.payload()));
        return out.toByteArray();
    }

    private static byte[] get(String path) {
        return Http2Frames.requestBlock(ALLOCATOR, "GET", path);
    }

    private static void awaitParkedInCredit(AtomicReference<Thread> thread) {
        Http2ScriptedTransport.awaitParkedInCredit(thread, BOUND);
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, String what) {
        Http2ScriptedTransport.awaitCondition(condition, BOUND, what);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void acquireInterruptibly(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** The root handler: stream routes by exact path, and a respond-once answer for everything else. */
    private static final class Routes implements HttpHandler, StreamRouteResolver {
        private final Map<String, HttpStreamHandler> streams = new ConcurrentHashMap<>();
        private final List<String> respondOnceCalls = new CopyOnWriteArrayList<>();

        private void stream(String path, HttpStreamHandler handler) {
            streams.put(path, handler);
        }

        @Override
        public void handle(HttpExchange exchange) {
            String path = exchange.request().path();
            if (streams.containsKey(path)) {
                throw new AssertionError("the respond-once handler was called for the stream route " + path);
            }
            respondOnceCalls.add(path);
            exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            HttpStreamHandler handler = streams.get(path);
            return handler == null ? null : StreamMatch.exact(handler);
        }
    }
}
