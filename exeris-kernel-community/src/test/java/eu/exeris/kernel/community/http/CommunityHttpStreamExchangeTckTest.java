/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.NativeTcpTransportProvider;
import eu.exeris.kernel.core.http.sse.StreamAdmissionController;
import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.http.AbstractHttpStreamExchangeTck;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Community binding of {@link AbstractHttpStreamExchangeTck} over a real NIO loopback (ADR-043).
 *
 * <p>Each scenario starts a Community {@code NativeTcp} server, accepts a loopback connection, runs the
 * supplied {@link HttpStreamHandler} through {@link CommunityHttpStreamDispatcher} over the real
 * server-side {@link TransportStream}, and reads the {@code text/event-stream} bytes back on a plain
 * client socket — parsing them into {@link StreamEvent}s. The SSE framing (Core {@code SseEventEncoder})
 * and the held-open egress (Community NIO) are exercised end-to-end.
 *
 * <p>Loan accounting is per-stream via {@link LeakTrackingAllocator} wrapping the emit-path allocator
 * (the transport keeps the raw allocator), so {@code outstandingLoans()} reflects only this stream's
 * SSE buffers. Backpressure-park observation is strong: it watches the emitting VT reach a parked
 * state inside {@code HttpStreamEngine.awaitCredit}.
 */
@Tag("stream-loopback")
@DisplayName("Community: HttpStreamExchange TCK")
class CommunityHttpStreamExchangeTckTest extends AbstractHttpStreamExchangeTck {

    private static final long CONNECT_TIMEOUT_SECONDS = 5L;
    // Internal observation bound. Held below the slow-probe JUnit @Timeout (30 s) so a true hang is
    // still caught by JUnit, while a correct-but-slow drain / fail-closed under full-suite load is not
    // cut off prematurely. The fast mandatory cases keep their own tight 10 s @Timeout regardless.
    private static final long OBSERVE_TIMEOUT_MILLIS = 25_000L;
    private static final int CLIENT_READ_BUF = 16 * 1024;
    private static final int CLIENT_RECV_BUF = 2 * 1024;
    // Auth deadline comfortably above worst-case stream-open latency under load (so it never fires
    // racily before the handler is even running) yet small enough that the fail-closed unwind is
    // observed promptly. The engine checks the deadline at emit-top and every park slice.
    private static final long AUTH_EXPIRY_DELAY_MILLIS = 300L;
    private static final long PARK_SLICE_MILLIS = 5L;

    static {
        // Force a small egress credit window AND a small accepted-socket send buffer so the
        // backpressure probe parks deterministically: a stalled client fills the tiny send buffer,
        // the transport stalls, loans are held, the credit window fills, and emit() parks. Set before
        // the dispatcher / carrier constants are read.
        System.setProperty("exeris.http.stream.creditWindowBytes", Integer.toString(16 * 1024));
        System.setProperty("exeris.transport.acceptedSendBufferBytes", Integer.toString(8 * 1024));
    }

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected StreamScenario openStream(HttpStreamHandler handler) {
        return LoopbackStreamScenario.start(handler, 0L);
    }

    @Override
    protected boolean supportsBackpressureProbe() {
        return true;
    }

    @Override
    protected boolean supportsAuthExpiryProbe() {
        return true;
    }

    @Override
    protected boolean supportsShedProbe() {
        return true;
    }

    @Override
    protected StreamScenario openExpiringStream(HttpStreamHandler handler) {
        return LoopbackStreamScenario.start(handler, System.currentTimeMillis() + AUTH_EXPIRY_DELAY_MILLIS);
    }

    @Override
    protected ExerisKernelException openUnderShed(HttpStreamHandler handler) {
        // Shed is decided at admission, BEFORE any transport I/O — so the probe needs no real socket.
        // A forced-shed decision rejects the open with EX-NET-4006 over a no-op stub stream.
        StreamAdmissionController shed =
                new StreamAdmissionController(() -> ResourceArbiter.Action.SHED_LOAD, "tck-stream");
        CommunityHttpStreamDispatcher dispatcher =
                new CommunityHttpStreamDispatcher(new LeakTrackingAllocator(ALLOCATOR), shed);
        try {
            dispatcher.dispatchStream(streamRequest(), new StubStream(), HttpRouter.StreamMatch.exact(handler));
            return null;
        } catch (ExerisKernelException rejection) {
            return rejection;
        }
    }

    @Test
    @DisplayName("SSE response head is well-formed and close-delimited (no Content-Length / chunked, v0.10)")
    void sseResponseHeadIsWellFormedAndCloseDelimited() {
        LoopbackStreamScenario scenario = LoopbackStreamScenario.start(exchange -> {
            exchange.emit(StreamEvent.of("hi"));
            exchange.close();
        }, 0L);
        try (scenario) {
            scenario.awaitEvents(1);
            String head = scenario.responseHead();
            assertThat(head).as("the client must have read a response head").isNotNull();
            String lower = head.toLowerCase(java.util.Locale.ROOT);
            assertThat(head).as("status line").startsWith("HTTP/1.1 200");
            assertThat(lower).as("SSE content type").contains("content-type: text/event-stream");
            assertThat(lower).as("disables proxy/client caching").contains("cache-control: no-cache");
            // v0.10: the SSE body is close-delimited (RFC 9112 §6.3) — Connection: close, and neither
            // Content-Length nor Transfer-Encoding: chunked is present. This pins the honest framing the
            // SseEventEncoder Javadoc / ADR-043 delivery-status note describe.
            assertThat(lower).as("close-delimited framing").contains("connection: close");
            assertThat(lower).as("no chunked framing in v0.10").doesNotContain("transfer-encoding");
            assertThat(lower).as("no Content-Length on an open-ended stream").doesNotContain("content-length");
        }
    }

    // =====================================================================
    // Loopback scenario — real NIO server + plain-socket SSE client.
    // =====================================================================

    private static final class LoopbackStreamScenario implements StreamScenario {

        private final TransportEngine serverEngine;
        private final Socket client;
        private final LeakTrackingAllocator emitAllocator;
        private final SseClientReader reader;
        private final AtomicReference<Throwable> handlerError = new AtomicReference<>();
        private final CountDownLatch handlerDone = new CountDownLatch(1);
        private final AtomicReference<Thread> emitterThread = new AtomicReference<>();

        private LoopbackStreamScenario(TransportEngine serverEngine,
                                       Socket client,
                                       LeakTrackingAllocator emitAllocator,
                                       SseClientReader reader) {
            this.serverEngine = serverEngine;
            this.client = client;
            this.emitAllocator = emitAllocator;
            this.reader = reader;
        }

        @SuppressWarnings("PMD.CloseResource") // server engine + socket owned and closed in close().
        static LoopbackStreamScenario start(HttpStreamHandler handler, long authDeadlineEpochMillis) {
            int port = nextFreePort();
            LeakTrackingAllocator emitAllocator = new LeakTrackingAllocator(ALLOCATOR);
            CommunityHttpStreamDispatcher dispatcher = new CommunityHttpStreamDispatcher(emitAllocator);

            TransportEngine serverEngine = createServerEngine(port);
            LoopbackStreamScenario[] self = new LoopbackStreamScenario[1];
            StreamHandler streamHandler = serverStream ->
                    self[0].runHandler(dispatcher, handler, serverStream, authDeadlineEpochMillis);
            serverEngine.setStreamHandler(streamHandler);
            serverEngine.setConnectionHandler(connection -> { });
            serverEngine.start();

            Socket clientSocket = connectClient(port);
            SseClientReader readerInstance = SseClientReader.start(clientSocket);

            LoopbackStreamScenario scenario =
                    new LoopbackStreamScenario(serverEngine, clientSocket, emitAllocator, readerInstance);
            self[0] = scenario;
            return scenario;
        }

        private void runHandler(CommunityHttpStreamDispatcher dispatcher,
                                HttpStreamHandler handler,
                                TransportStream serverStream,
                                long authDeadlineEpochMillis) {
            HttpRequest request = streamRequest();
            HttpStreamHandler observing = exchange -> {
                emitterThread.set(Thread.currentThread());
                handler.handle(exchange);
            };
            try {
                // The terminal StreamClosedException (disconnect / fail-closed) is returned even when
                // the handler swallows the throw — that is what awaitHandlerUnwind() reports.
                StreamClosedException terminal =
                        dispatcher.dispatchStream(request, serverStream,
                                HttpRouter.StreamMatch.exact(observing), authDeadlineEpochMillis);
                if (terminal != null) {
                    handlerError.set(terminal);
                }
            } catch (RuntimeException ex) {
                handlerError.set(ex);
            } finally {
                handlerDone.countDown();
            }
        }

        @Override
        public List<StreamEvent> awaitEvents(int count) {
            return reader.awaitEvents(count);
        }

        @Override
        public boolean awaitEndOfStream() {
            return reader.awaitEndOfStream();
        }

        /** Community-only: the raw HTTP response head the client read (for the SSE framing assertion). */
        String responseHead() {
            return reader.awaitResponseHead();
        }

        @Override
        public void disconnectClient() {
            closeQuietly(client);
        }

        @Override
        public Throwable awaitHandlerUnwind() {
            awaitLatch(handlerDone);
            return handlerError.get();
        }

        @Override
        public Throwable handlerError() {
            return handlerError.get();
        }

        @Override
        public long outstandingLoans() {
            // Buffer release runs on the reactor after the socket write completes; allow a brief settle
            // so an in-flight close-action (which returns the loan) is observed before we read zero.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (emitAllocator.outstanding() > 0L && System.nanoTime() < deadline) {
                parkBriefly();
            }
            return emitAllocator.outstanding();
        }

        @Override
        public void stallClient() {
            reader.stall();
        }

        @Override
        public void drainClient() {
            reader.drain();
        }

        @Override
        public boolean awaitEmitterParked() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OBSERVE_TIMEOUT_MILLIS);
            while (System.nanoTime() < deadline) {
                Thread emitter = emitterThread.get();
                if (emitter != null && isParkedInCredit(emitter)) {
                    return true;
                }
                parkBriefly();
            }
            return false;
        }

        private static boolean isParkedInCredit(Thread emitter) {
            Thread.State state = emitter.getState();
            if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
                return false;
            }
            for (StackTraceElement frame : emitter.getStackTrace()) {
                if ("awaitCredit".equals(frame.getMethodName())
                        && frame.getClassName().endsWith("HttpStreamEngine")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            closeQuietly(client);
            reader.stop();
            awaitLatch(handlerDone);
            try {
                serverEngine.close();
            } catch (RuntimeException _) {
                // best effort
            }
        }
    }

    // =====================================================================
    // Plain-socket SSE client reader.
    // =====================================================================

    private static final class SseClientReader {

        private final Socket socket;
        private final List<StreamEvent> events = new ArrayList<>();
        private final Object lock = new Object();
        private final AtomicBoolean stalled = new AtomicBoolean(false);
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private volatile boolean endOfStream;
        private volatile String responseHead;
        private Thread readerVt;

        private SseClientReader(Socket socket) {
            this.socket = socket;
        }

        static SseClientReader start(Socket socket) {
            SseClientReader reader = new SseClientReader(socket);
            // Platform thread: the client uses blocking java.net.Socket I/O, which can pin a carrier.
            // The Community test JVM forces only 2 VT carriers, so the reader MUST NOT consume one.
            reader.readerVt = Thread.ofPlatform().daemon(true).name("tck-sse-client").start(reader::readLoop);
            return reader;
        }

        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        private void readLoop() {
            byte[] buf = new byte[CLIENT_READ_BUF];
            // A forward cursor over `pending` instead of repeated delete(0,..): front-deletion per event
            // is O(remaining), which makes a full read batch O(events^2) and collapses throughput under
            // backpressure bursts. We scan from `cursor` and compact only once consumed, keeping the
            // parse linear in bytes received.
            StringBuilder pending = new StringBuilder();
            int cursor = 0;
            boolean headerSeen = false;
            try {
                while (!stopped.get()) {
                    awaitDrain();
                    if (stopped.get()) {
                        return;
                    }
                    int read = socket.getInputStream().read(buf);
                    if (read < 0) {
                        endOfStream = true;
                        return;
                    }
                    pending.append(new String(buf, 0, read, StandardCharsets.UTF_8));
                    if (!headerSeen) {
                        int headEnd = pending.indexOf("\r\n\r\n", cursor);
                        if (headEnd < 0) {
                            continue;
                        }
                        responseHead = pending.substring(cursor, headEnd);
                        cursor = headEnd + 4;
                        headerSeen = true;
                    }
                    cursor = drainEvents(pending, cursor);
                    if (cursor > 0) {
                        pending.delete(0, cursor);
                        cursor = 0;
                    }
                }
            } catch (IOException _) {
                // disconnect / read interrupted: a clean FIN sets endOfStream above.
            } catch (RuntimeException _) {
                // best effort in the test client
            }
        }

        private void awaitDrain() {
            while (stalled.get() && !stopped.get()) {
                parkBriefly();
            }
        }

        private int drainEvents(StringBuilder pending, int from) {
            int cursor = from;
            int idx;
            while ((idx = indexOfBlankLine(pending, cursor)) >= 0) {
                String block = pending.substring(cursor, idx);
                cursor = idx + 1;
                StreamEvent parsed = parseBlock(block);
                if (parsed != null) {
                    synchronized (lock) {
                        events.add(parsed);
                        lock.notifyAll();
                    }
                }
            }
            return cursor;
        }

        private static int indexOfBlankLine(CharSequence text, int from) {
            for (int i = from; i + 1 < text.length(); i++) {
                if (text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
                    return i + 1;
                }
            }
            return -1;
        }

        private static StreamEvent parseBlock(String block) {
            String event = null;
            StringBuilder data = new StringBuilder();
            boolean hasData = false;
            for (String line : block.split("\n", -1)) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                    hasData = true;
                }
            }
            return hasData ? new StreamEvent(event, data.toString(), null, 0L) : null;
        }

        List<StreamEvent> awaitEvents(int count) {
            long deadline = System.currentTimeMillis() + OBSERVE_TIMEOUT_MILLIS;
            synchronized (lock) {
                while (events.size() < count && System.currentTimeMillis() < deadline) {
                    waitOnLock(deadline);
                }
                return List.copyOf(events);
            }
        }

        boolean awaitEndOfStream() {
            long deadline = System.currentTimeMillis() + OBSERVE_TIMEOUT_MILLIS;
            while (!endOfStream && System.currentTimeMillis() < deadline) {
                parkBriefly();
            }
            return endOfStream;
        }

        String awaitResponseHead() {
            long deadline = System.currentTimeMillis() + OBSERVE_TIMEOUT_MILLIS;
            while (responseHead == null && System.currentTimeMillis() < deadline) {
                parkBriefly();
            }
            return responseHead;
        }

        private void waitOnLock(long deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            try {
                lock.wait(remaining);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        void stall() {
            stalled.set(true);
        }

        void drain() {
            stalled.set(false);
        }

        void stop() {
            stopped.set(true);
            if (readerVt != null) {
                readerVt.interrupt();
                try {
                    readerVt.join(OBSERVE_TIMEOUT_MILLIS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // =====================================================================
    // Shared helpers.
    // =====================================================================

    private static TransportEngine createServerEngine(int port) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() ->
                holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                        TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 1024, 30_000)));
        return holder[0];
    }

    private static HttpRequest streamRequest() {
        return new HttpRequest(HttpMethod.GET, "/stream", HttpVersion.HTTP_1_1, List.of(), null);
    }

    private static Socket connectClient(int port) {
        try {
            Socket socket = new Socket();
            int timeout = (int) TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_SECONDS);
            // Small receive buffer so a stalled client fills the server send window quickly and the
            // emit() credit window is forced to park deterministically under the TCK flood.
            socket.setReceiveBufferSize(CLIENT_RECV_BUF);
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeout);
            socket.setTcpNoDelay(true);
            byte[] request = "GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            return socket;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to connect loopback client", ex);
        }
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            InetSocketAddress local = (InetSocketAddress) Objects.requireNonNull(server.getLocalAddress());
            return local.getPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException _) {
            // best effort
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(OBSERVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void parkBriefly() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PARK_SLICE_MILLIS));
    }

    /** No-op {@link TransportStream} for the shed probe: admission rejects before any I/O occurs. */
    private static final class StubStream implements eu.exeris.kernel.spi.transport.TransportStream {

        @Override
        public int read(java.lang.foreign.MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(java.lang.foreign.MemorySegment source, int length) {
            // never reached — admission sheds the open first
        }

        @Override
        public void queueWrite(eu.exeris.kernel.spi.memory.LoanedBuffer buffer, int length) {
            buffer.close();
        }

        @Override
        public long streamId() {
            return 0L;
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
        public eu.exeris.kernel.spi.transport.TransportConnection connection() {
            return null;
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
