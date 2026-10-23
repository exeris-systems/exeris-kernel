/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.hpack.HpackEncoder;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FlowController;
import eu.exeris.kernel.core.http.http2.Http2FrameEncoder;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.core.http.jfr.StreamBackpressureParkEvent;
import eu.exeris.kernel.core.http.sse.SseEventEncoder;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Community: the one writer of an HTTP/2 connection. Every byte that reaches the connection's
 * {@link TransportStream} after the session starts is written by whichever thread holds this
 * writer's lock, one or more complete frames per hold, so no two threads' frames interleave. The
 * frame loop is the connection's only reader; response threads write beside it.
 *
 * <p>The design relies on the transport admitting one reader and one writer at the same time and on
 * its writes handing bytes to an outbound queue rather than waiting for the peer, so a hold is short
 * and never waits on the reader. {@code CommunityWebSocketEgress} orders the senders of a WebSocket
 * connection with a lock for the same reason.
 *
 * <p>A held-open event stream is written the same way: its head is a header block encoded and written
 * in one hold, each event is one or more DATA frames taken from the credit of both windows, and its end
 * is an empty DATA frame carrying END_STREAM, which no window limits.
 *
 * <p>Invariants:
 * <ul>
 *   <li><b>Encode order is wire order.</b> The HPACK encoder lives here and is used only under the
 *       lock; a header block is encoded and written within one hold, because the encoder's dynamic
 *       table advances with every block and the peer decodes the blocks in wire order.</li>
 *   <li><b>Flow control is exact.</b> The connection send window and each tracked stream's send
 *       window are {@link Http2FlowController}s guarded by the lock. The credit for a frame is taken
 *       and the frame written in one hold, so a reset applied by the frame loop cannot land between
 *       taking credit and writing it.</li>
 *   <li><b>A sender without credit parks, and is woken exactly.</b> It publishes itself in
 *       {@link Http2OutboundStream#parkedSender} while holding the lock, releases the lock and parks;
 *       every window update, settings change, reset and connection end is applied under the lock and
 *       unparks the senders it affects. Publication and the credit check share one hold, so a wakeup
 *       cannot be lost and no timed backstop is needed.</li>
 *   <li><b>Frames split at 16 KiB.</b> That is the payload size every peer must accept, so a peer's
 *       frame-size setting stays out of cross-thread state; a frame carries at most the credit
 *       available, so a window smaller than a frame still makes progress.</li>
 *   <li><b>A response stream holds a slot until its sender is done.</b> {@link #openStreamCount} counts
 *       slots from registration to {@link #release}, not to END_STREAM or a reset, so a peer that
 *       opens and resets streams in a loop cannot pile up live sender threads past the concurrent
 *       stream cap.</li>
 *   <li><b>The lock is never held across a wait on the peer on the Community transport.</b> Every
 *       frame written under it goes through {@code TransportStream#queueWrite}, which the Community
 *       transport answers by handing the socket what it takes without blocking and queueing the rest
 *       for its event loop, so a slow peer cannot hold the lock and starve the other senders or the
 *       frame loop's own writes. The SPI leaves blocking in {@code queueWrite} implementation-defined,
 *       so a transport that sends synchronously there holds the lock for as long as its send takes.</li>
 *   <li><b>A transport per-stream cap bounds queued bytes beyond the window.</b> A stream whose
 *       bytes handed to the transport and not yet released reach the cap does not admit another
 *       event, because a peer can grant a very large window and never read.</li>
 * </ul>
 *
 * <p><b>Allocation:</b> the window and reset methods ({@link #applyConnectionWindowUpdate},
 * {@link #applyStreamWindowUpdate}, {@link #applyInitialWindowSize}, {@link #resetByPeer}) allocate
 * nothing while the lock is uncontended; a contended acquire allocates a queue node. A respond-once
 * response allocates one header-block buffer and one buffer holding its coalesced frames; a body frame
 * allocates its header buffer and a slice handle. An event allocates its frame buffer and one close
 * action, and each frame cut from an event larger than the credit adds a nine-byte header buffer and a
 * slice handle; no event is ever queued on the heap, a sender without credit parks.
 * <p><b>Thread confinement:</b> none — every method may be called from any thread; the lock orders
 * the writers.
 * <p><b>Ownership:</b> every byte goes to the transport through {@code queueWrite}; the writer closes
 * every buffer it allocates and its own reference to every buffer it hands over, and the transport
 * releases the reference it was given.
 */
// Retained suppressions:
// - TooManyMethods / GodClass: one cohesive lock-guarded state machine; splitting it would spread the
//   lock discipline over several classes.
// - AvoidCatchingGenericException: a failed transport write must close the connection for every
//   sender whatever the failure type, and is then rethrown or swallowed deliberately.
// - LawOfDemeter: Http2OutboundStream is this writer's own record of a response stream, a plain
//   holder whose fields are read and written only here, under the lock; hiding them behind accessors
//   would add calls to the lock-held paths without adding an invariant.
// - CyclomaticComplexity / CognitiveComplexity: the credit-and-park loop is one transition table
//   whose lock hand-over cannot be split across methods without obscuring it, and the class total is
//   the sum of the small state transitions the invariants above describe.
// - NullAssignment: clearing parkedSender is the "no sender is parked" half of the park protocol.
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.GodClass",
        "PMD.AvoidCatchingGenericException",
        "PMD.LawOfDemeter",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.NullAssignment"
})
final class Http2ConnectionWriter {

    /** Largest DATA payload sent in one frame; the floor every peer accepts (RFC 9113 §4.2). */
    /* default */ static final int MAX_FRAME_PAYLOAD_BYTES = 16 * 1024;

    private static final int FRAME_HEADER = Http2FrameParser.FRAME_HEADER_SIZE;
    private static final int HEADER_BLOCK_BYTES = 65_536;
    private static final int CONTROL_FRAME_BYTES = 64;
    private static final int HTTP2_MAX_DYNAMIC_TABLE_SIZE = 4096;
    private static final int STREAM_HEAD_SCRATCH_BYTES = 256;
    private static final int FLAG_END_STREAM = 0x01;
    private static final int FLAG_END_HEADERS = 0x04;
    private static final int ACQUIRE_CLOSED = -1;
    private static final int ACQUIRE_DEADLINE = -2;
    private static final int ACQUIRE_RETRY = 0;
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_STATUS = ":status";
    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String HEADER_CACHE_CONTROL = "cache-control";
    private static final String EVENT_STREAM_TYPE = "text/event-stream; charset=utf-8";
    private static final String NO_CACHE = "no-cache";
    /** HTTP/2 field names are lower case on the wire. */
    private static final String LICENCE_HEADER_LOWER = WorkloadAdmission.LICENCE_HEADER.toLowerCase(Locale.ROOT);

    private final TransportStream transport;
    private final MemoryAllocator allocator;
    private final ReentrantLock lock = new ReentrantLock();
    private final HpackDynamicTable encodeTable;
    private final HpackEncoder encoder;
    private final Http2FlowController connectionWindow = new Http2FlowController();
    private final Http2OutboundStream[] streams;
    private final int outstandingCapBytes;
    private final boolean licenceMarker;
    private int streamCount;
    private volatile int openStreamCount;
    private int peerInitialWindowSize = Http2FlowController.DEFAULT_WINDOW_SIZE;
    private boolean connectionClosed;
    private final AtomicReference<Runnable> beforeParkHook = new AtomicReference<>();
    private final AtomicReference<Runnable> afterHeadEncodedHook = new AtomicReference<>();

    /** How one attempt to send a body or an event ended. */
    /* default */ enum SendOutcome {
        /** Every byte was handed to the transport. */
        WRITTEN,
        /** The stream or the connection ended first; nothing more can be written. */
        CLOSED,
        /** The deadline passed while waiting for credit. */
        DEADLINE
    }

    private Http2ConnectionWriter(TransportStream transport,
                                  MemoryAllocator allocator,
                                  int maxStreams,
                                  int outstandingCapBytes,
                                  boolean licenceMarker) {
        this.licenceMarker = licenceMarker;
        this.transport = transport;
        this.allocator = allocator;
        this.encodeTable = new HpackDynamicTable(HTTP2_MAX_DYNAMIC_TABLE_SIZE);
        this.encoder = new HpackEncoder(encodeTable, allocator, false);
        this.streams = new Http2OutboundStream[maxStreams];
        this.outstandingCapBytes = outstandingCapBytes;
    }

    /**
     * Creates the writer of one connection.
     *
     * @param transport           the connection's transport stream
     * @param allocator           allocator for control and header frames
     * @param maxStreams          most response streams tracked at once
     * @param outstandingCapBytes bytes a stream may have queued at the transport before it parks
     * @return a writer with the default flow-control windows
     */
    /* default */ static Http2ConnectionWriter create(TransportStream transport,
                                        MemoryAllocator allocator,
                                        int maxStreams,
                                        int outstandingCapBytes) {
        return create(transport, allocator, maxStreams, outstandingCapBytes, false);
    }

    /**
     * Creates the writer of one connection, optionally stamping the developer-licence marker on every
     * response head it encodes.
     *
     * @param transport           the connection's transport stream
     * @param allocator           allocator for control and header frames
     * @param maxStreams          most response streams tracked at once
     * @param outstandingCapBytes bytes a stream may have queued at the transport before it parks
     * @param licenceMarker       whether every response head carries
     *                            {@link WorkloadAdmission#LICENCE_HEADER}
     * @return a writer with the default flow-control windows
     */
    /* default */ static Http2ConnectionWriter create(TransportStream transport,
                                        MemoryAllocator allocator,
                                        int maxStreams,
                                        int outstandingCapBytes,
                                        boolean licenceMarker) {
        return new Http2ConnectionWriter(transport, allocator, maxStreams, outstandingCapBytes, licenceMarker);
    }

    // ------------------------------------------------------------------ any thread

    /**
     * Writes one complete frame, or several, as a single hold of the lock. Does nothing once the
     * connection has ended.
     *
     * @param frame  the buffer holding the bytes; the caller keeps its own reference and closes it
     * @param length how many bytes of {@code frame} to write
     */
    /* default */ void writeFrame(LoanedBuffer frame, int length) {
        lock.lock();
        try {
            if (connectionClosed) {
                return;
            }
            frame.retain();
            queueWriteOwnedOrRethrow(frame, length);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns how many response streams hold a slot.
     *
     * @return the count, read without the lock
     */
    /* default */ int openStreamCount() {
        return openStreamCount;
    }

    /**
     * Returns the peer's current {@code SETTINGS_INITIAL_WINDOW_SIZE}.
     *
     * @return the window size every new stream starts with
     */
    /* default */ int peerInitialWindowSize() {
        lock.lock();
        try {
            return peerInitialWindowSize;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Test seam: reports whether {@code thread} is queued on the writer's lock.
     *
     * @param thread the thread to look for
     * @return {@code true} while {@code thread} waits to write
     */
    /* default */ boolean hasQueuedWriter(Thread thread) {
        return lock.hasQueuedThread(thread);
    }

    /**
     * Test seam: counts the threads queued on the writer's lock.
     *
     * @return the number of threads waiting to write
     */
    /* default */ int queuedWriters() {
        return lock.getQueueLength();
    }

    /**
     * Test seam: installs a hook that runs on a sender thread immediately before it parks, after it has
     * released the lock on finding no credit and re-read the transport's release count. The sender is
     * already published as parked there, so whatever the hook applies must find and wake it.
     *
     * @param hook the hook, or {@code null} to remove it
     */
    /* default */ void beforeParkForTest(Runnable hook) {
        this.beforeParkHook.set(hook);
    }

    /**
     * Test seam: installs a hook that runs on the sending thread inside {@link #writeStreamHead}, after
     * the head's header block is encoded and before it is written. The hook runs with the lock held, so
     * a thread that must not overtake the encoder's order cannot pass it.
     *
     * @param hook the hook, or {@code null} to remove it
     */
    /* default */ void afterHeadEncodedForTest(Runnable hook) {
        this.afterHeadEncodedHook.set(hook);
    }

    // ------------------------------------------------------------------ frame loop

    /**
     * Applies the peer's {@code SETTINGS_HEADER_TABLE_SIZE} to the HPACK encoder's table.
     *
     * @param size the new maximum table size
     */
    /* default */ void applyHeaderTableSize(long size) {
        lock.lock();
        try {
            encodeTable.setMaxSize(size);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a new {@code SETTINGS_INITIAL_WINDOW_SIZE}: the difference moves the window of every
     * open response stream, which may leave it negative, and wakes the senders.
     *
     * @param newInitial the new initial window size
     * @return {@link Http2ErrorCode#FLOW_CONTROL_ERROR} when a window would pass the maximum, else
     *         {@link Http2ErrorCode#NO_ERROR}
     */
    /* default */ Http2ErrorCode applyInitialWindowSize(int newInitial) {
        lock.lock();
        try {
            int old = peerInitialWindowSize;
            peerInitialWindowSize = newInitial;
            for (int i = 0; i < streamCount; i++) {
                Http2OutboundStream stream = streams[i];
                if (stream.state != Http2OutboundStream.State.OPEN) {
                    continue;
                }
                try {
                    stream.sendWindow.updateInitialWindowSize(old, newInitial);
                } catch (IllegalStateException _) {
                    return Http2ErrorCode.FLOW_CONTROL_ERROR;
                }
                wake(stream);
            }
            return Http2ErrorCode.NO_ERROR;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a WINDOW_UPDATE on stream 0 and wakes every open response stream's sender.
     *
     * @param increment the window increment
     * @return {@link Http2ErrorCode#PROTOCOL_ERROR} for zero, {@link Http2ErrorCode#FLOW_CONTROL_ERROR}
     *         for an overflow, else {@link Http2ErrorCode#NO_ERROR}
     */
    /* default */ Http2ErrorCode applyConnectionWindowUpdate(int increment) {
        if (increment == 0) {
            return Http2ErrorCode.PROTOCOL_ERROR;
        }
        lock.lock();
        try {
            if (!connectionWindow.increment(increment)) {
                return Http2ErrorCode.FLOW_CONTROL_ERROR;
            }
            for (int i = 0; i < streamCount; i++) {
                Http2OutboundStream stream = streams[i];
                if (stream.state == Http2OutboundStream.State.OPEN) {
                    wake(stream);
                }
            }
            return Http2ErrorCode.NO_ERROR;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a WINDOW_UPDATE on a stream. A stream that is not tracked is ignored; an increment of
     * zero or one that overflows the window resets that stream and no other.
     *
     * @param streamId  the stream id
     * @param increment the window increment
     * @return {@code true} when the writer tracks the stream, so the update was addressed to it
     */
    /* default */ boolean applyStreamWindowUpdate(int streamId, int increment) {
        lock.lock();
        try {
            Http2OutboundStream stream = find(streamId);
            if (stream == null) {
                return false;
            }
            if (stream.state != Http2OutboundStream.State.OPEN) {
                return true;
            }
            if (increment == 0) {
                resetLocalLocked(stream, Http2ErrorCode.PROTOCOL_ERROR);
                return true;
            }
            if (!stream.sendWindow.increment(increment)) {
                resetLocalLocked(stream, Http2ErrorCode.FLOW_CONTROL_ERROR);
                return true;
            }
            wake(stream);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets a stream the writer tracks, on behalf of the frame loop: the RST_STREAM is written and
     * the stream closed under the lock, so the task that serves it stops and no DATA follows the
     * reset. Nothing is written for a tracked stream that has already ended.
     *
     * @param streamId the stream id
     * @param code     the RST_STREAM error code
     * @return {@code true} when the writer tracks the stream; {@code false} leaves the caller to
     *         answer a stream the writer does not know
     */
    /* default */ boolean resetStream(int streamId, Http2ErrorCode code) {
        lock.lock();
        try {
            Http2OutboundStream stream = find(streamId);
            if (stream == null) {
                return false;
            }
            resetLocalLocked(stream, code);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a RST_STREAM from the peer: the stream's sender is woken and writes nothing more.
     *
     * @param streamId the stream id
     */
    /* default */ void resetByPeer(int streamId) {
        lock.lock();
        try {
            Http2OutboundStream stream = find(streamId);
            if (stream != null && stream.state == Http2OutboundStream.State.OPEN) {
                stream.state = Http2OutboundStream.State.RESET_BY_PEER;
                wake(stream);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Starts tracking a response stream.
     *
     * @param streamId         the stream id
     * @param pendingIncrement WINDOW_UPDATE credit the peer sent while the request was still arriving
     * @return the stream, or {@code null} when the connection has ended, the table is full (the stream
     *         is then refused with REFUSED_STREAM), or the credit overflows the window (the stream is
     *         then reset with FLOW_CONTROL_ERROR)
     */
    /* default */ Http2OutboundStream register(int streamId, long pendingIncrement) {
        lock.lock();
        try {
            if (connectionClosed) {
                return null;
            }
            if (streamCount == streams.length) {
                writeRstStreamLocked(streamId, Http2ErrorCode.REFUSED_STREAM);
                return null;
            }
            Http2OutboundStream stream = new Http2OutboundStream(streamId, peerInitialWindowSize);
            if (pendingIncrement > 0 && !stream.sendWindow.increment((int) pendingIncrement)) {
                writeRstStreamLocked(streamId, Http2ErrorCode.FLOW_CONTROL_ERROR);
                return null;
            }
            registerLocked(stream);
            return stream;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes a respond-once response as far as the windows allow, in one write: the header block
     * (with CONTINUATION frames if it needs them) and as much of the body as both windows carry.
     * Does not close the body.
     *
     * @param streamId         the stream id
     * @param pendingIncrement WINDOW_UPDATE credit the peer sent while the request was still arriving
     * @param response         the response
     * @return {@code null} when the response is complete or could not be written; otherwise the
     *         stream, tracked, whose remaining body starts at {@link Http2OutboundStream#bodyOffset}
     */
    // CloseResource: the body belongs to the response and is closed by the caller, never here.
    @SuppressWarnings("PMD.CloseResource")
    /* default */ Http2OutboundStream writeRespondOnce(int streamId, long pendingIncrement, HttpResponse response) {
        LoanedBuffer body = response.body();
        int bodyBytes = body == null ? 0 : (int) body.size();
        lock.lock();
        try {
            if (connectionClosed) {
                return null;
            }
            long streamWindow = peerInitialWindowSize + pendingIncrement;
            if (streamWindow > Http2FlowController.MAX_WINDOW_SIZE) {
                writeRstStreamLocked(streamId, Http2ErrorCode.FLOW_CONTROL_ERROR);
                return null;
            }
            int credit = Math.clamp(Math.min(streamWindow, connectionWindow.windowSize()), 0, Integer.MAX_VALUE);
            int now = Math.min(bodyBytes, credit);
            boolean complete = now == bodyBytes;
            writeCoalesced(streamId, response, body, now, bodyBytes, complete);
            connectionWindow.consume(now);
            if (complete) {
                return null;
            }
            Http2OutboundStream stream = new Http2OutboundStream(streamId, (int) (streamWindow - now));
            if (streamCount == streams.length) {
                // Unreachable under the admission invariant; the frame loop reports it as a
                // connection error, which is the right severity for a server-side defect.
                throw new IllegalStateException("outbound stream table full");
            }
            registerLocked(stream);
            stream.headWritten = true;
            stream.bodyOffset = now;
            return stream;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends the connection: every open response stream becomes closed and its sender is woken, and
     * nothing is written afterwards.
     */
    /* default */ void closeConnection() {
        lock.lock();
        try {
            failConnectionLocked();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ stream / continuation threads

    /**
     * Sends {@code length} bytes of {@code body} from {@code offset} as DATA frames, waiting for
     * credit as often as it must. Does not close {@code body}.
     *
     * @param stream    the response stream
     * @param body      the body buffer, owned by the caller
     * @param offset    where in {@code body} to start
     * @param length    how many bytes to send
     * @param endStream whether the last frame carries END_STREAM
     * @return {@link SendOutcome#WRITTEN}, or {@link SendOutcome#CLOSED} when the stream or the
     *         connection ended first
     */
    /* default */ SendOutcome sendBody(Http2OutboundStream stream, LoanedBuffer body, long offset, int length,
                         boolean endStream) {
        int sent = 0;
        while (sent < length) {
            int credit = acquireCredit(stream, false,
                    Math.min(length - sent, MAX_FRAME_PAYLOAD_BYTES), 0L, -1L);
            if (credit < 0) {
                return SendOutcome.CLOSED;
            }
            try {
                boolean last = sent + credit == length;
                int flags = last && endStream ? FLAG_END_STREAM : 0;
                if (!queueDataFrame(stream, body, offset + sent, credit, flags)) {
                    return SendOutcome.CLOSED;
                }
                sent += credit;
                if (last && endStream) {
                    stream.state = Http2OutboundStream.State.END_STREAM_SENT;
                }
            } finally {
                lock.unlock();
            }
        }
        return SendOutcome.WRITTEN;
    }

    // ------------------------------------------------------------------ stream open and events

    /**
     * Writes the SSE response head of a stream: HEADERS with END_HEADERS and without END_STREAM,
     * carrying {@code :status 200}, {@code content-type: text/event-stream} and
     * {@code cache-control: no-cache}. The block is encoded and written within one hold, so it
     * reaches the wire in the order the shared HPACK encoder produced it. There is no
     * {@code content-length}: the body ends with END_STREAM.
     *
     * @param stream the response stream
     * @return {@code false} when the stream or the connection had already ended and nothing was written
     */
    /* default */ boolean writeStreamHead(Http2OutboundStream stream) {
        lock.lock();
        try {
            if (stream.state != Http2OutboundStream.State.OPEN || connectionClosed) {
                return false;
            }
            try (LoanedBuffer scratch = allocator.allocateNetwork(STREAM_HEAD_SCRATCH_BYTES)) {
                MemorySegment target = scratch.segment();
                long position = FRAME_HEADER;
                position = encoder.encodeHeader(target, position, HEADER_STATUS, "200", false);
                position = encoder.encodeHeader(target, position, HEADER_CONTENT_TYPE, EVENT_STREAM_TYPE, false);
                position = encoder.encodeHeader(target, position, HEADER_CACHE_CONTROL, NO_CACHE, false);
                if (licenceMarker) {
                    position = encoder.encodeHeader(
                            target, position, LICENCE_HEADER_LOWER, WorkloadAdmission.DEVELOPMENT_MARKER, false);
                }
                Runnable encoded = afterHeadEncodedHook.get();
                if (encoded != null) {
                    encoded.run();
                }
                Http2FrameEncoder.writeHeader(target, 0L, (int) (position - FRAME_HEADER),
                        Http2FrameType.HEADERS.code(), FLAG_END_HEADERS, stream.streamId);
                scratch.setSize(position);
                scratch.retain();
                queueWriteOwnedOrRethrow(scratch, (int) position);
            }
            stream.headWritten = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Frames one SSE event as DATA and sends it, parking for credit as often as it must. The event is
     * framed once into a buffer of {@code eventAllocator}; an event that fits the available credit goes
     * out as one frame with its header in place, a larger one as 16 KiB frames cut from the same buffer.
     * The event's bytes count against the stream's transport-outstanding cap until the transport
     * releases the buffer.
     *
     * @param stream                  the response stream
     * @param event                   the event to send
     * @param encodedLength           the event's length in its SSE framing
     * @param authDeadlineEpochMillis deadline for the wait for credit, {@code <= 0} for none
     * @param eventsEmitted           events already sent on the stream, for the backpressure event
     * @param eventAllocator          allocator for the event's frame buffer
     * @return {@link SendOutcome#WRITTEN}, {@link SendOutcome#CLOSED} when the stream or the connection
     *         ended first, or {@link SendOutcome#DEADLINE} when the deadline passed while waiting
     */
    // CloseResource / UseTryWithResources: the framed buffer changes owner at queueWrite, so it is closed
    // here only on the paths on which nothing took it.
    @SuppressWarnings({"PMD.CloseResource", "PMD.UseTryWithResources"})
    /* default */ SendOutcome sendEvent(Http2OutboundStream stream, StreamEvent event, int encodedLength,
                                        long authDeadlineEpochMillis, long eventsEmitted,
                                        MemoryAllocator eventAllocator) {
        LoanedBuffer framed = eventAllocator.allocateNetwork(FRAME_HEADER + encodedLength);
        boolean ownsFramed = true;
        boolean accounted = false;
        try {
            SseEventEncoder.encodeInto(event, framed.segment(), FRAME_HEADER);
            framed.setSize((long) FRAME_HEADER + encodedLength);
            int sent = 0;
            while (sent < encodedLength) {
                int credit = acquireCredit(stream, sent == 0,
                        Math.min(encodedLength - sent, MAX_FRAME_PAYLOAD_BYTES),
                        authDeadlineEpochMillis, eventsEmitted);
                if (credit == ACQUIRE_DEADLINE) {
                    return SendOutcome.DEADLINE;
                }
                if (credit < 0) {
                    return SendOutcome.CLOSED;
                }
                try {
                    if (!accounted) {
                        accountOutstanding(stream, framed, encodedLength);
                        accounted = true;
                    }
                    if (sent == 0 && credit == encodedLength) {
                        // The whole event in one frame: the header goes in front of the payload, so
                        // the buffer is handed over as it is.
                        ownsFramed = false;
                        if (!queueEventFrame(stream, framed, credit)) {
                            return SendOutcome.CLOSED;
                        }
                    } else if (!queueDataFrame(stream, framed, (long) FRAME_HEADER + sent, credit, 0)) {
                        return SendOutcome.CLOSED;
                    }
                    sent += credit;
                } finally {
                    lock.unlock();
                }
            }
            return SendOutcome.WRITTEN;
        } finally {
            if (ownsFramed) {
                framed.close();
            }
        }
    }

    /**
     * Counts {@code bytes} against the stream's transport-outstanding cap and registers the release on
     * {@code framed}, undoing the count if the registration fails.
     */
    private static void accountOutstanding(Http2OutboundStream stream, LoanedBuffer framed, int bytes) {
        stream.outstandingBytes.addAndGet(bytes);
        try {
            framed.addCloseAction(() -> stream.releaseOutstanding(bytes));
        } catch (RuntimeException failure) {
            stream.outstandingBytes.addAndGet(-bytes);
            throw failure;
        }
    }

    /**
     * Writes a whole event as one DATA frame, with the lock held and {@code credit} already taken: the
     * frame header goes into the nine bytes the event buffer reserves in front of the payload. The
     * buffer is handed to the transport, which closes it, or closed here when that fails; the credit
     * returns to both windows when nothing of the frame reached the transport.
     *
     * @return {@code false} when the transport failed and the connection has ended
     */
    private boolean queueEventFrame(Http2OutboundStream stream, LoanedBuffer framed, int credit) {
        try {
            Http2FrameEncoder.writeHeader(framed.segment(), 0L, credit,
                    Http2FrameType.DATA.code(), 0, stream.streamId);
        } catch (RuntimeException failure) {
            framed.close();
            stream.sendWindow.increment(credit);
            connectionWindow.increment(credit);
            throw failure;
        }
        return queueWriteOwned(framed, FRAME_HEADER + credit);
    }

    /**
     * Ends a stream gracefully: an empty DATA frame with END_STREAM, which no window limits, so this
     * never waits. Does nothing for a stream that has already ended.
     *
     * @param stream the response stream
     */
    /* default */ void endStream(Http2OutboundStream stream) {
        lock.lock();
        try {
            if (stream.state != Http2OutboundStream.State.OPEN || connectionClosed) {
                return;
            }
            try (LoanedBuffer frame = allocator.allocateNetwork(FRAME_HEADER)) {
                Http2FrameEncoder.writeHeader(frame.segment(), 0L, 0,
                        Http2FrameType.DATA.code(), FLAG_END_STREAM, stream.streamId);
                frame.setSize(FRAME_HEADER);
                frame.retain();
                queueWriteOwnedOrRethrow(frame, FRAME_HEADER);
            }
            stream.state = Http2OutboundStream.State.END_STREAM_SENT;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sends a complete response on a response stream whose head has not been written, from the
     * stream's own thread: HEADERS and as much of the body as both windows carry in one write, and the
     * rest as DATA once credit allows, which may park. Closes the response body.
     *
     * @param stream   the response stream
     * @param response the response; its body is closed here
     */
    /* default */ void respondOnStream(Http2OutboundStream stream, HttpResponse response) {
        LoanedBuffer body = response.body();
        try (body) {
            int bodyBytes = body == null ? 0 : (int) body.size();
            int now;
            lock.lock();
            try {
                if (stream.state != Http2OutboundStream.State.OPEN || connectionClosed) {
                    return;
                }
                int credit = Math.clamp(Math.min(stream.sendWindow.windowSize(), connectionWindow.windowSize()),
                        0, Integer.MAX_VALUE);
                now = Math.min(bodyBytes, credit);
                writeCoalesced(stream.streamId, response, body, now, bodyBytes, now == bodyBytes);
                stream.headWritten = true;
                stream.sendWindow.consume(now);
                connectionWindow.consume(now);
                if (now == bodyBytes) {
                    stream.state = Http2OutboundStream.State.END_STREAM_SENT;
                    return;
                }
            } finally {
                lock.unlock();
            }
            sendBody(stream, body, now, bodyBytes - now, true);
        }
    }

    /**
     * Resets a response stream from this side.
     *
     * @param stream the response stream
     * @param code the RST_STREAM error code
     */
    /* default */ void resetLocal(Http2OutboundStream stream, Http2ErrorCode code) {
        lock.lock();
        try {
            resetLocalLocked(stream, code);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops tracking a response stream and frees its slot. A stream still open is reset first.
     * Calling it again for the same stream does nothing.
     *
     * @param stream the response stream
     */
    /* default */ void release(Http2OutboundStream stream) {
        lock.lock();
        try {
            if (stream.slot < 0) {
                return;
            }
            if (stream.state == Http2OutboundStream.State.OPEN && !connectionClosed) {
                resetLocalLocked(stream, Http2ErrorCode.INTERNAL_ERROR);
            }
            streamCount--;
            Http2OutboundStream last = streams[streamCount];
            streams[stream.slot] = last;
            last.slot = stream.slot;
            streams[streamCount] = null;
            stream.slot = -1;
            openStreamCount = streamCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Queues one DATA frame, header then payload, with the lock held and {@code credit} already
     * taken. The slice is taken first so that a failure to take it, or to allocate the header, leaves
     * nothing of this frame on the wire; the credit is then returned, since the peer never saw it
     * spent, and the buffers taken so far are closed. Restoring cannot overflow a window: the credit
     * was consumed from it under the same hold.
     *
     * @return {@code false} when the transport failed and the connection has ended
     */
    // CloseResource: both buffers change owner at queueWrite and are closed here on every failure path.
    @SuppressWarnings("PMD.CloseResource")
    private boolean queueDataFrame(Http2OutboundStream stream, LoanedBuffer body, long from,
                                   int credit, int flags) {
        LoanedBuffer part = null;
        LoanedBuffer header = null;
        try {
            part = body.slice(from, credit);
            header = allocator.allocateNetwork(FRAME_HEADER);
            Http2FrameEncoder.writeHeader(header.segment(), 0, credit,
                    Http2FrameType.DATA.code(), flags, stream.streamId);
            header.setSize(FRAME_HEADER);
        } catch (RuntimeException failure) {
            // Nothing of this frame was handed to the transport, so both buffers are still ours.
            if (header != null) {
                header.close();
            }
            if (part != null) {
                part.close();
            }
            stream.sendWindow.increment(credit);
            connectionWindow.increment(credit);
            throw failure;
        }
        if (!queueWriteOwned(header, FRAME_HEADER)) {
            part.close();
            return false;
        }
        return queueWriteOwned(part, credit);
    }

    // ------------------------------------------------------------------ credit

    /**
     * Waits until {@code stream} may send at least one byte, takes up to {@code maxWanted} bytes of both
     * windows, and returns that count with the lock HELD; the caller writes and unlocks. Returns
     * {@link #ACQUIRE_CLOSED} or {@link #ACQUIRE_DEADLINE} without the lock. A non-negative
     * {@code eventsEmitted} makes the first park of this wait a backpressure event.
     */
    private int acquireCredit(Http2OutboundStream stream, boolean admitEvent, int maxWanted,
                              long deadlineEpochMillis, long eventsEmitted) {
        boolean parkRecorded = false;
        while (true) {
            long window;
            boolean windowLimited;
            boolean handedOver = false;
            lock.lock();
            try {
                if (stream.state != Http2OutboundStream.State.OPEN || connectionClosed) {
                    return ACQUIRE_CLOSED;
                }
                window = Math.min(stream.sendWindow.windowSize(), connectionWindow.windowSize());
                boolean capOk = !admitEvent || stream.outstandingBytes.get() < outstandingCapBytes;
                if (window > 0 && capOk) {
                    int credit = (int) Math.min(window, maxWanted);
                    stream.sendWindow.consume(credit);
                    connectionWindow.consume(credit);
                    handedOver = true;
                    return credit;
                }
                windowLimited = window <= 0;
                stream.parkedSender.set(Thread.currentThread());
            } finally {
                if (!handedOver) {
                    lock.unlock();
                }
            }
            // A transport release decrements outstandingBytes and then reads parkedSender, without
            // the lock; this read after publishing parkedSender is the other half of that handshake.
            if (!windowLimited && stream.outstandingBytes.get() < outstandingCapBytes) {
                stream.parkedSender.set(null);
                continue;
            }
            parkRecorded = beforePark(stream, window, eventsEmitted, parkRecorded);
            int outcome = parkSender(stream, deadlineEpochMillis);
            if (outcome != ACQUIRE_RETRY) {
                return outcome;
            }
        }
    }

    /**
     * What a sender does immediately before it parks. The test hook runs first, so a test can apply a
     * change in the gap between "no credit, lock released, re-checked" and "parked": the sender is
     * already published, so the change must find it and leave it a permit. The backpressure event is a
     * single-phase commit made before the park, once per credit wait and not per wakeup.
     *
     * @return whether the backpressure event has been recorded for this wait
     */
    private boolean beforePark(Http2OutboundStream stream, long window, long eventsEmitted,
                               boolean alreadyRecorded) {
        Runnable hook = beforeParkHook.get();
        if (hook != null) {
            hook.run();
        }
        if (alreadyRecorded || eventsEmitted < 0) {
            return alreadyRecorded;
        }
        StreamBackpressureParkEvent.emit(transport.streamId(), stream.streamId,
                stream.outstandingBytes.get(), window, eventsEmitted);
        return true;
    }

    /**
     * Parks the calling sender until it is woken, interrupted or past its deadline.
     *
     * @return {@link #ACQUIRE_RETRY} to look at the windows again, else {@link #ACQUIRE_CLOSED} or
     *         {@link #ACQUIRE_DEADLINE}
     */
    private int parkSender(Http2OutboundStream stream, long deadlineEpochMillis) {
        if (deadlineEpochMillis > 0) {
            long remaining = deadlineEpochMillis - System.currentTimeMillis();
            if (remaining <= 0) {
                stream.parkedSender.set(null);
                return ACQUIRE_DEADLINE;
            }
            LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(remaining));
        } else {
            LockSupport.park(this);
        }
        stream.parkedSender.set(null);
        return Thread.currentThread().isInterrupted() ? ACQUIRE_CLOSED : ACQUIRE_RETRY;
    }

    // ------------------------------------------------------------------ helpers, lock held

    private void registerLocked(Http2OutboundStream stream) {
        stream.slot = streamCount;
        streams[streamCount] = stream;
        streamCount++;
        openStreamCount = streamCount;
    }

    private void resetLocalLocked(Http2OutboundStream stream, Http2ErrorCode code) {
        if (stream.state != Http2OutboundStream.State.OPEN) {
            return;
        }
        if (!connectionClosed) {
            writeRstStreamLocked(stream.streamId, code);
        }
        stream.state = Http2OutboundStream.State.RESET_LOCALLY;
        wake(stream);
    }

    /** Writes RST_STREAM; a transport failure ends the connection and is not rethrown. */
    private void writeRstStreamLocked(int streamId, Http2ErrorCode code) {
        try (LoanedBuffer rst = allocator.allocateNetwork(CONTROL_FRAME_BYTES)) {
            long written = Http2FrameEncoder.writeRstStream(rst.segment(), 0, streamId, code.code());
            rst.setSize(written);
            rst.retain();
            queueWriteOwned(rst, (int) written);
        } catch (RuntimeException _) {
            failTransportLocked();
        }
    }

    /**
     * Ends the connection after the transport failed on a write, and closes the transport: the frame
     * loop is parked reading the same stream, and a stream whose write path has failed does not
     * necessarily wake its reader, so without the close the loop would wait for a peer that can no
     * longer be answered.
     */
    private void failTransportLocked() {
        failConnectionLocked();
        try {
            transport.close();
        } catch (RuntimeException _) {
            // Already failing: the close exists to wake the reader, and a stream that cannot close
            // has nothing more to wake.
        }
    }

    private void failConnectionLocked() {
        connectionClosed = true;
        for (int i = 0; i < streamCount; i++) {
            Http2OutboundStream stream = streams[i];
            if (stream.state == Http2OutboundStream.State.OPEN) {
                stream.state = Http2OutboundStream.State.CONNECTION_CLOSED;
                wake(stream);
            }
        }
    }

    private static void wake(Http2OutboundStream stream) {
        Thread thread = stream.parkedSender.get();
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    private Http2OutboundStream find(int streamId) {
        for (int i = 0; i < streamCount; i++) {
            Http2OutboundStream stream = streams[i];
            if (stream.streamId == streamId) {
                return stream;
            }
        }
        return null;
    }

    /**
     * Hands {@code buf} to the transport like {@link #queueWriteOwned}, but a failure is rethrown
     * after the connection has ended, for the senders whose caller must learn the write was lost.
     */
    private void queueWriteOwnedOrRethrow(LoanedBuffer buf, int len) {
        try {
            transport.queueWrite(buf, len);
        } catch (RuntimeException failure) {
            // The transport hands the loan back to the caller on any throw (TransportStream#queueWrite).
            buf.close();
            failTransportLocked();
            throw failure;
        }
    }

    private boolean queueWriteOwned(LoanedBuffer buf, int len) {
        try {
            transport.queueWrite(buf, len);
            return true;
        } catch (RuntimeException _) {
            // The transport hands the loan back to the caller on any throw (TransportStream#queueWrite).
            buf.close();
            failTransportLocked();
            return false;
        }
    }

    /**
     * Serializes the head and the first {@code now} body bytes into one buffer and writes it once,
     * so the TLS path does one wrap and the reactor one outbound enqueue per response. END_STREAM
     * goes on the HEADERS frame of a bodiless response, otherwise on the last DATA frame when the
     * whole body is in this write, and nowhere when a continuation will finish it.
     */
    private void writeCoalesced(int streamId, HttpResponse response, LoanedBuffer body,
                                int now, int bodyBytes, boolean complete) {
        try (LoanedBuffer headerBlock = allocator.allocateNetwork(HEADER_BLOCK_BYTES)) {
            int headerBytes = (int) encodeResponseHeaders(headerBlock.segment(), response);
            boolean headerEndsStream = bodyBytes == 0;
            int dataFrames = now == 0 ? 0 : frameCount(now);
            long framedSize = (long) headerBytes + now
                    + (long) FRAME_HEADER * (frameCount(headerBytes) + dataFrames);
            try (LoanedBuffer outbound = allocator.allocateNetwork((int) framedSize)) {
                long position = serializeHeaderBlockFrames(
                        outbound.segment(), 0L, streamId, headerBlock.segment(), headerBytes,
                        headerEndsStream);
                if (now > 0) {
                    position = serializeDataFrames(
                            outbound.segment(), position, streamId, body.segment(), now, complete);
                }
                outbound.retain();
                queueWriteOwnedOrRethrow(outbound, (int) position);
            }
        }
    }

    // CloseResource: the body is only measured, and belongs to the response.
    @SuppressWarnings("PMD.CloseResource")
    private long encodeResponseHeaders(MemorySegment target, HttpResponse response) {
        long position = encoder.encodeHeader(
                target, 0, ":status", Integer.toString(response.status().code()), false);

        boolean hasContentLength = false;
        for (HttpHeader header : response.headers()) {
            if (header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                hasContentLength = true;
            }
            if (Http2SessionContext.isConnectionSpecificHeader(header.name())
                    || header.name().startsWith(":")
                    || licenceMarker && WorkloadAdmission.LICENCE_HEADER.equalsIgnoreCase(header.name().strip())) {
                continue;
            }
            position = encoder.encodeHeader(
                    target, position, header.name().toLowerCase(Locale.ROOT), header.value(), false);
        }

        if (licenceMarker) {
            position = encoder.encodeHeader(
                    target, position, LICENCE_HEADER_LOWER, WorkloadAdmission.DEVELOPMENT_MARKER, false);
        }

        LoanedBuffer body = response.body();
        if (!hasContentLength) {
            int bodyBytes = body == null ? 0 : (int) body.size();
            position = encoder.encodeHeader(
                    target, position, "content-length", Integer.toString(bodyBytes), false);
        }
        return position;
    }

    /**
     * Number of HTTP/2 frames a payload of {@code payloadLength} bytes spans — zero when empty, to
     * match the serialize loops (which emit no frame for a zero-length payload). Used only to size
     * the coalescing buffer; the actual written length is the position returned by serialization.
     */
    private static int frameCount(int payloadLength) {
        if (payloadLength <= 0) {
            return 0;
        }
        return (payloadLength + MAX_FRAME_PAYLOAD_BYTES - 1) / MAX_FRAME_PAYLOAD_BYTES;
    }

    private static long serializeHeaderBlockFrames(MemorySegment target,
                                                   long position,
                                                   int streamId,
                                                   MemorySegment headerBlock,
                                                   int headerLength,
                                                   boolean endStream) {
        int written = 0;
        boolean firstFrame = true;
        long pos = position;
        while (written < headerLength) {
            int chunk = Math.min(MAX_FRAME_PAYLOAD_BYTES, headerLength - written);
            boolean last = (written + chunk) == headerLength;
            int type = firstFrame ? Http2FrameType.HEADERS.code() : Http2FrameType.CONTINUATION.code();
            int flags = last ? FLAG_END_HEADERS : 0;
            if (firstFrame && endStream) {
                flags |= FLAG_END_STREAM;
            }
            pos = serializeFrame(target, pos, type, flags, streamId, headerBlock, written, chunk);
            written += chunk;
            firstFrame = false;
        }
        return pos;
    }

    private static long serializeDataFrames(MemorySegment target,
                                            long position,
                                            int streamId,
                                            MemorySegment body,
                                            int bodyLength,
                                            boolean endStreamOnLast) {
        int written = 0;
        long pos = position;
        while (written < bodyLength) {
            int chunk = Math.min(MAX_FRAME_PAYLOAD_BYTES, bodyLength - written);
            boolean endStream = endStreamOnLast && (written + chunk) == bodyLength;
            int flags = endStream ? FLAG_END_STREAM : 0;
            pos = serializeFrame(target, pos, Http2FrameType.DATA.code(), flags, streamId, body, written, chunk);
            written += chunk;
        }
        return pos;
    }

    /**
     * Serializes one frame (9-byte header + payload slice) into {@code target} at {@code position}
     * and returns the position just past it. No socket write — the whole response is written once by
     * the caller (egress coalescing).
     */
    private static long serializeFrame(MemorySegment target,
                                       long position,
                                       int frameType,
                                       int flags,
                                       int streamId,
                                       MemorySegment payloadSource,
                                       int payloadOffset,
                                       int payloadLength) {
        Http2FrameEncoder.writeHeader(target, position, payloadLength, frameType, flags, streamId);
        long payloadPos = position + FRAME_HEADER;
        if (payloadLength > 0) {
            MemorySegment.copy(payloadSource, payloadOffset, target, payloadPos, payloadLength);
        }
        return payloadPos + payloadLength;
    }
}
