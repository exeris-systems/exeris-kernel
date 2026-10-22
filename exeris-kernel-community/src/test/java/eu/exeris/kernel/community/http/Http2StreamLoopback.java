/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.core.http.sse.HttpStreamEngine;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
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
import eu.exeris.kernel.spi.transport.TransportEngine;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * One live HTTP/2 stream round trip for the stream TCK bindings: a real NIO server whose request loop
 * serves {@code GET /stream} as a stream route over cleartext prior knowledge, and an
 * {@link Http2TestClient} that reads it. The emit path's allocator is a {@link LeakTrackingAllocator},
 * so the outstanding loans are this stream's alone.
 *
 * <p>{@link #startTls} serves the same route over TLS with ALPN {@code h2} instead, on the production
 * transport's TLS listener.
 */
final class Http2StreamLoopback implements AutoCloseable {

    private static final long OBSERVE_TIMEOUT_MILLIS = 25_000L;
    private static final long PARK_SLICE_MILLIS = 5L;
    private static final int CLIENT_INITIAL_WINDOW = 4_096;
    private static final String STREAM_PATH = "/stream";
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private final LeakTrackingAllocator emitAllocator;
    private final AtomicReference<Throwable> handlerError = new AtomicReference<>();
    private final CountDownLatch handlerDone = new CountDownLatch(1);
    private final AtomicReference<Thread> emitterThread = new AtomicReference<>();
    private TransportEngine serverEngine;
    private Http2TestClient client;

    private Http2StreamLoopback(LeakTrackingAllocator emitAllocator) {
        this.emitAllocator = emitAllocator;
    }

    /** The material a TLS round trip needs: the server's crypto provider and certificate, and the client's trust. */
    record Tls(CommunityKernelCryptoProvider crypto, TlsTestCertificate certificate, SSLContext clientTrust) {
    }

    /**
     * Starts a server and a client that has already sent its request.
     *
     * @param handler                 the stream handler the route runs
     * @param authDeadlineEpochMillis fail-closed deadline for the stream, {@code 0} for none
     * @return the running round trip
     */
    static Http2StreamLoopback start(HttpStreamHandler handler, long authDeadlineEpochMillis) {
        return begin(handler, authDeadlineEpochMillis, null);
    }

    /**
     * As {@link #start}, over TLS with ALPN {@code h2}: the server's listener terminates TLS and the
     * client negotiates the protocol before it sends its request.
     *
     * @param handler                 the stream handler the route runs
     * @param authDeadlineEpochMillis fail-closed deadline for the stream, {@code 0} for none
     * @param tls                     the server and client TLS material
     * @return the running round trip
     */
    static Http2StreamLoopback startTls(HttpStreamHandler handler, long authDeadlineEpochMillis, Tls tls) {
        return begin(handler, authDeadlineEpochMillis, Objects.requireNonNull(tls));
    }

    @SuppressWarnings("PMD.CloseResource") // the server engine and the client are owned and closed by close().
    private static Http2StreamLoopback begin(HttpStreamHandler handler, long authDeadlineEpochMillis, Tls tls) {
        int port = nextFreePort();
        LeakTrackingAllocator emitAllocator = new LeakTrackingAllocator(ALLOCATOR);
        Http2StreamLoopback loopback = new Http2StreamLoopback(emitAllocator);
        HttpConfig config = serverConfig(port);
        CommunityHttpRequestProcessor processor = new CommunityHttpRequestProcessor(
                ALLOCATOR, HttpResponseBodyEncoderRegistry.empty(), config,
                new CommunityHttpRequestDispatcher(ALLOCATOR, null, null, null, null),
                new CommunityHttpStreamDispatcher(emitAllocator, null, request -> authDeadlineEpochMillis));
        HttpHandler routes = new Routes(loopback.observing(handler));

        TransportEngine engine = buildTransport(config, port, tls);
        engine.setStreamHandler(stream -> processor.process(stream, routes));
        engine.setConnectionHandler(connection -> { });
        engine.start();
        loopback.serverEngine = engine;
        loopback.client = tls == null
                ? Http2TestClient.connectPlain(
                        port, STREAM_PATH, CLIENT_INITIAL_WINDOW, Http2TestClient.Start.PRIOR_KNOWLEDGE)
                : Http2TestClient.connectTls(port, tls.clientTrust(), STREAM_PATH, CLIENT_INITIAL_WINDOW);
        return loopback;
    }

    private HttpStreamHandler observing(HttpStreamHandler handler) {
        return exchange -> {
            emitterThread.set(Thread.currentThread());
            RuntimeException thrown = null;
            try {
                handler.handle(exchange);
            } catch (RuntimeException failure) {
                thrown = failure;
                throw failure;
            } finally {
                StreamClosedException signal = exchange instanceof HttpStreamEngine engine
                        ? engine.terminalSignal() : null;
                handlerError.set(signal != null ? signal : thrown);
                handlerDone.countDown();
            }
        };
    }

    // ------------------------------------------------------------------ observation

    /**
     * Waits until the client has parsed {@code count} events.
     *
     * @param count how many events to wait for
     * @return the events parsed, in wire order
     */
    List<StreamEvent> awaitEvents(int count) {
        return client.awaitEvents(count);
    }

    /**
     * Waits for END_STREAM on the request stream.
     *
     * @return {@code true} when the stream ended cleanly
     */
    boolean awaitEndOfStream() {
        return client.awaitEndOfStream();
    }

    /**
     * Drops the client's connection. A handler that waits on a latch the test releases right after this
     * call would be interrupted there if the server saw the connection end first, which the server does
     * promptly on a transport that reports a close; so the drop is made from another thread, once the
     * emitter is known not to be in the gap before such a wait: it has been seen blocked on a latch and
     * released again, or it has emitted a second event, which a handler that waits after its first does
     * not. The handler's next emit then meets the closed connection.
     */
    void disconnectClient() {
        Thread closer = Thread.ofPlatform().daemon(true).name("h2-test-disconnect").unstarted(() -> {
            long giveUp = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OBSERVE_TIMEOUT_MILLIS);
            Thread emitter = emitterThread.get();
            while (emitter != null && !isBlockedOnLatch(emitter) && client.eventsParsed() < 2
                    && System.nanoTime() < giveUp) {
                parkBriefly();
                emitter = emitterThread.get();
            }
            while (emitter != null && isBlockedOnLatch(emitter) && System.nanoTime() < giveUp) {
                parkBriefly();
            }
            client.disconnect();
        });
        closer.start();
    }

    private static boolean isBlockedOnLatch(Thread thread) {
        Thread.State state = thread.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if ("java.util.concurrent.CountDownLatch".equals(frame.getClassName())
                    && "await".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Waits for the handler to return and reports what ended its stream.
     *
     * @return the terminal signal or the failure the handler threw, or {@code null}
     */
    Throwable awaitHandlerUnwind() {
        awaitLatch(handlerDone);
        return handlerError.get();
    }

    /**
     * Reports what ended the handler without waiting beyond a completed run.
     *
     * @return the terminal signal or the failure the handler threw, or {@code null}
     */
    Throwable handlerError() {
        return handlerError.get();
    }

    /**
     * Counts the event buffers not yet released, allowing the transport a brief settle to run the close
     * actions that return them.
     *
     * @return the outstanding loan count
     */
    long outstandingLoans() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (emitAllocator.outstanding() > 0L && System.nanoTime() < deadline) {
            parkBriefly();
        }
        return emitAllocator.outstanding();
    }

    /** Withholds the client's flow-control credit while it keeps reading. */
    void stallClient() {
        client.stall();
    }

    /** Returns the withheld credit and resumes granting. */
    void drainClient() {
        client.drain();
    }

    /**
     * Waits until the emitting thread is parked in the writer's credit wait.
     *
     * @return {@code true} once it is
     */
    boolean awaitEmitterParked() {
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
            if ("acquireCredit".equals(frame.getMethodName())
                    && frame.getClassName().endsWith("Http2ConnectionWriter")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        client.close();
        awaitLatch(handlerDone);
        try {
            serverEngine.close();
        } catch (RuntimeException _) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ server

    private static TransportEngine buildTransport(HttpConfig config, int port, Tls tls) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR);
        if (tls != null) {
            MapConfigProvider kernelConfig = new MapConfigProvider(
                    Map.of("transport.certPath", tls.certificate().certificate().toString(),
                            "transport.keyPath", tls.certificate().privateKey().toString()),
                    Map.of());
            scope = scope.where(KernelProviders.CRYPTO_PROVIDER, tls.crypto())
                    .where(KernelProviders.CURRENT_CONFIG, kernelConfig);
        }
        scope.run(() -> holder[0] = CommunityHttpTransportFactory.buildTransport(
                config, port, ALLOCATOR, CommunityHttpTransportFactory.Role.SERVER));
        return holder[0];
    }

    private static HttpConfig serverConfig(int port) {
        return new HttpConfig(
                HttpMode.SERVER,
                "127.0.0.1",
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2,
                null,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) Objects.requireNonNull(server.getLocalAddress())).getPort();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
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

    /** Serves {@code GET /stream} as the stream route and everything else with an empty 404. */
    private static final class Routes implements HttpHandler, StreamRouteResolver {
        private final HttpStreamHandler stream;

        private Routes(HttpStreamHandler stream) {
            this.stream = stream;
        }

        @Override
        public void handle(HttpExchange exchange) {
            exchange.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, HttpVersion.HTTP_2));
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            return method == HttpMethod.GET && STREAM_PATH.equals(path) ? StreamMatch.exact(stream) : null;
        }
    }
}
