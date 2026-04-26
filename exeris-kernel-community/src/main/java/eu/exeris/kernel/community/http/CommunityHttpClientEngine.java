/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http1.Http1ResponseEncoder;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({
    "PMD.GodClass",
    "PMD.CyclomaticComplexity",
    "PMD.TooManyMethods"
})
final class CommunityHttpClientEngine implements HttpClientEngine {

    private static final String ENGINE_NAME = "community-http-client";
    private static final String HEADER_HOST = "Host";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_CLOSE = "close";
    private static final int READ_CHUNK_BYTES = 8 * 1024;
    private static final int STATUS_LINE_MIN_PARTS = 2;
    private static final int STATUS_LINE_PARTS_WITH_REASON = 3;

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
            return readResponse(stream, request.version());
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
            MemorySegment seg = outbound.segment();
            long pos = writeRequestLine(seg, request);
            HeaderWriteResult headerWriteResult = writeRequestHeaders(seg, pos, request);
            pos = writeDefaultRequestHeaders(
                    seg,
                    headerWriteResult.position(),
                    headerWriteResult.presence(),
                    connection,
                    bodyBytes);
            pos = writeRequestBody(seg, pos, request, bodyBytes);
            outbound.setSize(pos);
            stream.write(outbound.segment(), (int) outbound.size());
        }
    }

    private HttpResponse readResponse(TransportStream stream, HttpVersion requestVersion) {
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
                        headerTerminator = resolveHeaderTerminator(headerTerminator, aggregate.segment(), total);
                        expectedTotal = resolveExpectedTotal(expectedTotal, aggregate.segment(),
                                        total, headerTerminator);
                        responseComplete = isResponseComplete(total, expectedTotal);
                    }
                }
            }

            if (total == 0) {
                throw new IllegalStateException("Remote peer returned an empty HTTP response");
            }

            return decodeResponse(aggregate, total, requestVersion);
        }
    }

    private static long writeRequestLine(MemorySegment seg, HttpRequest request) {
        long pos = 0L;
        pos = writeAscii(seg, pos, request.method().name());
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) ' ');
        pos += 1;
        pos = writeAscii(seg, pos, request.path());
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) ' ');
        pos += 1;
        pos = writeAscii(seg, pos, versionToken(request.version()));
        return Http1ResponseEncoder.writeHeaderEnd(seg, pos);
    }

    private static HeaderWriteResult writeRequestHeaders(MemorySegment seg, long startPos, HttpRequest request) {
        long pos = startPos;
        boolean hasHost = false;
        boolean hasContentLength = false;
        boolean hasConnection = false;
        for (HttpHeader header : request.headers()) {
            if (header.nameEqualsIgnoreCase(HEADER_HOST)) {
                hasHost = true;
            }
            if (header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                hasContentLength = true;
            }
            if (header.nameEqualsIgnoreCase(HEADER_CONNECTION)) {
                hasConnection = true;
            }
            pos = Http1ResponseEncoder.writeHeader(seg, pos, header.name(), header.value());
        }
        return new HeaderWriteResult(pos, new HeaderPresence(hasHost, hasContentLength, hasConnection));
    }

    private static long writeDefaultRequestHeaders(MemorySegment seg,
                                                   long startPos,
                                                   HeaderPresence presence,
                                                   TransportConnection connection,
                                                   int bodyBytes) {
        long pos = startPos;
        if (!presence.hasHost()) {
            pos = Http1ResponseEncoder.writeHeader(seg, pos, HEADER_HOST,
                    connection.remoteAddress() + ":" + connection.remotePort());
        }
        if (!presence.hasContentLength()) {
            pos = Http1ResponseEncoder.writeHeader(seg, pos, HEADER_CONTENT_LENGTH,
                    Integer.toString(bodyBytes));
        }
        if (!presence.hasConnection()) {
            pos = Http1ResponseEncoder.writeHeader(seg, pos, HEADER_CONNECTION, HEADER_CLOSE);
        }
        return Http1ResponseEncoder.writeHeaderEnd(seg, pos);
    }

    private static long writeRequestBody(MemorySegment seg, long startPos, HttpRequest request, int bodyBytes) {
        long pos = startPos;
        if (bodyBytes > 0) {
            MemorySegment.copy(request.body().segment(), 0L, seg, pos, bodyBytes);
            pos += bodyBytes;
        }
        return pos;
    }

    private static long resolveHeaderTerminator(long currentHeaderTerminator, MemorySegment segment, long totalBytes) {
        if (currentHeaderTerminator >= 0) {
            return currentHeaderTerminator;
        }
        return findHeaderTerminator(segment, 0, totalBytes);
    }

    private static long resolveExpectedTotal(long currentExpectedTotal,
                                             MemorySegment segment,
                                             long totalBytes,
                                             long headerTerminator) {
        if (currentExpectedTotal >= 0 || headerTerminator < 0) {
            return currentExpectedTotal;
        }
        long statusLineEnd = CommunityHttpBufferOps.findCrLf(segment, 0, totalBytes);
        if (statusLineEnd < 0) {
            return -1;
        }
        List<HttpHeader> headers = parseHeaders(segment, statusLineEnd + 2, headerTerminator);
        long contentLength = resolveContentLengthFromHeaders(headers);
        if (contentLength < 0) {
            return -1;
        }
        return headerTerminator + 4 + contentLength;
    }

    private static boolean isResponseComplete(long totalBytes, long expectedTotalBytes) {
        return expectedTotalBytes > 0 && totalBytes >= expectedTotalBytes;
    }

    private HttpResponse decodeResponse(LoanedBuffer aggregate, long total, HttpVersion requestVersion) {
        long statusLineEnd = CommunityHttpBufferOps.findCrLf(aggregate.segment(), 0, total);
        if (statusLineEnd < 0) {
            throw new IllegalStateException("Invalid HTTP response: missing status line terminator");
        }

        String statusLine = asciiString(aggregate.segment(), 0, statusLineEnd);
        StatusLine parsedStatus = parseStatusLine(statusLine, requestVersion);

        long headerStart = statusLineEnd + 2;
        long headerEnd = findHeaderTerminator(aggregate.segment(), headerStart, total);
        if (headerEnd < 0) {
            throw new IllegalStateException("Invalid HTTP response: missing header terminator");
        }

        List<HttpHeader> headers = parseHeaders(aggregate.segment(), headerStart, headerEnd);
        long bodyStart = headerEnd + 4;
        long availableBodyBytes = total - bodyStart;
        if (availableBodyBytes < 0) {
            throw new IllegalStateException("Invalid HTTP response: body start exceeds received bytes");
        }
        long bodyLength = resolveBodyLength(headers, availableBodyBytes);
        if (bodyLength > availableBodyBytes) {
            throw new IllegalStateException(
                    "Truncated HTTP response body: expected " + bodyLength
                    + " bytes but received " + availableBodyBytes + " bytes");
        }

        LoanedBuffer bodyBuffer = null;
        if (bodyLength > 0) {
            bodyBuffer = allocator.allocateNetwork((int) bodyLength);
            MemorySegment.copy(aggregate.segment(), bodyStart, bodyBuffer.segment(), 0, bodyLength);
            bodyBuffer.setSize(bodyLength);
        }
        return new HttpResponse(parsedStatus.status(), parsedStatus.version(), headers, bodyBuffer);
    }

    private static List<HttpHeader> parseHeaders(MemorySegment segment, long start, long endExclusive) {
        List<HttpHeader> headers = new ArrayList<>();
        long cursor = start;
        while (cursor < endExclusive) {
            long lineEnd = CommunityHttpBufferOps.findCrLf(segment, cursor, endExclusive + 2);
            if (lineEnd < 0 || lineEnd == cursor) {
                break;
            }
            String line = asciiString(segment, cursor, lineEnd);
            int separator = line.indexOf(':');
            if (separator > 0) {
                String name = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                headers.add(new HttpHeader(name, value));
            }
            cursor = lineEnd + 2;
        }
        return List.copyOf(headers);
    }

    private static long resolveBodyLength(List<HttpHeader> headers, long fallbackBytes) {
        Optional<String> contentLength = headers.stream()
                .filter(header -> header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH))
                .map(HttpHeader::value)
                .findFirst();
        if (contentLength.isPresent()) {
            try {
                return Long.parseLong(contentLength.get());
            } catch (NumberFormatException _) {
                return Math.max(fallbackBytes, 0L);
            }
        }
        return Math.max(fallbackBytes, 0L);
    }

    private static long resolveContentLengthFromHeaders(List<HttpHeader> headers) {
        for (HttpHeader h : headers) {
            if (h.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                try {
                    return Long.parseLong(h.value().trim());
                } catch (NumberFormatException _) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static StatusLine parseStatusLine(String statusLine, HttpVersion requestVersion) {
        String[] parts = statusLine.split(" ", STATUS_LINE_PARTS_WITH_REASON);
        if (parts.length < STATUS_LINE_MIN_PARTS) {
            throw new IllegalStateException("Invalid HTTP response status line: " + statusLine);
        }
        HttpVersion version = switch (parts[0]) {
            case "HTTP/1.0" -> HttpVersion.HTTP_1_0;
            case "HTTP/1.1" -> HttpVersion.HTTP_1_1;
            case "HTTP/2", "HTTP/2.0" -> HttpVersion.HTTP_2;
            default -> requestVersion;
        };
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid HTTP status code in status line: " + statusLine, ex);
        }
        String reason = parts.length == STATUS_LINE_PARTS_WITH_REASON ? parts[2] : "";
        return new StatusLine(version, new HttpStatus(code, reason));
    }

    private static long findHeaderTerminator(MemorySegment segment, long start, long endExclusive) {
        for (long index = start; index + 3 < endExclusive; index++) {
            if (segment.get(ValueLayout.JAVA_BYTE, index) == '\r'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 1) == '\n'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 2) == '\r'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 3) == '\n') {
                return index;
            }
        }
        return -1;
    }

    private int resolveAggregateCapacity() {
        long configured = config.maxRequestBodyBytes();
        long bounded = configured < 0 ? 64L * 1024L : configured + 8L * 1024L;
        return (int) Math.max(bounded, 8L * 1024L);
    }

    private static String asciiString(MemorySegment segment, long startInclusive, long endExclusive) {
        int length = Math.toIntExact(endExclusive - startInclusive);
        byte[] bytes = segment.asSlice(startInclusive, length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static long writeAscii(MemorySegment seg, long pos, String value) {
        for (int i = 0; i < value.length(); i++) {
            seg.set(ValueLayout.JAVA_BYTE, pos + i, (byte) value.charAt(i));
        }
        return pos + value.length();
    }

    private static String versionToken(HttpVersion version) {
        return switch (version) {
            case HTTP_1_0 -> "HTTP/1.0";
            case HTTP_1_1 -> "HTTP/1.1";
            case HTTP_2 -> "HTTP/2";
            case HTTP_3 -> "HTTP/3";
        };
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

    private record HeaderPresence(boolean hasHost, boolean hasContentLength, boolean hasConnection) {
    }

    private record HeaderWriteResult(long position, HeaderPresence presence) {
    }

    private record StatusLine(HttpVersion version, HttpStatus status) {
    }
}