/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.sse;

import eu.exeris.kernel.core.http.http1.Http1ResponseEncoder;
import eu.exeris.kernel.core.http.jfr.StreamAbortiveTeardownEvent;
import eu.exeris.kernel.core.http.jfr.StreamBackpressureParkEvent;
import eu.exeris.kernel.core.http.jfr.StreamClosedEvent;
import eu.exeris.kernel.core.http.jfr.StreamOpenedEvent;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Core: the tier-blind engine behind {@link HttpStreamExchange}. It binds the SSE wire framing
 * ({@link SseEventEncoder}) and the held-open transport mechanics ({@link TransportStream}) into the
 * implementation-blind streaming SPI (ADR-043).
 *
 * <h2>The Wall</h2>
 * <p>This class depends only on SPI contracts ({@link TransportStream}, {@link MemoryAllocator},
 * {@link LoanedBuffer}) and Core framing — never on a concrete driver. A transport tier supplies a
 * concrete {@link TransportStream}; the Community NIO {@code NativeTcpStream} and the Enterprise
 * native binding both flow through this engine.
 *
 * <p><b>v0.10 protocol scope:</b> the SSE event framing ({@link SseEventEncoder}) is protocol-blind, but
 * the response-head write is HTTP/1.1-specific (it uses {@code Http1ResponseEncoder}). HTTP/2 streaming
 * (head as HEADERS frame, body as DATA frames) needs a protocol-aware head-write seam — a follow-up; the
 * current Community path is HTTP/1.1 only.
 *
 * <h2>Backpressure — park the VT, never an on-heap queue (obligation 4)</h2>
 * <p>The engine maintains a bounded <em>credit window</em>: at most {@code creditWindow} bytes may
 * be outstanding (queued to the transport but not yet released). {@link #emit(StreamEvent)} parks the
 * calling virtual thread when the next event would exceed the window, and resumes when the transport
 * releases an in-flight buffer (via {@link LoanedBuffer#addCloseAction}). No event is ever buffered to
 * an unbounded heap queue.
 *
 * <h2>Ownership transfer (obligation 5)</h2>
 * <p>Each event is framed once into a {@link LoanedBuffer} and handed to
 * {@link TransportStream#queueWrite}; the transport takes ownership and releases it after the write
 * completes. No defensive copy is made on the emit path.
 *
 * <h2>Fail-closed on JWT expiry (obligation 6)</h2>
 * <p>When constructed with an {@code authDeadlineEpochMillis &gt; 0}, the engine enforces the deadline:
 * once wall-clock passes it, the next {@link #emit(StreamEvent)} fails closed with
 * {@link StreamClosedException#authExpired(long, long)} ({@code EX-HTTP-4012}) and the stream is reset.
 * The deadline is captured at open — never a per-emit JWKS re-fetch.
 *
 * @since 0.10.0
 */
// The engine is the cohesive lifecycle owner for one stream (open head + emit/park/credit + close +
// fail-closed/abortive teardown); its method count and branch total reflect that single
// responsibility, not inflation. The two RuntimeException catches are deliberate: queueWrite() may
// surface a TransportException (broken-pipe flush) or IllegalStateException (closed) that both mean
// "peer gone", and the frame-copy guard must release credit on any allocation/copy failure. The
// head-write LoanedBuffer is closed by its try-with-resources (CloseResource false positive).
@SuppressWarnings({
    "PMD.CyclomaticComplexity",
    "PMD.TooManyMethods",
    "PMD.CloseResource",
    "PMD.AvoidCatchingGenericException"
})
public final class HttpStreamEngine implements HttpStreamExchange {

    /** Default outstanding-bytes credit window before {@code emit()} parks. */
    public static final int DEFAULT_CREDIT_WINDOW_BYTES = 64 * 1024;

    /** Backstop park slice so a missed unpark cannot wedge the emitter forever. */
    private static final long PARK_SLICE_NANOS = 20_000_000L;

    private static final long NO_AUTH_DEADLINE = 0L;
    private static final int HEAD_SCRATCH_BYTES = 256;
    private static final long MILLIS_PER_NANO = 1_000_000L;

    private final HttpRequest request;
    private final TransportStream stream;
    private final MemoryAllocator allocator;
    private final long authDeadlineEpochMillis;
    private final long openNanos;
    private final long creditWindow;

    private final AtomicLong outstandingBytes = new AtomicLong(0L);
    private final AtomicLong eventsEmitted = new AtomicLong(0L);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Thread> parkedEmitter = new AtomicReference<>();
    private final AtomicReference<StreamClosedException> terminalSignal = new AtomicReference<>();

    private HttpStreamEngine(HttpRequest request,
                             TransportStream stream,
                             MemoryAllocator allocator,
                             long authDeadlineEpochMillis,
                             int creditWindowBytes) {
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.authDeadlineEpochMillis = authDeadlineEpochMillis;
        this.creditWindow = creditWindowBytes;
        this.openNanos = System.nanoTime();
    }

    /**
     * Opens a stream: writes the SSE response head (200, {@code text/event-stream}, {@code no-cache})
     * and returns the live exchange. The caller invokes the handler on its own virtual thread.
     *
     * @param request   the inbound request that opened the stream
     * @param stream    the held-open transport stream (ownership stays with the engine)
     * @param allocator the per-stream allocator
     * @return a live {@link HttpStreamExchange}
     */
    public static HttpStreamEngine open(HttpRequest request,
                                        TransportStream stream,
                                        MemoryAllocator allocator) {
        return open(request, stream, allocator, NO_AUTH_DEADLINE, DEFAULT_CREDIT_WINDOW_BYTES);
    }

    /**
     * Opens a stream with an explicit auth-expiry deadline and credit window (obligation 6).
     *
     * @param request                 the inbound request
     * @param stream                  the held-open transport stream
     * @param allocator               the per-stream allocator
     * @param authDeadlineEpochMillis epoch-millis deadline; {@code <= 0} disables expiry enforcement
     * @param creditWindowBytes       outstanding-bytes window before {@code emit()} parks
     * @return a live {@link HttpStreamExchange}
     */
    public static HttpStreamEngine open(HttpRequest request,
                                        TransportStream stream,
                                        MemoryAllocator allocator,
                                        long authDeadlineEpochMillis,
                                        int creditWindowBytes) {
        HttpStreamEngine engine = new HttpStreamEngine(
                request, stream, allocator, authDeadlineEpochMillis, creditWindowBytes);
        engine.writeResponseHead();
        StreamOpenedEvent.emit(stream.streamId(), authDeadlineEpochMillis > NO_AUTH_DEADLINE);
        return engine;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void emit(StreamEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (closed.get()) {
            throw recordSignal(StreamClosedException.peerDisconnect(eventsEmitted.get()));
        }
        failClosedIfAuthExpired();

        int frameBytes = SseEventEncoder.encodedLength(event);
        awaitCredit(frameBytes);
        transferFrame(event, frameBytes);
        eventsEmitted.incrementAndGet();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stream.close();
        StreamClosedEvent.emit(stream.streamId(), eventsEmitted.get());
    }

    // -------------------------------------------------------------------------
    // Backpressure credit window — park the emitting VT, never a heap queue.
    // -------------------------------------------------------------------------

    private void awaitCredit(int frameBytes) {
        if (outstandingBytes.get() + frameBytes <= creditWindow) {
            return;
        }
        // Single-phase commit BEFORE parking — the blocking park must never sit between
        // begin() and commit() on a virtual thread (carrier-bound EventWriter straddle).
        StreamBackpressureParkEvent.emit(stream.streamId(), outstandingBytes.get(), eventsEmitted.get());
        // Missed-unpark window: between the overflow check above and this set, releaseCredit() on the
        // transport callback thread may call unpark(null) (no-op) and the emitter would miss the signal.
        // The bounded parkNanos(PARK_SLICE_NANOS) backstop below makes that benign — the loop re-checks
        // credit every slice — at the cost of up to one slice of extra latency on that first wakeup. A
        // volatile-flag handshake would close the window but adds hot-path state; the backstop is the
        // deliberate, simpler trade-off.
        parkedEmitter.set(Thread.currentThread());
        try {
            while (outstandingBytes.get() + frameBytes > creditWindow) {
                if (closed.get()) {
                    throw recordSignal(StreamClosedException.peerDisconnect(eventsEmitted.get()));
                }
                failClosedIfAuthExpired();
                LockSupport.parkNanos(PARK_SLICE_NANOS);
            }
        } finally {
            parkedEmitter.set(null);
        }
    }

    private void transferFrame(StreamEvent event, int frameBytes) {
        LoanedBuffer buffer = allocator.allocateNetwork(frameBytes);
        boolean accounted = false;
        try {
            // Zero-copy: frame the event straight into the egress segment (no intermediate byte[]).
            SseEventEncoder.encodeInto(event, buffer.segment(), 0L);
            buffer.setSize(frameBytes);
            outstandingBytes.addAndGet(frameBytes);
            accounted = true;
            buffer.addCloseAction(() -> releaseCredit(frameBytes));
        } catch (RuntimeException error) {
            if (accounted) {
                outstandingBytes.addAndGet(-frameBytes);
            }
            buffer.close();
            throw error;
        }
        try {
            // Ownership transfers to the transport. On a dead stream queueWrite throws — either
            // IllegalStateException ("stream closed") or a TransportException surfaced from a
            // synchronous flush whose socket write faulted (broken pipe / RST after peer
            // disconnect). Both mean the peer is gone: map them to the unchecked
            // StreamClosedException the emit loop unwinds on. On throw the buffer is ours to free
            // (see the catch).
            stream.queueWrite(buffer, frameBytes);
        } catch (RuntimeException streamClosed) {
            // On throw the caller owns the buffer (queueWrite releases only on success), so we close
            // it here to return the credit via its close-action. close() is idempotent and the
            // close-action runs exactly once (refcount CAS), so this cannot double-release even if an
            // implementation also released the loan while unwinding.
            buffer.close();
            abortiveTeardown(StreamClosedException.peerDisconnect(eventsEmitted.get()), streamClosed);
        }
    }

    private void releaseCredit(int frameBytes) {
        outstandingBytes.addAndGet(-frameBytes);
        Thread emitter = parkedEmitter.get();
        if (emitter != null) {
            LockSupport.unpark(emitter);
        }
    }

    // -------------------------------------------------------------------------
    // Fail-closed teardown
    // -------------------------------------------------------------------------

    private void failClosedIfAuthExpired() {
        if (authDeadlineEpochMillis <= NO_AUTH_DEADLINE
                || System.currentTimeMillis() < authDeadlineEpochMillis) {
            return;
        }
        long ageMillis = (System.nanoTime() - openNanos) / MILLIS_PER_NANO;
        abortiveTeardown(StreamClosedException.authExpired(ageMillis, eventsEmitted.get()), null);
    }

    private void abortiveTeardown(StreamClosedException signal, Throwable cause) {
        if (closed.compareAndSet(false, true)) {
            stream.reset(0L);
            StreamAbortiveTeardownEvent.emit(stream.streamId(), signal.errorCode(), eventsEmitted.get());
        }
        if (cause != null && signal.getCause() == null) {
            // initCause throws if a cause was already established; only chain when the slot is free.
            try {
                signal.initCause(cause);
            } catch (IllegalStateException _) {
                // cause already set elsewhere — leave it.
            }
        }
        throw recordSignal(signal);
    }

    private StreamClosedException recordSignal(StreamClosedException signal) {
        terminalSignal.compareAndSet(null, signal);
        return signal;
    }

    private void writeResponseHead() {
        try (LoanedBuffer scratch = allocator.allocateNetwork(HEAD_SCRATCH_BYTES)) {
            long position = 0L;
            position = Http1ResponseEncoder.writeStatusLine(scratch.segment(), position, 200, "OK");
            position = Http1ResponseEncoder.writeHeader(
                    scratch.segment(), position, "Content-Type", "text/event-stream; charset=utf-8");
            position = Http1ResponseEncoder.writeHeader(
                    scratch.segment(), position, "Cache-Control", "no-cache");
            // The SSE body has no Content-Length and is NOT chunk-framed in v0.10, so it is
            // close-delimited (RFC 9112 §6.3): the stream ends when the connection closes, which is the
            // natural end of an SSE session. "Connection: close" advertises this honestly — the request
            // processor already declines keep-alive reuse for streaming routes. Transfer-Encoding: chunked
            // (for SSE through buffering reverse proxies) is a documented v0.10 follow-up.
            position = Http1ResponseEncoder.writeHeader(
                    scratch.segment(), position, "Connection", "close");
            position = Http1ResponseEncoder.writeHeaderEnd(scratch.segment(), position);
            stream.write(scratch.segment(), (int) position);
        }
    }

    /**
     * Diagnostic seam: bytes outstanding (queued to the transport but not yet released).
     *
     * @return outstanding queued bytes
     */
    public long outstandingBytes() {
        return outstandingBytes.get();
    }

    /**
     * Diagnostic/test seam: the {@link StreamClosedException} this stream terminated with (peer
     * disconnect or fail-closed expiry), or {@code null} if it closed gracefully. Recorded even when
     * the handler swallows the throw, so a binding can surface "what ended the stream" to a TCK.
     *
     * @return the terminal signal, or {@code null} on graceful close
     */
    public StreamClosedException terminalSignal() {
        return terminalSignal.get();
    }
}
