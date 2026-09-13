/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community HTTP/1.x client engine — drives outbound requests over a single
 * {@link TransportConnection} per call, blocking the calling virtual thread.
 *
 * <h2>Decomposition</h2>
 * <p>Request byte layout lives in {@link CommunityHttpClientRequestEncoder};
 * response parsing + completeness helpers live in
 * {@link CommunityHttpClientResponseDecoder}; the response read loop lives in
 * {@link CommunityHttpClientResponseReader}. This class retains the
 * {@code TransportEngine} lifecycle (start/close/isRunning), the per-request
 * connection orchestration, and dependency resolution (allocator + transport).
 *
 * <p><b>Ownership:</b> owns the {@code TransportEngine} for this engine's life, starting it in
 * {@link #start()} and closing it in {@link #close()}; owns the {@link MemoryAllocator} only when
 * none was already bound to {@link KernelProviders#MEMORY_ALLOCATOR} at construction, in which
 * case {@link #close()} closes it too.
 *
 * @since 0.5
 */
// TooManyMethods: SPI contract surface — the count is intrinsic. Every method here but
// resolvePeer/sendRequest/readResponse implements HttpClientEngine, and ADR-074 added
// defaultAuthority() to that interface; PersistenceConnection carries the same disposition.
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
final class CommunityHttpClientEngine implements HttpClientEngine {

    private static final String ENGINE_NAME = "community-http-client";

    private final HttpConfig config;
    private final MemoryAllocator allocator;
    private final TransportEngine transport;
    private final boolean closeAllocatorOnClose;
    private final String defaultAuthority;
    private final CommunityHttpClientConnectionPool connectionPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /* default */ CommunityHttpClientEngine(HttpConfig config) {
        this(config, resolveDeps(config));
    }

    private CommunityHttpClientEngine(HttpConfig config, ResolvedHttpClientDeps deps) {
        this(config,
                deps.allocator(),
                deps.transport(),
                deps.closeAllocatorOnClose(),
                config.defaultAuthority());
    }

    /* default */ CommunityHttpClientEngine(HttpConfig config,
                                            MemoryAllocator allocator,
                                            TransportEngine transport,
                                            boolean closeAllocatorOnClose,
                                            String defaultAuthority) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.closeAllocatorOnClose = closeAllocatorOnClose;
        this.defaultAuthority = defaultAuthority;
        this.connectionPool = new CommunityHttpClientConnectionPool(config);
    }

    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Client engine is closed");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Client engine is already running");
        }
        boolean startFailed = true;
        try {
            transport.start();
            startFailed = false;
        } finally {
            if (startFailed) {
                running.set(false);
            }
        }
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!running.get() || closed.get()) {
            throw new IllegalStateException("Client engine is not running");
        }
        CommunityHttpClientPeer peer = resolvePeer(request);

        HttpResponse pooledResponse = trySendPooled(request, peer);
        if (pooledResponse != null) {
            return pooledResponse;
        }
        return sendFresh(request, peer);
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.PreserveStackTrace"})
    private HttpResponse trySendPooled(HttpRequest request, CommunityHttpClientPeer peer) {
        CommunityHttpClientConnectionPool.PooledConnection pooled = connectionPool.acquire(peer.authority());
        if (pooled == null) {
            return null;
        }
        try {
            return executeExchange(pooled.connection(), pooled.stream(), request, peer);
        } catch (Exception ex) {
            pooled.close();
            // RFC 9110 §9.2.2: Only idempotent requests may be retried on connection failure.
            // Non-idempotent methods (e.g. POST) must not be silently retried once bytes may have left.
            if (!request.method().isIdempotent()) {
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Failed to execute HTTP request on pooled connection", ex);
            }
            return null;
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.PreserveStackTrace"})
    private HttpResponse sendFresh(HttpRequest request, CommunityHttpClientPeer peer) {
        TransportConnection connection = transport.connect(peer.host(), peer.port());
        TransportStream stream = null;
        try {
            stream = connection.openStream();
            return executeExchange(connection, stream, request, peer);
        } catch (Throwable t) {
            CommunityHttpClientConnectionPool.closeQuietly(stream, connection);
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("Failed to send HTTP request", t);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.PreserveStackTrace"})
    private HttpResponse executeExchange(TransportConnection connection,
                                         TransportStream stream,
                                         HttpRequest request,
                                         CommunityHttpClientPeer peer) {
        try {
            sendRequest(stream, request, peer.authority());
            HttpResponse response = readResponse(stream, request.version(), request.method() == HttpMethod.HEAD);
            if (CommunityHttpClientResponseDecoder.isKeepAlive(request, response, connection)
                    && !stream.hasPendingData()) {
                connectionPool.release(peer.authority(), connection, stream);
            } else {
                CommunityHttpClientConnectionPool.closeQuietly(stream, connection);
            }
            return response;
        } catch (Throwable t) {
            CommunityHttpClientConnectionPool.closeQuietly(stream, connection);
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("Failed to execute HTTP exchange", t);
        }
    }

    /**
     * Resolves the peer this request is addressed to: the request's own authority, or the engine's
     * configured default when it carries none.
     *
     * <p>Before ADR-074 this read {@code HttpConfig.bindHost} — the SERVER/DUAL <em>listener</em>
     * address — so the client dialled the address its own server listened on, and a config built by
     * {@code HttpConfig.defaultClient()} (bindHost {@code null}, port {@code -1}) produced an engine
     * that could not send at all. Refusing an unaddressed request is the correct failure: the
     * alternative is dialling somewhere the caller never named.
     *
     * <p>The port is required rather than defaulted. {@link HttpRequest} carries no scheme, so there
     * is no basis for choosing 80 over 443 — and defaulting to the listener port is precisely what
     * this decision removed.
     */
    private CommunityHttpClientPeer resolvePeer(HttpRequest request) {
        String authority = request.authority() != null ? request.authority() : defaultAuthority;
        if (authority == null || authority.isBlank()) {
            throw new IllegalStateException(
                    "Request carries no authority and no http.client.defaultAuthority is configured; "
                            + "set one, or address the request with HttpRequest.withAuthority(host:port)");
        }
        return CommunityHttpClientPeer.parse(authority);
    }

    @Override
    public String defaultAuthority() {
        return defaultAuthority;
    }

    @Override
    public boolean isRunning() {
        return running.get() && !closed.get();
    }

    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    @SuppressWarnings("PMD.UseTryWithResources")
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        try {
            connectionPool.close();
        } finally {
            try {
                transport.close();
            } finally {
                if (closeAllocatorOnClose) {
                    allocator.close();
                }
            }
        }
    }

    private void sendRequest(TransportStream stream, HttpRequest request, String effectiveAuthority) {
        int bodyBytes = request.hasBody() ? (int) request.body().size() : 0;
        int capacity = 512 + request.headers().size() * 128 + bodyBytes;
        try (LoanedBuffer outbound = allocator.allocateNetwork(capacity)) {
            long pos = CommunityHttpClientRequestEncoder.writeRequest(
                    outbound.segment(), request, effectiveAuthority, bodyBytes);
            outbound.setSize(pos);
            stream.write(outbound.segment(), (int) outbound.size());
        }
    }

    private HttpResponse readResponse(TransportStream stream, HttpVersion requestVersion,
                                      boolean bodyless) {
        try (CommunityHttpClientResponseReader reader = new CommunityHttpClientResponseReader(
                allocator, resolveAggregateCapacity(), bodyless)) {
            reader.readFrom(stream);
            return reader.decode(requestVersion);
        }
    }

    /**
     * The largest a response read may become — the CEILING, not the size it starts at.
     * {@link CommunityHttpClientResponseReader} starts small and grows to what the response
     * declares; this bounds that growth, and reaching it still ends the read and leaves the
     * decoder to refuse the overrun.
     *
     * <p>Package-private so the ceiling it derives can be asserted without opening a socket, the
     * same reason {@code CommunityHttpTransportFactory.buildTransportConfig} is: the value an
     * operator configures is worth a test that does not depend on a live peer to reach it.
     *
     * @return the aggregate ceiling in bytes
     */
    /* default */ int resolveAggregateCapacity() {
        // The RESPONSE ceiling, not the request one. Reading a response against the limit that
        // bounds what this server accepts made an ingress setting retune an outbound client
        // (ADR-071 amendment); they are opposite directions on different sockets.
        // HttpConfig refuses anything outside (0, Integer.MAX_VALUE], so there is no unlimited
        // branch to handle here: -1 does not reach this method.
        long bounded = config.maxResponseBodyBytes() + 8L * 1024L;
        // Clamped, not cast. The header allowance pushes a ceiling near the int range past it,
        // where a bare cast lands on a negative number the allocator refuses — the first response
        // of a deployment that set a large limit failed on the limit itself.
        return Math.clamp(bounded, 8 * 1024, Integer.MAX_VALUE);
    }

    private static MemoryAllocator resolveAllocator(HttpConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            return KernelProviders.MEMORY_ALLOCATOR.get();
        }
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    private static ResolvedHttpClientDeps resolveDeps(HttpConfig config) {
        MemoryAllocator allocator = resolveAllocator(config);
        boolean closeAllocatorOnClose = !KernelProviders.MEMORY_ALLOCATOR.isBound();
        TransportEngine transport = CommunityHttpTransportFactory.buildTransport(config, config.port(), allocator);
        return new ResolvedHttpClientDeps(allocator, transport, closeAllocatorOnClose);
    }

    private record ResolvedHttpClientDeps(MemoryAllocator allocator,
                                          TransportEngine transport,
                                          boolean closeAllocatorOnClose) {
    }
}
