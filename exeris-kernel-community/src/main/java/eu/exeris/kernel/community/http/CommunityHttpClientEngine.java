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
 * <h2>Decomposition (v0.8 Sprint 3 QA-015)</h2>
 * <p>Request byte layout lives in {@link CommunityHttpClientRequestEncoder};
 * response parsing + completeness helpers live in
 * {@link CommunityHttpClientResponseDecoder}. This class retains the
 * {@code TransportEngine} lifecycle (start/close/isRunning), the per-request
 * connection orchestration, the response read loop, and dependency
 * resolution (allocator + transport).
 *
 * @since 0.5.0
 */
// CyclomaticComplexity: the multi-state response read loop is intrinsically cohesive.
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
        Peer peer = resolvePeer(request);

        try (TransportConnection connection = transport.connect(peer.host(), peer.port());
             TransportStream stream = connection.openStream()) {
            sendRequest(stream, request, peer.authority());
            return readResponse(stream, request.version(), request.method() == HttpMethod.HEAD);
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
    private Peer resolvePeer(HttpRequest request) {
        String authority = request.authority() != null ? request.authority() : defaultAuthority;
        if (authority == null || authority.isBlank()) {
            throw new IllegalStateException(
                    "Request carries no authority and no http.client.defaultAuthority is configured; "
                            + "set one, or address the request with HttpRequest.withAuthority(host:port)");
        }
        return Peer.parse(authority);
    }

    /**
     * The dialled endpoint plus the authority it came from, which the {@code Host} header follows.
     *
     * <p>Parsing lives here rather than in the engine so that the engine's method count stays under
     * its PMD ceiling, and because splitting an authority is the record's own business.
     */
    private record Peer(String host, int port, String authority) {

        private static final int MAX_PORT = 65_535;

        private static Peer parse(String authority) {
            int close = authority.startsWith("[") ? authority.indexOf(']') : -1;
            if (authority.startsWith("[") && close < 0) {
                throw new IllegalStateException("Unterminated IPv6 literal in authority: " + authority);
            }
            int separator = close >= 0 ? authority.indexOf(':', close) : authority.lastIndexOf(':');
            if (separator <= 0 || separator == authority.length() - 1) {
                throw new IllegalStateException(
                        "Authority must carry an explicit port (host:port), got: " + authority);
            }
            String host = authority.substring(0, separator);
            // An unbracketed IPv6 literal is not merely unusual, it is AMBIGUOUS: "::1:8080" is a
            // valid IPv6 address in its own right, so reading it as host "::1" port 8080 is a guess.
            // RFC 3986 requires the bracketed form for exactly this reason, and guessing is what
            // ADR-074 exists to remove. Note the bracketed host keeps its brackets — InetSocketAddress
            // accepts them (measured), so stripping would be work that also loses the disambiguation.
            if (close < 0 && host.indexOf(':') >= 0) {
                throw new IllegalStateException(
                        "IPv6 authority must be bracketed as [address]:port, got: " + authority);
            }
            int port;
            try {
                port = Integer.parseInt(authority.substring(separator + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Authority port is not a number: " + authority, e);
            }
            if (port <= 0 || port > MAX_PORT) {
                throw new IllegalStateException("Authority port out of range: " + authority);
            }
            return new Peer(host, port, authority);
        }
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
            transport.close();
        } finally {
            if (closeAllocatorOnClose) {
                allocator.close();
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
     *
     * <p>Until 0.12 the two were the same number and this value was allocated up front for every
     * response. {@link CommunityHttpClientResponseReader} now starts small and grows to what the
     * response declares; this bounds that growth, and reaching it still ends the read and leaves
     * the decoder to refuse the overrun.
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
        long configured = config.maxResponseBodyBytes();
        long bounded = configured < 0 ? 64L * 1024L : configured + 8L * 1024L;
        // Clamped, not cast. A ceiling near Integer.MAX_VALUE is a legal setting and the header
        // allowance pushes it past the int range, where a bare cast lands on a negative number and
        // the allocator refuses it — the first response of a deployment that set a large limit
        // failed on the limit itself. Free to clamp now that this value is only a bound: nothing
        // allocates it, so a ceiling nobody reaches costs nothing.
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
