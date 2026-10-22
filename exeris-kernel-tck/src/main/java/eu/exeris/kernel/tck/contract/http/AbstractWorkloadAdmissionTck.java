/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: the obligations a server-side HTTP driver owes a {@link WorkloadAdmission} (the developer-licence
 * workload envelope).
 *
 * <h2>What the suite is built to catch</h2>
 * <p>A driver that never consults the gate looks, from outside, like a driver the gate lets through, so
 * every enforcement case here has a counterpart that proves the same load is served untouched when the
 * gate is {@link WorkloadAdmission#UNGATED}, not enforced, or not bound at all. A driver that reads the
 * slot on a request or accept thread, where no binding is visible, fails the enforcement cases and passes
 * the controls, which is the fail-open shape the slot's contract names. The suite binds a gate of its own
 * with {@link ScopedValue} around {@link #startServer}, so it needs no signed manifest and no trusted key.
 *
 * <h2>What the suite pins</h2>
 * <ul>
 *   <li><b>Marker.</b> Every final response, whatever produced it, carries exactly one
 *       {@code Exeris-License: development}: an application 200 and 404, a response the kernel wrote
 *       itself for a throwing handler, the refusal, the head of a server-sent-events stream, and an
 *       HTTP/2 response. A handler that sets the header under any spelling cannot change it.</li>
 *   <li><b>Requests.</b> Past the ceiling a request is answered {@code 429} with {@code Retry-After: 1}
 *       and no body and never reaches the handler; HTTP/1.1 keeps the connection open and HTTP/2 answers
 *       with HEADERS ending the stream, not RST_STREAM.</li>
 *   <li><b>Connections.</b> Past the ceiling an accepted connection is closed before a byte is written, a
 *       closed connection frees its place, and every admission is released exactly once.</li>
 *   <li><b>Direction controls.</b> With the gate unenforced or unbound the same load meets no header, no
 *       {@code 429}, no refused connection and no admission call.</li>
 * </ul>
 *
 * <h2>Binding</h2>
 * <p>A binding starts a plaintext server engine through {@link #startServer}, which the suite calls
 * inside the scope it binds the gate in. The server must accept cleartext HTTP/2 with prior knowledge,
 * unless {@link #supportsCleartextHttp2()} says otherwise, and must resolve stream routes for a handler
 * that implements {@link StreamRouteResolver}.
 *
 * @since 0.13
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public abstract class AbstractWorkloadAdmissionTck {

    /**
     * A started server a case drives.
     *
     * @param engine the started server engine; closed by the suite
     * @param host   an IP address literal the server accepts on
     * @param port   the port the server accepts on
     */
    public record StartedServer(HttpServerEngine engine, String host, int port) implements AutoCloseable {

        @Override
        public void close() {
            engine.close();
        }
    }

    private static final String PATH_SPOOF = "/spoof";
    private static final String PATH_SPOOF_LOWER = "/spoof-lower";
    private static final String PATH_SPOOF_SPACE = "/spoof-space";
    private static final String PATH_EVENTS = "/events";
    private static final String RETRY_AFTER = "retry-after";
    private static final String MARKER = WorkloadAdmission.LICENCE_HEADER.toLowerCase(Locale.ROOT);
    private static final String MARKER_VALUE = WorkloadAdmission.DEVELOPMENT_MARKER;
    private static final int CONNECT_LIMIT_MILLIS = 5_000;
    private static final int READ_LIMIT_MILLIS = 10_000;
    private static final long SETTLE_LIMIT_MILLIS = 10_000L;
    private static final int STREAM_FIRST = 1;
    private static final int STREAM_SECOND = 3;
    private static final int STREAM_THIRD = 5;

    private static final byte[] CLIENT_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FRAME_HEADER_BYTES = 9;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int TYPE_CONTINUATION = 0x9;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_ACK = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int STATIC_AUTHORITY = 1;
    private static final int STATIC_METHOD_GET = 2;
    private static final int STATIC_PATH = 4;
    private static final int STATIC_SCHEME_HTTP = 6;

    private final List<StartedServer> servers = new ArrayList<>();

    /** Constructor for subclasses. */
    protected AbstractWorkloadAdmissionTck() {
        // binding classes start the server engine under test
    }

    /**
     * Starts a plaintext server engine that hands every request to {@code handler}.
     *
     * @param handler the handler the engine dispatches to
     * @return a started server; the suite closes it
     * @implSpec The engine is started inside this call, because the suite binds the
     *           {@link ContractKernelProviders#WORKLOAD_ADMISSION} slot around it, and the engine reads
     *           the slot in {@code start()}. A binding that defers {@code start()} to another thread
     *           or another moment defeats that, and the enforcement cases fail.
     */
    protected abstract StartedServer startServer(HttpHandler handler);

    /**
     * Returns the kernel's router with no route registered, as the handler of a server.
     *
     * @return a handler that answers every request with the kernel's own routing miss, a {@code 404}
     *         the application did not write
     * @implSpec In the Community binding this is the Core {@code HttpRouter} built with no route.
     */
    protected abstract HttpHandler emptyRouter();

    /**
     * Whether the server accepts cleartext HTTP/2 with prior knowledge (RFC 9113 §3.3).
     *
     * @return {@code true} by default; an override returns {@code false} only for a server that cannot
     *         serve cleartext HTTP/2, and the HTTP/2 cases are then reported as skipped
     */
    protected boolean supportsCleartextHttp2() {
        return true;
    }

    @AfterEach
    final void closeServers() {
        servers.forEach(StartedServer::close);
        servers.clear();
    }

    private void requireHttp2() {
        assumeTrue(supportsCleartextHttp2(), "this binding does not serve cleartext HTTP/2");
    }

    // =========================================================================
    // Marker
    // =========================================================================

    @Nested
    @DisplayName("(a) the Exeris-License marker on every final response")
    class Marker {

        @Test
        @DisplayName("an application 200 carries exactly one marker")
        void applicationOk() throws IOException {
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), new Probe());
            try (Socket client = connect(server)) {
                Http1Reply reply = get(client, "/ok");

                assertThat(reply.status()).isEqualTo(200);
                assertMarkedOnce(reply.fields(), "an application 200");
            }
        }

        @Test
        @DisplayName("a 404 from the kernel's own routing miss carries the marker")
        void routingMiss() throws IOException {
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), emptyRouter());
            try (Socket client = connect(server)) {
                Http1Reply reply = get(client, "/nothing-is-routed-here");

                assertThat(reply.status()).isEqualTo(404);
                assertMarkedOnce(reply.fields(), "a kernel routing miss");
            }
        }

        @Test
        @DisplayName("an application 404 carries the marker")
        void applicationNotFound() throws IOException {
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), new Probe());
            try (Socket client = connect(server)) {
                Http1Reply reply = get(client, "/missing");

                assertThat(reply.status()).isEqualTo(404);
                assertMarkedOnce(reply.fields(), "an application 404");
            }
        }

        @Test
        @DisplayName("a response the kernel writes for a throwing handler carries the marker")
        void kernelGeneratedServerError() throws IOException {
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), new Probe());
            try (Socket client = connect(server)) {
                Http1Reply reply = get(client, "/boom");

                assertThat(reply.status()).isEqualTo(500);
                assertMarkedOnce(reply.fields(), "a kernel-generated 500");
            }
        }

        @Test
        @DisplayName("a handler that sets the header, under either spelling, cannot change it")
        void handlerCannotOverrideTheMarker() throws IOException {
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), new Probe());
            try (Socket client = connect(server)) {
                assertMarkedOnce(get(client, PATH_SPOOF).fields(), "a handler that sets Exeris-License");
                assertMarkedOnce(get(client, PATH_SPOOF_LOWER).fields(), "a handler that sets exeris-license");
                assertMarkedOnce(get(client, PATH_SPOOF_SPACE).fields(),
                        "a handler that sets a name with a trailing space, which an intermediary strips");
            }
        }

        @Test
        @DisplayName("the head of a server-sent-events stream carries the marker")
        void streamHeadCarriesTheMarker() throws IOException {
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), new Probe());
            try (Socket client = connect(server)) {
                Http1Reply head = getStreamHead(client, PATH_EVENTS);

                assertThat(head.status()).isEqualTo(200);
                assertMarkedOnce(head.fields(), "a stream head");
            }
        }

        @Test
        @DisplayName("an HTTP/2 response carries exactly one marker, and a handler cannot change it")
        void http2Response() throws IOException {
            requireHttp2();
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), new Probe());
            try (RawH2 client = RawH2.open(server)) {
                client.request(STREAM_FIRST, "/ok");
                client.request(STREAM_SECOND, PATH_SPOOF);
                client.request(STREAM_THIRD, PATH_SPOOF_SPACE);

                H2Reply ok = client.reply(STREAM_FIRST);
                H2Reply spoof = client.reply(STREAM_SECOND);
                H2Reply spaced = client.reply(STREAM_THIRD);

                assertThat(ok.status()).isEqualTo(200);
                assertMarkedOnce(ok.fields(), "an HTTP/2 200");
                assertThat(spoof.status()).isEqualTo(200);
                assertMarkedOnce(spoof.fields(), "an HTTP/2 response to a handler that sets Exeris-License");
                assertThat(spaced.status()).isEqualTo(200);
                assertMarkedOnce(spaced.fields(),
                        "an HTTP/2 response to a handler that sets a name with a trailing space");
            }
        }

        @Test
        @DisplayName("the head of an HTTP/2 stream route carries exactly one marker")
        void http2StreamHeadCarriesTheMarker() throws IOException {
            requireHttp2();
            StartedServer server = start(new GateProbe(true, 1_000, 1_000), new Probe());
            try (RawH2 client = RawH2.open(server)) {
                client.request(STREAM_FIRST, PATH_EVENTS);

                H2Reply head = client.reply(STREAM_FIRST);

                assertThat(head.status()).isEqualTo(200);
                assertThat(head.reset()).isFalse();
                assertMarkedOnce(head.fields(), "an HTTP/2 stream-route head");
            }
        }
    }

    // =========================================================================
    // Requests
    // =========================================================================

    @Nested
    @DisplayName("(b) the request ceiling, answered 429 before the handler")
    class Requests {

        @Test
        @DisplayName("HTTP/1.1: past the ceiling a request gets 429 with Retry-After: 1 and never reaches the handler")
        void http1Throttle() throws IOException {
            GateProbe gate = new GateProbe(true, 3, 1_000);
            Probe probe = new Probe();
            StartedServer server = start(gate, probe);
            try (Socket client = connect(server)) {
                for (int i = 1; i <= 3; i++) {
                    assertThat(get(client, "/ok").status()).as("request %d is under the ceiling", i).isEqualTo(200);
                }

                Http1Reply fourth = get(client, "/ok");
                Http1Reply fifth = get(client, "/ok");

                assertThat(fourth.status()).as("the request past the ceiling").isEqualTo(429);
                assertThat(fourth.first(RETRY_AFTER)).as("Retry-After").isEqualTo("1");
                assertThat(fourth.contentLength()).as("the refusal has no body").isZero();
                assertMarkedOnce(fourth.fields(), "the 429");
                assertThat(fifth.status())
                        .as("the connection stays open after a 429, so the next request is answered too")
                        .isEqualTo(429);
                assertThat(probe.handled()).as("handler invocations: only the admitted requests").isEqualTo(3);
            }
        }

        @Test
        @DisplayName("HTTP/1.1: a stream route is gated before it is resolved")
        void http1StreamRouteIsGated() throws IOException {
            GateProbe gate = new GateProbe(true, 0, 1_000);
            Probe probe = new Probe();
            StartedServer server = start(gate, probe);
            try (Socket client = connect(server)) {
                Http1Reply reply = get(client, PATH_EVENTS);

                assertThat(reply.status()).isEqualTo(429);
                assertThat(probe.handled()).as("the stream handler never ran").isZero();
            }
        }

        @Test
        @DisplayName("HTTP/2: past the ceiling a stream gets HEADERS ending the stream, not RST_STREAM")
        void http2Throttle() throws IOException {
            requireHttp2();
            GateProbe gate = new GateProbe(true, 2, 1_000);
            Probe probe = new Probe();
            StartedServer server = start(gate, probe);
            try (RawH2 client = RawH2.open(server)) {
                client.request(STREAM_FIRST, "/ok");
                client.request(STREAM_SECOND, "/ok");
                client.request(STREAM_THIRD, "/ok");

                assertThat(client.reply(STREAM_FIRST).status()).isEqualTo(200);
                assertThat(client.reply(STREAM_SECOND).status()).isEqualTo(200);
                H2Reply refused = client.reply(STREAM_THIRD);

                assertThat(refused.reset()).as("the refusal is a response, not RST_STREAM").isFalse();
                assertThat(refused.status()).isEqualTo(429);
                assertThat(refused.headersEndStream()).as("HEADERS carries END_STREAM").isTrue();
                assertThat(refused.first(RETRY_AFTER)).isEqualTo("1");
                assertMarkedOnce(refused.fields(), "the HTTP/2 429");
                assertThat(probe.handled()).as("handler invocations: only the admitted streams").isEqualTo(2);
            }
        }

        @Test
        @DisplayName("HTTP/2: a stream route is gated before it is resolved, so no handler is forked")
        void http2StreamRouteIsGated() throws IOException {
            requireHttp2();
            GateProbe gate = new GateProbe(true, 0, 1_000);
            Probe probe = new Probe();
            StartedServer server = start(gate, probe);
            try (RawH2 client = RawH2.open(server)) {
                client.request(STREAM_FIRST, PATH_EVENTS);

                H2Reply refused = client.reply(STREAM_FIRST);

                assertThat(refused.reset()).as("the refusal is a response, not RST_STREAM").isFalse();
                assertThat(refused.status()).isEqualTo(429);
                assertThat(refused.headersEndStream()).as("HEADERS carries END_STREAM").isTrue();
                assertThat(refused.first(RETRY_AFTER)).isEqualTo("1");
                assertMarkedOnce(refused.fields(), "the HTTP/2 stream-route 429");
                assertThat(probe.handled()).as("the stream handler never ran").isZero();
                assertThat(gate.requestCalls()).as("the gate was asked once, before resolution").isEqualTo(1);
            }
        }
    }

    // =========================================================================
    // Connections
    // =========================================================================

    @Nested
    @DisplayName("(c) the connection ceiling, enforced at accept")
    class Connections {

        @Test
        @DisplayName("past the ceiling a connection is closed with no byte read, a close frees its place, "
                + "and every admission is released once")
        void connectionCeiling() throws IOException, InterruptedException {
            GateProbe gate = new GateProbe(true, 1_000, 2);
            Probe probe = new Probe();
            StartedServer server = start(gate, probe);

            Socket first = connect(server);
            Socket second = connect(server);
            try {
                assertThat(get(first, "/ok").status()).isEqualTo(200);
                assertThat(get(second, "/ok").status()).isEqualTo(200);

                try (Socket third = connect(server)) {
                    assertClosedWithNoBytes(third);
                }
                assertThat(probe.handled()).as("the refused connection reached no handler").isEqualTo(2);

                first.close();
                awaitLive(gate, 1);
                try (Socket fourth = connect(server)) {
                    assertThat(get(fourth, "/ok").status())
                            .as("a connection opened after one closed is served")
                            .isEqualTo(200);
                }
            } finally {
                first.close();
                second.close();
            }

            awaitLive(gate, 0);
            assertThat(gate.admittedConnections()).as("connections admitted: first, second, fourth").isEqualTo(3);
            assertThat(gate.releasedConnections())
                    .as("every admitted connection is released exactly once, never the refused one")
                    .isEqualTo(gate.admittedConnections());
        }
    }

    // =========================================================================
    // Direction controls
    // =========================================================================

    @Nested
    @DisplayName("(d) the same load, ungated, meets no header, no 429, no refusal")
    class Controls {

        @Test
        @DisplayName("UNGATED bound: the load of the enforcement cases is served untouched")
        void ungatedBound() throws IOException, InterruptedException {
            assertServedUntouched(start(WorkloadAdmission.UNGATED, new Probe()));
        }

        @Test
        @DisplayName("a gate that reports enforced() == false is never consulted")
        void unenforcedGateIsNeverConsulted() throws IOException, InterruptedException {
            GateProbe gate = new GateProbe(false, 0, 0);
            StartedServer server = start(gate, new Probe());

            assertServedUntouched(server);

            assertThat(gate.requestCalls()).as("tryAdmitRequest calls").isZero();
            assertThat(gate.connectionCalls()).as("tryAdmitConnection calls").isZero();
            assertThat(gate.releasedConnections()).as("connectionClosed calls").isZero();
        }

        @Test
        @DisplayName("no binding at all behaves as UNGATED")
        void unbound() throws IOException, InterruptedException {
            assertServedUntouched(start(null, new Probe()));
        }

        private void assertServedUntouched(StartedServer server) throws IOException {
            try (Socket client = connect(server)) {
                for (int i = 1; i <= 10; i++) {
                    Http1Reply reply = get(client, "/ok");
                    assertThat(reply.status()).as("HTTP/1.1 request %d", i).isEqualTo(200);
                    assertNotMarked(reply.fields(), "HTTP/1.1 request " + i);
                }
                assertThat(get(client, PATH_SPOOF).fields())
                        .as("an application header of that name is the application's own while the gate is not "
                                + "enforced")
                        .filteredOn(field -> MARKER.equals(field.name()))
                        .extracting(HpackFieldReader.Field::value)
                        .containsExactly("production");
                assertNotMarked(getStreamHead(connect(server), PATH_EVENTS).fields(), "a stream head");
            }
            List<Socket> many = new ArrayList<>();
            try {
                for (int i = 0; i < 5; i++) {
                    many.add(connect(server));
                }
                for (Socket socket : many) {
                    assertThat(get(socket, "/ok").status()).as("a connection among five at once").isEqualTo(200);
                }
            } finally {
                for (Socket socket : many) {
                    socket.close();
                }
            }
            if (supportsCleartextHttp2()) {
                try (RawH2 client = RawH2.open(server)) {
                    for (int stream = STREAM_FIRST; stream <= 9; stream += 2) {
                        client.request(stream, "/ok");
                    }
                    for (int stream = STREAM_FIRST; stream <= 9; stream += 2) {
                        H2Reply reply = client.reply(stream);
                        assertThat(reply.status()).as("HTTP/2 stream %d", stream).isEqualTo(200);
                        assertNotMarked(reply.fields(), "HTTP/2 stream " + stream);
                    }
                }
            }
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private StartedServer start(WorkloadAdmission gate, HttpHandler handler) {
        StartedServer[] holder = new StartedServer[1];
        if (gate == null) {
            holder[0] = startServer(handler);
        } else {
            ScopedValue.where(ContractKernelProviders.WORKLOAD_ADMISSION, gate)
                    .run(() -> holder[0] = startServer(handler));
        }
        servers.add(holder[0]);
        return holder[0];
    }

    private static Socket connect(StartedServer server) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(server.host(), server.port()), CONNECT_LIMIT_MILLIS);
            socket.setSoTimeout(READ_LIMIT_MILLIS);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private static void awaitLive(GateProbe gate, int expected) throws InterruptedException {
        assertThat(gate.live.await(count -> count == expected, SETTLE_LIMIT_MILLIS))
                .as("admitted connections still held: %d, expected %d", gate.liveConnections(), expected)
                .isTrue();
    }

    private static void assertClosedWithNoBytes(Socket socket) throws IOException {
        assertThat(readOrClosed(socket))
                .as("a refused connection is closed before the server writes a byte")
                .isEqualTo(-1);
    }

    private static int readOrClosed(Socket socket) throws IOException {
        try {
            return socket.getInputStream().read();
        } catch (SocketException _) {
            // A reset is a close with no bytes either; a read that timed out is not caught here and fails the case.
            return -1;
        }
    }

    private static void assertMarkedOnce(List<HpackFieldReader.Field> fields, String what) {
        List<String> values = fields.stream()
                .filter(field -> MARKER.equals(field.name().strip().toLowerCase(Locale.ROOT)))
                .map(HpackFieldReader.Field::value)
                .toList();
        assertThat(values).as("%s: the %s header values", what, WorkloadAdmission.LICENCE_HEADER)
                .containsExactly(MARKER_VALUE);
    }

    private static void assertNotMarked(List<HpackFieldReader.Field> fields, String what) {
        assertThat(fields).as("%s: no %s header when the gate is not enforced", what, WorkloadAdmission.LICENCE_HEADER)
                .noneMatch(field -> MARKER.equals(field.name().strip().toLowerCase(Locale.ROOT)));
    }

    // -- the handler --------------------------------------------------------

    /** Answers by path and counts every request or stream that reaches it. */
    private static final class Probe implements HttpHandler, StreamRouteResolver {

        private final AtomicInteger handled = new AtomicInteger();

        @Override
        public void handle(HttpExchange exchange) {
            handled.incrementAndGet();
            switch (exchange.request().path()) {
                case "/missing" -> exchange.respond(HttpStatus.NOT_FOUND);
                case "/boom" -> throw new IllegalStateException("handler failure under test");
                case PATH_SPOOF -> respondWithSpoofedMarker(exchange, "Exeris-License");
                case PATH_SPOOF_LOWER -> respondWithSpoofedMarker(exchange, "exeris-license");
                case PATH_SPOOF_SPACE -> respondWithSpoofedMarker(exchange, "Exeris-License ");
                default -> exchange.respond(HttpStatus.OK);
            }
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            if (method == HttpMethod.GET && PATH_EVENTS.equals(path)) {
                return StreamMatch.exact(exchange -> {
                    handled.incrementAndGet();
                    exchange.emit(StreamEvent.of("one"));
                });
            }
            return null;
        }

        int handled() {
            return handled.get();
        }

        private static void respondWithSpoofedMarker(HttpExchange exchange, String name) {
            exchange.respond(HttpResponse.noBody(
                    HttpStatus.OK, exchange.request().version(), List.of(new HttpHeader(name, "production"))));
        }
    }

    /**
     * A count a case can wait on: every change signals the waiters, so a wait ends on the event that
     * satisfies it, or on its timeout, and never re-checks on a timer.
     */
    private static final class AwaitableCount {

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private int value;

        int get() {
            lock.lock();
            try {
                return value;
            } finally {
                lock.unlock();
            }
        }

        int increment() {
            return add(1);
        }

        int decrement() {
            return add(-1);
        }

        /** Takes one place if the count is below {@code ceiling}. */
        boolean incrementBelow(int ceiling) {
            lock.lock();
            try {
                if (value >= ceiling) {
                    return false;
                }
                value++;
                changed.signalAll();
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** Waits until {@code condition} holds for the count; returns whether it did within the timeout. */
        boolean await(IntPredicate condition, long timeoutMillis) throws InterruptedException {
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            lock.lock();
            try {
                while (!condition.test(value)) {
                    if (remaining <= 0) {
                        return false;
                    }
                    remaining = changed.awaitNanos(remaining);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        private int add(int delta) {
            lock.lock();
            try {
                value += delta;
                changed.signalAll();
                return value;
            } finally {
                lock.unlock();
            }
        }
    }

    // -- the gate -----------------------------------------------------------

    /**
     * A gate of the suite's own: a ceiling on the number of requests ever offered, and a bounded count
     * of live connections, with every call counted.
     */
    private static final class GateProbe implements WorkloadAdmission {

        private final boolean enforced;
        private final int requestCeiling;
        private final int connectionCeiling;
        private final AtomicInteger requestCalls = new AtomicInteger();
        private final AtomicInteger connectionCalls = new AtomicInteger();
        private final AwaitableCount live = new AwaitableCount();
        private final AtomicInteger admitted = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();

        GateProbe(boolean enforced, int requestCeiling, int connectionCeiling) {
            this.enforced = enforced;
            this.requestCeiling = requestCeiling;
            this.connectionCeiling = connectionCeiling;
        }

        @Override
        public boolean enforced() {
            return enforced;
        }

        @Override
        public boolean tryAdmitConnection() {
            connectionCalls.incrementAndGet();
            if (!live.incrementBelow(connectionCeiling)) {
                return false;
            }
            admitted.incrementAndGet();
            return true;
        }

        @Override
        public void connectionClosed() {
            released.incrementAndGet();
            live.decrement();
        }

        @Override
        public boolean tryAdmitRequest() {
            return requestCalls.incrementAndGet() <= requestCeiling;
        }

        int requestCalls() {
            return requestCalls.get();
        }

        int connectionCalls() {
            return connectionCalls.get();
        }

        int liveConnections() {
            return live.get();
        }

        int admittedConnections() {
            return admitted.get();
        }

        int releasedConnections() {
            return released.get();
        }
    }

    // -- HTTP/1.1 client ----------------------------------------------------

    private record Http1Reply(int status, List<HpackFieldReader.Field> fields) {

        String first(String name) {
            return fields.stream()
                    .filter(field -> name.equals(field.name()))
                    .map(HpackFieldReader.Field::value)
                    .findFirst()
                    .orElse(null);
        }

        int contentLength() {
            String value = first("content-length");
            return value == null ? 0 : Integer.parseInt(value.trim());
        }
    }

    private static Http1Reply get(Socket client, String path) throws IOException {
        Http1Reply head = sendAndReadHead(client, path);
        client.getInputStream().readNBytes(head.contentLength());
        return head;
    }

    private static Http1Reply getStreamHead(Socket client, String path) throws IOException {
        try (client) {
            return sendAndReadHead(client, path);
        }
    }

    private static Http1Reply sendAndReadHead(Socket client, String path) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = client.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("the connection closed before the response head ended: " + head);
            }
            head.append((char) next);
        }
        String[] lines = head.toString().split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        List<HpackFieldReader.Field> fields = new ArrayList<>();
        for (int i = 1; i < lines.length && !lines[i].isEmpty(); i++) {
            int colon = lines[i].indexOf(':');
            fields.add(new HpackFieldReader.Field(
                    lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim()));
        }
        return new Http1Reply(status, fields);
    }

    // -- HTTP/2 client ------------------------------------------------------

    private record H2Reply(int status, List<HpackFieldReader.Field> fields, boolean headersEndStream,
                           boolean reset) {

        String first(String name) {
            return fields.stream()
                    .filter(field -> name.equals(field.name()))
                    .map(HpackFieldReader.Field::value)
                    .findFirst()
                    .orElse(null);
        }
    }

    /** A cleartext prior-knowledge HTTP/2 connection that sends requests and collects their replies. */
    private static final class RawH2 implements AutoCloseable {

        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private final HpackFieldReader reader = new HpackFieldReader();
        private final java.util.Map<Integer, ByteArrayOutputStream> blocks = new java.util.HashMap<>();
        private final java.util.Map<Integer, H2Reply> replies = new java.util.HashMap<>();
        private final java.util.Map<Integer, Boolean> endOnHeaders = new java.util.HashMap<>();

        private RawH2(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
            out.write(CLIENT_PREFACE);
            writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
            out.flush();
        }

        static RawH2 open(StartedServer server) throws IOException {
            return new RawH2(connect(server));
        }

        void request(int streamId, String path) throws IOException {
            writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM | FLAG_END_HEADERS, streamId, requestBlock(path));
            out.flush();
        }

        /** Reads frames until the reply to {@code streamId} is complete or the stream is reset. */
        H2Reply reply(int streamId) throws IOException {
            while (!replies.containsKey(streamId)) {
                readFrame();
            }
            return replies.get(streamId);
        }

        private void readFrame() throws IOException {
            int lengthAndType = in.readInt();
            int length = lengthAndType >>> 8;
            int type = lengthAndType & 0xFF;
            int flags = in.readUnsignedByte();
            int streamId = in.readInt() & 0x7FFF_FFFF;
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) {
                throw new EOFException("the connection closed inside an HTTP/2 frame");
            }
            if (type == TYPE_SETTINGS && (flags & FLAG_ACK) == 0) {
                writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                out.flush();
            } else if (type == TYPE_HEADERS || type == TYPE_CONTINUATION) {
                collectHeaders(streamId, type, flags, payload);
            } else if (type == TYPE_RST_STREAM) {
                replies.put(streamId, new H2Reply(0, List.of(), false, true));
            } else if (type == TYPE_GOAWAY) {
                fail("the server sent GOAWAY while a reply was awaited");
            }
        }

        private void collectHeaders(int streamId, int type, int flags, byte[] payload) {
            ByteArrayOutputStream block = blocks.computeIfAbsent(streamId, id -> new ByteArrayOutputStream());
            if (type == TYPE_HEADERS) {
                endOnHeaders.put(streamId, (flags & FLAG_END_STREAM) != 0);
            }
            block.writeBytes(payload);
            if ((flags & FLAG_END_HEADERS) == 0) {
                return;
            }
            List<HpackFieldReader.Field> fields = reader.decode(block.toByteArray());
            int status = fields.stream()
                    .filter(field -> ":status".equals(field.name()))
                    .mapToInt(field -> Integer.parseInt(field.value()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the response block carries no :status"));
            replies.put(streamId, new H2Reply(status, fields, endOnHeaders.get(streamId), false));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        private static byte[] requestBlock(String path) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeInteger(out, 0x80, 7, STATIC_METHOD_GET);
            writeInteger(out, 0x80, 7, STATIC_SCHEME_HTTP);
            writeInteger(out, 0x00, 4, STATIC_PATH);
            writeString(out, path);
            writeInteger(out, 0x00, 4, STATIC_AUTHORITY);
            writeString(out, "localhost");
            return out.toByteArray();
        }

        private static void writeString(ByteArrayOutputStream out, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            writeInteger(out, 0x00, 7, bytes.length);
            out.writeBytes(bytes);
        }

        private static void writeInteger(ByteArrayOutputStream out, int highBits, int prefixBits, int value) {
            int maxPrefix = (1 << prefixBits) - 1;
            if (value < maxPrefix) {
                out.write(highBits | value);
                return;
            }
            out.write(highBits | maxPrefix);
            int remaining = value - maxPrefix;
            while (remaining >= 0x80) {
                out.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            out.write(remaining);
        }

        private static void writeFrame(OutputStream out, int type, int flags, int streamId, byte[] payload)
                throws IOException {
            ByteBuffer header = ByteBuffer.allocate(FRAME_HEADER_BYTES)
                    .putInt((payload.length << 8) | type)
                    .put((byte) flags)
                    .putInt(streamId & 0x7FFF_FFFF);
            out.write(header.array());
            out.write(payload);
        }
    }
}
