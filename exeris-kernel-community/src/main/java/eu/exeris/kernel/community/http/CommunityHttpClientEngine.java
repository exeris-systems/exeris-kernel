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
@SuppressWarnings("PMD.CyclomaticComplexity") // multi-state response read loop is intrinsically cohesive.
final class CommunityHttpClientEngine implements HttpClientEngine {

    private static final String ENGINE_NAME = "community-http-client";
    private static final int READ_CHUNK_BYTES = 8 * 1024;

    private final HttpConfig config;
    private final MemoryAllocator allocator;
    private final TransportEngine transport;
    private final boolean closeAllocatorOnClose;
    private final String targetHost;
    private final int targetPort;
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
                config.bindHost(),
                config.port());
    }

    /* default */ CommunityHttpClientEngine(HttpConfig config,
                                            MemoryAllocator allocator,
                                            TransportEngine transport,
                                            boolean closeAllocatorOnClose,
                                            String targetHost,
                                            int targetPort) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.closeAllocatorOnClose = closeAllocatorOnClose;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
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
        if (targetHost == null || targetHost.isBlank()) {
            throw new IllegalStateException("Client target host must be configured in HttpConfig.bindHost");
        }
        if (targetPort <= 0) {
            throw new IllegalStateException("Client target port must be configured in HttpConfig.port");
        }

        try (TransportConnection connection = transport.connect(targetHost, targetPort);
             TransportStream stream = connection.openStream()) {
            sendRequest(stream, request, connection);
            return readResponse(stream, request.version(), request.method() == HttpMethod.HEAD);
        }
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

    private void sendRequest(TransportStream stream, HttpRequest request, TransportConnection connection) {
        int bodyBytes = request.hasBody() ? (int) request.body().size() : 0;
        int capacity = 512 + request.headers().size() * 128 + bodyBytes;
        try (LoanedBuffer outbound = allocator.allocateNetwork(capacity)) {
            long pos = CommunityHttpClientRequestEncoder.writeRequest(
                    outbound.segment(), request, connection, bodyBytes);
            outbound.setSize(pos);
            stream.write(outbound.segment(), (int) outbound.size());
        }
    }

    private HttpResponse readResponse(TransportStream stream, HttpVersion requestVersion,
                                      boolean bodyless) {
        try (LoanedBuffer aggregate = allocator.allocateNetwork(resolveAggregateCapacity())) {
            long total = 0;
            long headerTerminator = -1;
            long expectedTotal = -1;
            boolean endOfStream = false;
            boolean responseComplete = false;
            while (total < aggregate.capacity() && !endOfStream && !responseComplete) {
                int remaining = (int) (aggregate.capacity() - total);
                int chunk = Math.min(READ_CHUNK_BYTES, remaining);
                int read = stream.read(aggregate.segment().asSlice(total, chunk), chunk);
                if (read != 0) {
                    if (read < 0) {
                        endOfStream = true;
                    } else {
                        total += read;
                        aggregate.setSize(total);
                        headerTerminator = CommunityHttpClientResponseDecoder.resolveHeaderTerminator(
                                headerTerminator, aggregate.segment(), total);
                        expectedTotal = CommunityHttpClientResponseDecoder.resolveExpectedTotal(
                                expectedTotal, aggregate.segment(), total, headerTerminator, bodyless);
                        responseComplete = CommunityHttpClientResponseDecoder.isResponseComplete(total, expectedTotal);
                    }
                }
            }

            if (total == 0) {
                throw new IllegalStateException("Remote peer returned an empty HTTP response");
            }

            return CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, aggregate, total, requestVersion, bodyless);
        }
    }

    private int resolveAggregateCapacity() {
        long configured = config.maxRequestBodyBytes();
        long bounded = configured < 0 ? 64L * 1024L : configured + 8L * 1024L;
        return (int) Math.max(bounded, 8L * 1024L);
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
