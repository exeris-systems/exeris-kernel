/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.http.Http2ScriptedTransport.Frame;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that inserts an obs-text field value into the HPACK dynamic table.
 *
 * <p>The client's encoder sizes a table entry by the octets it sent. A server that sizes it by any
 * re-encoding of the decoded value evicts at a different point, and the next indexed reference then
 * names an entry the server no longer holds.
 */
@DisplayName("HTTP/2 obs-text field values through the frame loop")
class Http2HpackObsTextTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final int HEADERS = Http2FrameType.HEADERS.code();
    private static final int GOAWAY = Http2FrameType.GOAWAY.code();
    private static final int STATIC_AUTHORITY = 1;
    private static final int STATIC_METHOD_GET = 2;
    private static final int STATIC_PATH = 4;
    private static final int STATIC_SCHEME_HTTP = 6;
    private static final int TABLE_FIRST = 62;
    private static final int TABLE_SECOND = 63;
    private static final String OBS_TEXT_PATH = "/obs-text";
    private static final String FOLLOW_UP_PATH = "/follow-up";

    private final List<Thread> started = new ArrayList<>();
    private final List<String> servedPaths = new CopyOnWriteArrayList<>();
    private final List<String> servedFields = new CopyOnWriteArrayList<>();
    private LeakTrackingAllocator allocator;
    private Http2ScriptedTransport transport;
    private Thread processThread;
    private HpackDecoder responseDecoder;

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
    @DisplayName("a request that inserts an obs-text value is served with its octets, "
            + "and the next request is served from the entries before and after it")
    void obsTextValueIsServedAndTheEntriesAroundItSurvive() {
        connect();
        transport.feed(Http2Frames.concat(Http2Frames.PREFACE, Http2Frames.emptySettings()));

        byte[] obsOctets = new byte[2_100];
        java.util.Arrays.fill(obsOctets, (byte) 0xFF);
        String obsValue = "\u00ff".repeat(2_100);

        ByteArrayOutputStream first = new ByteArrayOutputStream();
        writePseudoHeaders(first, OBS_TEXT_PATH);
        writeLiteral(first, 0x40, "x-first", "one".getBytes(StandardCharsets.US_ASCII));
        writeLiteral(first, 0x40, "x-obs", obsOctets);
        transport.feed(Http2Frames.headers(1, first.toByteArray(), true));

        Frame served = transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 1, BOUND);
        assertThat(status(served)).isEqualTo("200");
        assertThat(servedPaths).containsExactly(OBS_TEXT_PATH);
        assertThat(servedFields).containsExactly("x-first: one", "x-obs: " + obsValue);

        ByteArrayOutputStream followUp = new ByteArrayOutputStream();
        writePseudoHeaders(followUp, FOLLOW_UP_PATH);
        followUp.write(0x80 | TABLE_SECOND);
        followUp.write(0x80 | TABLE_FIRST);
        writeLiteral(followUp, 0x00, "x-last", "1".getBytes(StandardCharsets.US_ASCII));
        transport.feed(Http2Frames.headers(3, followUp.toByteArray(), true));

        Frame answered = transport.awaitFrame(f -> f.type() == HEADERS && f.streamId() == 3, BOUND);
        assertThat(status(answered)).isEqualTo("200");
        assertThat(servedPaths).containsExactly(OBS_TEXT_PATH, FOLLOW_UP_PATH);
        assertThat(servedFields.subList(2, servedFields.size()))
                .containsExactly("x-first: one", "x-obs: " + obsValue, "x-last: 1");
        assertThat(transport.seen()).noneMatch(f -> f.type() == GOAWAY);

        transport.feedEof();
        join();
        assertThat(allocator.outstanding()).as("a served request releases everything it loaned").isZero();
    }

    // ------------------------------------------------------------------ support

    private static HttpConfig config() {
        HttpConfig base = HttpConfig.defaultServer();
        return new HttpConfig(HttpMode.SERVER, base.bindHost(), base.port(), base.maxConnections(),
                base.idleTimeoutMillis(), base.maxRequestHeaderCount(), base.maxRequestHeaderSize(),
                base.maxRequestBodyBytes(), true, HttpVersion.HTTP_2, null, 12_288, 16_384, 4_096);
    }

    private void connect() {
        allocator = new LeakTrackingAllocator(ALLOCATOR);
        transport = new Http2ScriptedTransport();
        responseDecoder = new HpackDecoder(new HpackDynamicTable(4096), ALLOCATOR, 65_536, 65_536);
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                allocator, HttpResponseBodyEncoderRegistry.empty(), config());
        processThread = Thread.ofVirtual().unstarted(() -> processor.process(transport, recording()));
        started.add(processThread);
        processThread.start();
    }

    /** Records the path and the regular fields of each request it serves, and answers 200. */
    private HttpHandler recording() {
        return exchange -> {
            servedPaths.add(exchange.request().path());
            exchange.request().headers().forEach(h -> servedFields.add(h.name() + ": " + h.value()));
            exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_2));
        };
    }

    private void join() {
        try {
            processThread.join(BOUND);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        assertThat(processThread.isAlive()).isFalse();
    }

    /** Decodes with the one decoder the connection's responses share, as a client's table does. */
    private String status(Frame head) {
        List<String> fields = new ArrayList<>();
        responseDecoder.decode(MemorySegment.ofArray(head.payload()), 0, head.payload().length,
                (name, value, sensitive) -> {
                    if (":status".equals(name)) {
                        fields.add(value);
                    }
                });
        assertThat(fields).hasSize(1);
        return fields.get(0);
    }

    /** RFC 7541 section 6.1 and 6.2.2: GET, http, a path and an authority, none Huffman-coded. */
    private static void writePseudoHeaders(ByteArrayOutputStream out, String path) {
        out.write(0x80 | STATIC_METHOD_GET);
        out.write(0x80 | STATIC_SCHEME_HTTP);
        out.write(STATIC_PATH);
        writeString(out, path);
        out.write(STATIC_AUTHORITY);
        writeString(out, "localhost");
    }

    /** A literal field with a literal name; {@code prefix} 0x00 is without indexing, 0x40 incremental. */
    private static void writeLiteral(ByteArrayOutputStream out, int prefix, String name, byte[] value) {
        out.write(prefix);
        writeString(out, name.getBytes(StandardCharsets.UTF_8));
        writeString(out, value);
    }

    /** RFC 7541 section 5.2, Huffman bit clear, with the length on a 7-bit prefix. */
    private static void writeString(ByteArrayOutputStream out, String text) {
        writeString(out, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeString(ByteArrayOutputStream out, byte[] bytes) {
        int remaining = bytes.length;
        if (remaining < 0x7F) {
            out.write(remaining);
        } else {
            out.write(0x7F);
            remaining -= 0x7F;
            while (remaining >= 0x80) {
                out.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            out.write(remaining);
        }
        out.writeBytes(bytes);
    }
}
