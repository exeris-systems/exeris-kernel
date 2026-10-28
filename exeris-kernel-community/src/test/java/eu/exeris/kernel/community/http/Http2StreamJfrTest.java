/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.http.Http2ScriptedTransport.Frame;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.core.http.http2.Http2Settings;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four stream JFR events of streams that share one HTTP/2 connection carry the connection's
 * transport stream id and, to tell the streams apart, each stream's protocol id; the park event also
 * says how much window there was.
 */
@DisplayName("HTTP/2 stream JFR events")
class Http2StreamJfrTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final String OPENED = "eu.exeris.kernel.http.StreamOpened";
    private static final String CLOSED = "eu.exeris.kernel.http.StreamClosed";
    private static final String PARK = "eu.exeris.kernel.http.StreamBackpressurePark";
    private static final long TRANSPORT_STREAM_ID = 7L;

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("two streams of one connection are told apart by the protocol stream id")
    void twoStreamsOnOneConnectionAreToldApartByProtocolStreamId() throws Exception {
        Path file = Files.createTempFile("h2-stream-jfr", ".jfr");
        Http2ScriptedTransport transport = new Http2ScriptedTransport();
        AtomicReference<Thread> parked = new AtomicReference<>();
        Routes routes = new Routes();
        routes.stream("/quiet", exchange -> { });
        routes.stream("/waits", exchange -> {
            parked.set(Thread.currentThread());
            exchange.emit(StreamEvent.of("needs credit"));
        });
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                ALLOCATOR, HttpResponseBodyEncoderRegistry.empty(), HttpConfig.defaultServer(),
                new CommunityHttpRequestDispatcher(ALLOCATOR, null, null, null, null),
                new CommunityHttpStreamDispatcher(ALLOCATOR));
        List<RecordedEvent> events;
        try (Recording recording = new Recording()) {
            recording.enable(OPENED).withoutStackTrace();
            recording.enable(CLOSED).withoutStackTrace();
            recording.enable(PARK).withoutStackTrace();
            recording.start();
            Thread loop = Thread.ofVirtual().start(() -> processor.process(transport, routes));
            transport.feed(Http2Frames.concat(
                    Http2Frames.PREFACE,
                    Http2Frames.settings(Http2Settings.ID_INITIAL_WINDOW_SIZE, 0),
                    Http2Frames.headers(1, get("/quiet"), true),
                    Http2Frames.headers(3, get("/quiet"), true),
                    Http2Frames.headers(5, get("/waits"), true)));
            Http2ScriptedTransport.awaitParkedInCredit(parked, BOUND);
            transport.feed(Http2Frames.windowUpdate(5, 100));
            transport.awaitFrame(f -> f.type() == Http2FrameType.DATA.code() && f.streamId() == 5
                    && f.isEndStream(), BOUND);
            transport.feedEof();
            loop.join(BOUND);
            recording.stop();
            recording.dump(file);
        }
        try {
            events = RecordingFile.readAllEvents(file).stream()
                    .filter(e -> Set.of(OPENED, CLOSED, PARK).contains(e.getEventType().getName()))
                    .filter(e -> e.getLong("streamId") == TRANSPORT_STREAM_ID).toList();
        } finally {
            Files.deleteIfExists(file);
        }

        assertThat(protocolIds(events, OPENED)).containsExactly(1, 3, 5);
        assertThat(protocolIds(events, CLOSED)).containsExactly(1, 3, 5);
        List<RecordedEvent> parks = events.stream().filter(e -> e.getEventType().getName().equals(PARK)).toList();
        assertThat(parks).hasSize(1);
        assertThat(parks.getFirst().getInt("protocolStreamId")).isEqualTo(5);
        assertThat(parks.getFirst().getLong("windowBytes")).as("no window was left at the park").isLessThanOrEqualTo(0L);
    }

    private static Set<Integer> protocolIds(List<RecordedEvent> events, String type) throws IOException {
        Set<Integer> ids = new TreeSet<>();
        for (RecordedEvent event : events) {
            if (event.getEventType().getName().equals(type)) {
                ids.add(event.getInt("protocolStreamId"));
            }
        }
        return ids;
    }

    private static byte[] get(String path) {
        return Http2Frames.requestBlock(ALLOCATOR, "GET", path);
    }

    private static final class Routes implements HttpHandler, StreamRouteResolver {
        private final Map<String, HttpStreamHandler> streams = new java.util.concurrent.ConcurrentHashMap<>();

        private void stream(String path, HttpStreamHandler handler) {
            streams.put(path, handler);
        }

        @Override
        public void handle(HttpExchange exchange) {
            exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            HttpStreamHandler handler = streams.get(path);
            return handler == null ? null : StreamMatch.exact(handler);
        }
    }
}
