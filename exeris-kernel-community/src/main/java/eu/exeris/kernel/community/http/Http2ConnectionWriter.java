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
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
 *   <li><b>A transport per-stream cap bounds queued bytes beyond the window.</b> A stream whose
 *       bytes handed to the transport and not yet released reach the cap does not admit another
 *       event, because a peer can grant a very large window and never read.</li>
 * </ul>
 *
 * <p><b>Allocation:</b> the window and reset methods ({@link #applyConnectionWindowUpdate},
 * {@link #applyStreamWindowUpdate}, {@link #applyInitialWindowSize}, {@link #resetByPeer}) allocate
 * nothing while the lock is uncontended; a contended acquire allocates a queue node. A respond-once
 * response allocates what it did before; a body frame allocates its header buffer and a slice handle.
 * <p><b>Thread confinement:</b> none — every method may be called from any thread; the lock orders
 * the writers.
 * <p><b>Ownership:</b> buffers handed to {@code queueWrite} belong to the transport; the writer closes
 * every buffer it allocates and does not hand over.
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
    private static final int FLAG_END_STREAM = 0x01;
    private static final int FLAG_END_HEADERS = 0x04;
    private static final int ACQUIRE_CLOSED = -1;
    private static final int ACQUIRE_DEADLINE = -2;
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";

    private final TransportStream transport;
    private final MemoryAllocator allocator;
    private final ReentrantLock lock = new ReentrantLock();
    private final HpackDynamicTable encodeTable;
    private final HpackEncoder encoder;
    private final Http2FlowController connectionWindow = new Http2FlowController();
    private final Http2OutboundStream[] streams;
    private final int outstandingCapBytes;
    private int streamCount;
    private volatile int openStreamCount;
    private int peerInitialWindowSize = Http2FlowController.DEFAULT_WINDOW_SIZE;
    private boolean connectionClosed;
    private volatile Runnable beforeParkHook;

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
                                  int outstandingCapBytes) {
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
        return new Http2ConnectionWriter(transport, allocator, maxStreams, outstandingCapBytes);
    }

    // ------------------------------------------------------------------ any thread

    /**
     * Writes one complete frame, or several, as a single hold of the lock. Does nothing once the
     * connection has ended.
     *
     * @param frame  the bytes to write
     * @param length how many bytes of {@code frame} to write
     */
    /* default */ void writeFrame(MemorySegment frame, int length) {
        lock.lock();
        try {
            if (connectionClosed) {
                return;
            }
            try {
                transport.write(frame, length);
            } catch (RuntimeException failure) {
                failConnectionLocked();
                throw failure;
            }
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
     * Test seam: installs a hook that runs on a sender thread as the first thing after it has
     * released the lock on finding no credit, before it parks. The sender is already published as
     * parked there, so whatever the hook applies must find and wake it.
     *
     * @param hook the hook, or {@code null} to remove it
     */
    /* default */ void beforeParkForTest(Runnable hook) {
        this.beforeParkHook = hook;
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
                } catch (IllegalStateException overflow) {
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
     * @return the stream, or {@code null} when the connection has ended, the table is full, or the
     *         credit overflows the window (the stream is then reset with FLOW_CONTROL_ERROR)
     */
    /* default */ Http2OutboundStream register(int streamId, long pendingIncrement) {
        lock.lock();
        try {
            if (connectionClosed || streamCount == streams.length) {
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
            long streamWindow = (long) peerInitialWindowSize + pendingIncrement;
            if (streamWindow > Http2FlowController.MAX_WINDOW_SIZE) {
                writeRstStreamLocked(streamId, Http2ErrorCode.FLOW_CONTROL_ERROR);
                return null;
            }
            long credit = Math.max(0L, Math.min(streamWindow, connectionWindow.windowSize()));
            int now = (int) Math.min(bodyBytes, credit);
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
                    Math.min(length - sent, MAX_FRAME_PAYLOAD_BYTES), 0L);
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
     * {@link #ACQUIRE_CLOSED} or {@link #ACQUIRE_DEADLINE} without the lock.
     */
    // NPathComplexity: the credit-and-park loop is one transition table whose lock hand-over cannot be
    // split across methods without obscuring it.
    @SuppressWarnings("PMD.NPathComplexity")
    private int acquireCredit(Http2OutboundStream stream, boolean admitEvent, int maxWanted,
                              long deadlineEpochMillis) {
        while (true) {
            long window;
            boolean windowLimited;
            boolean handedOver = false;
            boolean publishedForPark = false;
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
                stream.parkedSender = Thread.currentThread();
                publishedForPark = true;
            } finally {
                if (!handedOver) {
                    lock.unlock();
                }
                // First thing after the lock is released, so a test can apply a change in the gap
                // between "no credit, lock released" and "parked": the sender must already be
                // published for that change to find it.
                if (publishedForPark) {
                    Runnable hook = beforeParkHook;
                    if (hook != null) {
                        hook.run();
                    }
                }
            }
            // A transport release decrements outstandingBytes and then reads parkedSender, without
            // the lock; this read after publishing parkedSender is the other half of that handshake.
            if (!windowLimited && stream.outstandingBytes.get() < outstandingCapBytes) {
                stream.parkedSender = null;
                continue;
            }
            if (deadlineEpochMillis > 0) {
                long remaining = deadlineEpochMillis - System.currentTimeMillis();
                if (remaining <= 0) {
                    stream.parkedSender = null;
                    return ACQUIRE_DEADLINE;
                }
                LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(remaining));
            } else {
                LockSupport.park(this);
            }
            stream.parkedSender = null;
            if (Thread.currentThread().isInterrupted()) {
                return ACQUIRE_CLOSED;
            }
        }
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
            transport.write(rst.segment(), (int) written);
        } catch (RuntimeException failure) {
            failConnectionLocked();
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
        Thread thread = stream.parkedSender;
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

    private boolean queueWriteOwned(LoanedBuffer buf, int len) {
        try {
            transport.queueWrite(buf, len);
            return true;
        } catch (RuntimeException failure) {
            buf.close();
            failConnectionLocked();
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
                try {
                    transport.write(outbound.segment(), (int) position);
                } catch (RuntimeException failure) {
                    failConnectionLocked();
                    throw failure;
                }
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
                    || header.name().startsWith(":")) {
                continue;
            }
            position = encoder.encodeHeader(
                    target, position, header.name().toLowerCase(Locale.ROOT), header.value(), false);
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
