/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Reads one HTTP/1.x response off a {@link TransportStream} into a single contiguous buffer.
 *
 * <p>The buffer starts small and grows to what the response says it needs. Until 0.12 the client
 * allocated the configured ceiling up front for every response, so a {@code HEAD} against a 10 MiB
 * ceiling cost 10 MiB — the allocation was sized by configuration rather than by the response, on a
 * path that runs once per request. {@code Content-Length} is the signal that ends the guessing:
 * once the decoder has resolved it the next allocation is the last one, and for the common small
 * response there is no second allocation at all.
 *
 * <p>The ceiling did not go away, it stopped being the starting point. Reaching it still ends the
 * read and leaves the decoder to refuse the overrun with the same message, which is what lets a
 * caller size an engine deliberately and rely on the refusal.
 *
 * <p>Not thread-safe, and not meant to be: one instance reads one response on the calling thread.
 *
 * @since 0.12.0
 */
final class CommunityHttpClientResponseReader implements AutoCloseable {

    private static final int READ_CHUNK_BYTES = 8 * 1024;

    /**
     * What a read starts with, before the response has said how big it is.
     *
     * <p>One read chunk: enough for a status line and a header block in the overwhelming majority
     * of responses, which is all that is needed to reach the {@code Content-Length} that sizes the
     * rest. A {@code HEAD} or a small {@code GET} never grows past it.
     */
    private static final int INITIAL_CAPACITY_BYTES = READ_CHUNK_BYTES;

    private final MemoryAllocator allocator;
    private final int ceiling;
    private final boolean bodyless;

    private LoanedBuffer aggregate;
    private long total;
    private long headerTerminator = -1;
    private long expectedTotal = -1;

    /* default */ CommunityHttpClientResponseReader(MemoryAllocator allocator,
                                                    int ceiling,
                                                    boolean bodyless) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.ceiling = ceiling;
        this.bodyless = bodyless;
        this.aggregate = allocator.allocateNetwork(Math.min(INITIAL_CAPACITY_BYTES, ceiling));
    }

    /**
     * Reads until the response is complete, the peer stops sending, or the ceiling is reached.
     *
     * @param stream the stream to read from
     * @throws IllegalStateException if the peer sent nothing at all
     */
    /* default */ void readFrom(TransportStream stream) {
        boolean reading = true;
        while (reading) {
            reading = ensureRoom() && readOnce(stream);
        }
        if (total == 0) {
            throw new IllegalStateException("Remote peer returned an empty HTTP response");
        }
    }

    /**
     * Decodes what was read. Any body is copied into its own buffer owned by the response, so this
     * reader may be closed immediately afterwards.
     *
     * @param requestVersion the version the request was sent as
     * @return the decoded response
     */
    /* default */ HttpResponse decode(HttpVersion requestVersion) {
        return CommunityHttpClientResponseDecoder.decodeResponse(
                allocator, aggregate, total, requestVersion, bodyless);
    }

    @Override
    public void close() {
        aggregate.close();
    }

    /**
     * Makes room for one more read, growing the buffer if it is full.
     *
     * @return {@code false} when the buffer is full and the ceiling forbids growing further — the
     *     read stops there and the decoder refuses what arrived
     */
    private boolean ensureRoom() {
        if (total < aggregate.capacity()) {
            return true;
        }
        int grown = nextCapacity();
        if (grown <= aggregate.capacity()) {
            return false;
        }
        grow(grown);
        return true;
    }

    /**
     * The capacity to grow to.
     *
     * <p>Once {@code Content-Length} is known the response has told us its own size and one more
     * allocation ends it. Until then — a chunked or connection-framed response — doubling is the
     * only honest guess. Both stay bounded by the ceiling.
     */
    private int nextCapacity() {
        long current = aggregate.capacity();
        long target = expectedTotal > 0 ? expectedTotal : current * 2;
        return (int) Math.min(Math.max(target, current), ceiling);
    }

    /**
     * Moves what has been read into a larger buffer and releases the old one.
     *
     * <p>The copy is bounded by the OLD capacity: growth only happens once the prefix has filled
     * the buffer, so the bytes moved are exactly the bytes already read. Ownership changes at one
     * point — if the copy fails the new buffer is released and the old one stays live for
     * {@link #close()}.
     */
    @SuppressWarnings({
        "PMD.CloseResource",       // the new buffer OUTLIVES this method by design — it becomes the field
        "PMD.UseTryWithResources"  // ownership handover: which of the two gets closed is the outcome
    })
    private void grow(int capacity) {
        LoanedBuffer grown = allocator.allocateNetwork(capacity);
        boolean copied = false;
        try {
            MemorySegment.copy(aggregate.segment(), 0, grown.segment(), 0, total);
            grown.setSize(total);
            copied = true;
        } finally {
            if (copied) {
                aggregate.close();
                aggregate = grown;
            } else {
                grown.close();
            }
        }
    }

    /**
     * One read, plus the framing it may have completed.
     *
     * @return {@code false} when the peer closed or the response is complete
     */
    private boolean readOnce(TransportStream stream) {
        int chunk = (int) Math.min(READ_CHUNK_BYTES, aggregate.capacity() - total);
        int read = stream.read(aggregate.segment().asSlice(total, chunk), chunk);
        if (read < 0) {
            return false;
        }
        if (read > 0) {
            total += read;
            aggregate.setSize(total);
            headerTerminator = CommunityHttpClientResponseDecoder.resolveHeaderTerminator(
                    headerTerminator, aggregate.segment(), total);
            expectedTotal = CommunityHttpClientResponseDecoder.resolveExpectedTotal(
                    expectedTotal, aggregate.segment(), total, headerTerminator, bodyless);
        }
        return !CommunityHttpClientResponseDecoder.isResponseComplete(total, expectedTotal);
    }
}
