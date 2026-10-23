/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityTlsEngine;
import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.core.transport.jfr.ConnectionEstablishedEvent;
import eu.exeris.kernel.core.transport.jfr.TransportIngressQueueDepthEvent;
import eu.exeris.kernel.core.transport.jfr.TransportQueueBackpressureAlertEvent;
import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.jctools.queues.SpscArrayQueue;
import org.jctools.queues.SpscUnboundedArrayQueue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Community TCP stream backed by a single socket file descriptor.
 *
 * <p>Ingress is slab-backed: the carrier's reactor thread reads directly into a
 * {@link LoanedBuffer} and enqueues the slab for the stream's virtual thread to consume.
 *
 * <h2>Collaborators</h2>
 * <p>Plain-socket I/O dispatch (core-socket seam vs NIO fallback) lives in
 * {@link NativeTcpStreamPlainSocketIo}; single-consumer-gate helpers live in
 * {@link NativeTcpStreamConsumerGate}; outbound pending-write records (plain
 * + lazy ciphertext + offsets) live in {@link NativeTcpStreamPendingWrite};
 * the TLS handshake state machine and its recorded failure live in
 * {@link NativeTcpStreamTlsHandshake}. This class itself holds the
 * {@link TransportStream} contract, carrier-facing callbacks, inbound state,
 * and close orchestration.
 *
 * <p><b>Allocation:</b> on the TLS fd-owner ingress path, allocates one {@link LoanedBuffer} per
 * record in {@link #readTlsIngressFromFd()}; on the plain path, the carrier's {@code readIngress}
 * allocates the inbound buffer and this class only retains a reference to it. Every write that
 * cannot be sent directly ({@link #enqueueDeferredWrite}, or {@link #write} on the TLS branch)
 * allocates one {@link LoanedBuffer} to hold a private copy of the caller's bytes before queueing
 * it: one per chunk of at most half the outbound bound for the bytes the socket refuses (plain) or
 * for every write (TLS), and one for the whole remainder when the bound is off. A TLS stream also
 * allocates one per-stream ciphertext placeholder at construction, reused for every unwrap/wrap
 * rather than allocated per record, plus small transient buffers for the handshake and shutdown.
 * <p><b>Thread confinement:</b> not confined to one thread over its lifetime, but single-consumer
 * per direction at any instant — {@link NativeTcpStreamConsumerGate} lets exactly one thread at a
 * time drain the inbound queue and exactly one (possibly different) thread at a time drain the
 * outbound queue, so ingress and egress may run concurrently on different threads but never two
 * readers or two writers at once. {@link #write} may park its caller while the outbound queue is at
 * its bound, and never parks a reactor thread.
 * <p><b>Ownership:</b> every {@link LoanedBuffer} handed to this stream (via {@link #offerIngress}
 * or a queued write) is closed by whichever consumer drains it — inbound buffers in
 * {@link #closeCurrentInbound()} / {@link #drainInboundQueue()}, outbound ones in
 * {@link NativeTcpStreamPendingWrite#close()} — and {@link #finishCloseIfDrained()} releases every
 * buffer still queued at close time once it holds (or can idly acquire) the outbound consumer slot;
 * if a different thread still holds that slot after {@link #close()}'s bounded wait, releasing the
 * queued buffers is deferred to that thread's own subsequent call, not performed by this one.
 *
 * <h2>TLS handshake failure</h2>
 * <p>A TLS handshake failure is recorded under the TLS lock by whichever thread observes it, before
 * the stream closes; every path that then reports the stream closed or at end-of-stream throws the
 * recorded failure instead, as a {@link TlsHandshakeException} ({@code EX-NET-2001}) built on the
 * throwing thread. No handshake step runs after a failure is recorded.
 *
 * @since 0.5
 */
// QA-016 extracted 4 seams (PlainSocketIo, ConsumerGate, PendingWrite, TlsHandshake); residual
// inbound/outbound machinery remains cohesive. QA-016b can extract inbound state if WMC threshold
// drives a follow-up split.
@SuppressWarnings({
    "PMD.GodClass",
    "PMD.CyclomaticComplexity",                // multi-state read/drain/handshake loops are intrinsically cohesive.
    "PMD.TooManyMethods",                      // lifecycle + carrier callbacks + handshake + close orchestration.
    "PMD.CloseResource",                       // LoanedBuffer ownership transfers via retain (PERF-062).
    "PMD.AvoidBranchingStatementAsLastInLoop", // continue-then-return idiom in advanceInbound state machine.
    "PMD.AvoidCatchingGenericException",       // best-effort cleanup paths must absorb RuntimeException.
    "PMD.NullAssignment",                      // currentInbound reset to null after close() to release reference.
    "PMD.CouplingBetweenObjects"               // socket, TLS, memory, JFR and queue collaborators of one stream.
})
final class NativeTcpStream implements TransportStream {

    private static final System.Logger LOG = System.getLogger(NativeTcpStream.class.getName());
    private static final long CLOSE_OUTBOUND_CONSUMER_BACKOFF_NANOS = 100_000L;
    private static final int CLOSE_OUTBOUND_CONSUMER_DRAIN_ATTEMPTS = 32;

    // Phase 1B: TLS ingress queue monitoring thresholds and backpressure control
    private static final boolean QUEUE_BACKPRESSURE_ENABLED =
            Boolean.parseBoolean(System.getProperty("exeris.transport.queueBackpressureEnabled", "false"));
    private static final int QUEUE_DEPTH_THRESHOLD_LOW = 100;
    private static final int QUEUE_DEPTH_THRESHOLD_MID = 500;
    private static final int QUEUE_DEPTH_THRESHOLD_HIGH = 1000;
    private static final int JCTOOLS_QUEUE_CHUNK_SIZE = 128;
    private static final int INBOUND_QUEUE_CAPACITY =
            QUEUE_BACKPRESSURE_ENABLED ? QUEUE_DEPTH_THRESHOLD_HIGH : Integer.MAX_VALUE;

    private static final int DRAIN_OK      = NativeTcpStreamTlsDrain.DRAIN_OK;
    private static final int DRAIN_STALLED = NativeTcpStreamTlsDrain.DRAIN_STALLED;

    private static final int INBOUND_ADVANCE_NEEDS_MORE_DATA = -1;
    private static final int INBOUND_ADVANCE_RETRY = 0;
    private static final int INBOUND_ADVANCE_READY = 1;

    private final String engineName;
    private final long streamId;
    private final SocketChannel channel;
    private final NativeTcpConnection connection;
    private final MemoryAllocator allocator;
    private final TlsEngine tlsEngine;
    private final Runnable writeInterestCallback;
    private final Runnable closeCallback;
    private final SyscallHandles socketHandles;
    private final int plainSocketHandle;
    private final NativeTcpStreamPlainSocketIo.Backend plainSocketBackend;
    private final NativeTcpStreamPendingWrite.TlsContext pendingWriteTlsContext;
    private final NativeTcpStreamPendingWrite.TryWriter pendingWriteTryWriter;
    private final NativeTcpStreamTlsHandshake tlsHandshake;
    private final NativeTcpStreamTlsDrain tlsDrain;

    // PERF: fd-owner BIO unwrap pulls ciphertext directly from the kernel socket and never
    // inspects its {@code ciphertext} parameter (OffHeapTlsEngine#unwrap contract). A single
    // per-stream empty placeholder is reused for every ingress record instead of allocating one
    // per read, removing the dominant per-request LoanedBuffer/ReleaseAction churn on the TLS hot
    // path. Owned by this stream; released in finishCloseIfDrained(). null for non-TLS streams.
    private final LoanedBuffer tlsCiphertextPlaceholder;

    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean remoteClosed = new AtomicBoolean(false);
    // Set when the remote end (or a carrier teardown) is observed while no local close was requested:
    // the first side to end the stream decides which closed-stream code it reports.
    private final AtomicBoolean peerEndedFirst = new AtomicBoolean(false);
    // Set by reset(long): abandon queued writes (no drain wait) and terminate abortively (RST).
    private final AtomicBoolean resetRequested = new AtomicBoolean(false);
    // Set only by a send-failure abort after the peer ended the stream: bytes the peer sent first
    // stay readable. A local close() or reset(long) clears it and discards what is queued.
    private final AtomicBoolean inboundOutlivesAbort = new AtomicBoolean(false);
    // Last time the application moved bytes on this connection, for idle reclamation
    // (transport.idleTimeoutMillis, swept by NativeTcpIdleReaper on the owning reactor).
    // "Activity" is an attempted read or queued write, not bytes observed on the wire: a peer that
    // opens a connection and then sends nothing never reaches either, which is what makes the same
    // stamp bound a slow-loris hold as well as an idle keep-alive. Volatile because the writer is
    // whichever virtual thread served the request and the reader is the reactor thread; a plain
    // long would let a reactor read an indefinitely stale stamp and reclaim a live connection.
    private volatile long lastActivityNanos = System.nanoTime();
    private final Queue<NativeTcpStreamPendingWrite> outboundQueue =
            new MpscUnboundedArrayQueue<>(JCTOOLS_QUEUE_CHUNK_SIZE);
    private final Queue<LoanedBuffer> inboundQueue = QUEUE_BACKPRESSURE_ENABLED
            ? new SpscArrayQueue<>(INBOUND_QUEUE_CAPACITY)
            : new SpscUnboundedArrayQueue<>(JCTOOLS_QUEUE_CHUNK_SIZE);
    private final AtomicInteger outboundQueueDepth = new AtomicInteger(0);
    // Bytes of every pending write, from write() and queueWrite alike, and the writer parked on them.
    private final NativeTcpStreamWriteBound writeBound;
    private final AtomicInteger inboundQueueDepth = new AtomicInteger(0);
    // One-shot latch + callback fired exactly once when the connection becomes established
    // (plaintext: reactor registration armed; TLS: reactor-driven handshake reached ACTIVE).
    // Replaces the acceptor thread blocking on the handshake — see fireEstablishedOnce().
    private final AtomicBoolean establishedFired = new AtomicBoolean(false);
    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile Runnable onEstablishedCallback;
    private volatile long registrationReadyNanos;
    private final Object tlsLock = new Object();
    private final StreamRuntimeState runtime = new StreamRuntimeState();
    
    // Phase 1B: Queue depth tracking for telemetry and backpressure. Read/written only on the
    // reactor thread (offerIngress) and feeds an advisory JFR "trend" label only — not shared, so
    // no volatile fence is warranted on this per-record hot path.
    private int lastQueueDepth;

    private LoanedBuffer currentInbound;
    private int currentInboundOffset;

    /* default */ NativeTcpStream(String engineName,
                    long streamId,
                    SocketChannel channel,
                    NativeTcpConnection connection,
                    MemoryAllocator allocator,
                    TlsEngine tlsEngine,
                    Runnable writeInterestCallback,
                    Runnable closeCallback) {
        this(engineName,
                streamId,
                channel,
                connection,
                allocator,
                tlsEngine,
                writeInterestCallback,
                closeCallback,
                null);
    }

    /* default */ NativeTcpStream(String engineName,
                    long streamId,
                    SocketChannel channel,
                    NativeTcpConnection connection,
                    MemoryAllocator allocator,
                    TlsEngine tlsEngine,
                    Runnable writeInterestCallback,
                    Runnable closeCallback,
                    SyscallHandles socketHandles) {
        this(engineName, streamId, channel, connection, allocator, tlsEngine,
                writeInterestCallback, closeCallback, socketHandles, NativeTcpHandshakeBackoff.PARK);
    }

    @SuppressWarnings("PMD.ExcessiveParameterList") // the canonical constructor minus the write bound
    /* default */ NativeTcpStream(String engineName,
                    long streamId,
                    SocketChannel channel,
                    NativeTcpConnection connection,
                    MemoryAllocator allocator,
                    TlsEngine tlsEngine,
                    Runnable writeInterestCallback,
                    Runnable closeCallback,
                    SyscallHandles socketHandles,
                    NativeTcpHandshakeBackoff handshakeBackoff) {
        this(engineName, streamId, channel, connection, allocator, tlsEngine,
                writeInterestCallback, closeCallback, socketHandles, handshakeBackoff,
                TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES);
    }

    @SuppressWarnings("PMD.ExcessiveParameterList") // the canonical constructor plus the backoff and bound
    /* default */ NativeTcpStream(String engineName,
                    long streamId,
                    SocketChannel channel,
                    NativeTcpConnection connection,
                    MemoryAllocator allocator,
                    TlsEngine tlsEngine,
                    Runnable writeInterestCallback,
                    Runnable closeCallback,
                    SyscallHandles socketHandles,
                    NativeTcpHandshakeBackoff handshakeBackoff,
                    int maxQueuedWriteBytes) {
        Objects.requireNonNull(handshakeBackoff, "handshakeBackoff must not be null");
        this.engineName = Objects.requireNonNull(engineName, "engineName must not be null");
        this.streamId = streamId;
        this.writeBound = new NativeTcpStreamWriteBound(streamId, validBound(maxQueuedWriteBytes), this::ensureOpen);
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.tlsEngine = tlsEngine;
        this.writeInterestCallback = Objects.requireNonNull(
            writeInterestCallback,
            "writeInterestCallback must not be null");
        this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback must not be null");
        this.socketHandles = socketHandles;
        this.plainSocketBackend = NativeTcpStreamPlainSocketIo.Backend.resolve(channel, tlsEngine, socketHandles);
        this.plainSocketHandle = plainSocketBackend.usesCoreSocketSeam()
                ? SocketChannelFdAccess.requireFd(channel)
                : -1;
        if (tlsEngine != null && !(tlsEngine instanceof CommunityTlsEngine)) {
            throw new IllegalArgumentException(
                    "NativeTcpStream only supports socket-owner TLS engines (CommunityTlsEngine); "
                    + "buffer-owner engines are not supported");
        }
        if (tlsEngine == null) {
            this.tlsCiphertextPlaceholder = null;
        } else {
            this.tlsCiphertextPlaceholder = allocator.allocateInfrastructure(1);
            this.tlsCiphertextPlaceholder.setSize(0);
        }
        // The same per-stream empty placeholder serves both ingress unwrap and egress wrap: in
        // fd-owner BIO mode neither dereferences the ciphertext segment (see tlsCiphertextPlaceholder
        // and NativeTcpStreamPendingWrite.prepareCipher). Both run under tlsLock, so no interleave.
        this.pendingWriteTlsContext = tlsEngine == null
                ? null
                : new NativeTcpStreamPendingWrite.TlsContext(
                        tlsEngine, tlsLock, tlsCiphertextPlaceholder, new AtomicBoolean());
        this.pendingWriteTryWriter = this::tryWrite;
        this.tlsHandshake = new NativeTcpStreamTlsHandshake(engineName, tlsEngine, channel, allocator,
                tlsLock, handshakeBackoff, closed, closeRequested, this::close, writeInterestCallback);
        this.tlsDrain = tlsEngine == null ? null : new NativeTcpStreamTlsDrain(
                engineName, remoteClosed, closed, pendingWriteTlsContext.waitingForPeer(), this::requestAbort,
                this::abortOnPeerGone, ((CommunityTlsEngine) tlsEngine)::lastWriteErrno, this::isPeerGone);
    }

    /**
     * Reads up to {@code maxBytes} bytes into {@code target}, parking the calling virtual thread
     * until a slab arrives on the inbound queue, the remote peer closes, or the stream closes.
     *
     * @param target   off-heap segment to read into
     * @param maxBytes maximum number of bytes to read; a value {@code <= 0} is a no-op that
     *                 returns {@code 0}
     * @return the number of bytes read, {@code 0} if {@code maxBytes <= 0}, or {@code -1} on EOF
     * @throws IllegalArgumentException if {@code target} is {@code null} or {@code maxBytes}
     *                                   exceeds its capacity
     * @throws TransportStreamClosedException if this stream has been closed and the remote side did
     *                                   not close first ({@code EX-NET-4009}); a closed stream whose
     *                                   remote end was observed reports end of stream instead
     * @throws TlsHandshakeException    if the TLS handshake failed ({@code EX-NET-2001}), including a
     *                                   server certificate that failed verification
     * @throws TransportException       if the TLS handshake does not complete within its timeout
     *                                   ({@code EX-NET-4003})
     */
    @Override
    public int read(MemorySegment target, int maxBytes) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (maxBytes > target.byteSize()) {
            throw new IllegalArgumentException("maxBytes out of range for target segment");
        }
        if (maxBytes <= 0) {
            return 0;
        }
        lastActivityNanos = System.nanoTime();

        Thread currentThread = Thread.currentThread();
        NativeTcpStreamConsumerGate.acquireSingleConsumer(runtime.inboundConsumer(), currentThread, "inbound");
        try {
            tlsHandshake.throwRecordedFailure();
            if (mustReportClosed()) {
                return closedReadOutcome();
            }
            if (remoteClosed.get() && currentInbound == null && inboundQueueDepth.get() == 0) {
                return -1;
            }
            tlsHandshake.ensureReady(true);

            runtime.streamVt().set(currentThread);

            while (true) {
                if (mustReportClosed()) {
                    return closedReadOutcome();
                }
                int inboundState = advanceInbound();
                if (inboundState == INBOUND_ADVANCE_NEEDS_MORE_DATA) {
                    return -1;
                }
                if (inboundState == INBOUND_ADVANCE_RETRY) {
                    continue;
                }
                return copyFromInbound(target, maxBytes);
            }
        } finally {
            runtime.streamVt().compareAndSet(currentThread, null);
            NativeTcpStreamConsumerGate.releaseSingleConsumer(runtime.inboundConsumer(), currentThread);
        }
    }

    /**
     * Ensures {@link #currentInbound} has data to read.
     * Returns: -1 (remote closed, return -1 to caller), 0 (not ready, continue loop), 1 (ready).
     */
    private int advanceInbound() {
        if (currentInbound == null) {
            int adoptResult = adoptNextInboundFromQueue();
            if (adoptResult != INBOUND_ADVANCE_READY) {
                return adoptResult;
            }
        }
        int available = (int) currentInbound.size() - currentInboundOffset;
        if (available <= 0) {
            closeCurrentInbound();
            return INBOUND_ADVANCE_RETRY;
        }
        return INBOUND_ADVANCE_READY;
    }

    private int adoptNextInboundFromQueue() {
        LoanedBuffer next = inboundQueue.poll();
        if (next == null) {
            if (remoteClosed.get()) {
                return INBOUND_ADVANCE_NEEDS_MORE_DATA;
            }
            awaitReadableIngress();
            return INBOUND_ADVANCE_RETRY;
        }
        decrementDepth(inboundQueueDepth);
        currentInbound = next;
        currentInboundOffset = 0;
        return INBOUND_ADVANCE_READY;
    }

    /**
     * Copies bytes from {@link #currentInbound} to {@code target}. Closes the buffer if
     * fully consumed. Assumes {@code currentInbound} is non-null with available data.
     */
    private int copyFromInbound(MemorySegment target, int maxBytes) {
        int available = (int) currentInbound.size() - currentInboundOffset;
        int bytes = Math.min(available, maxBytes);
        MemorySegment.copy(currentInbound.segment(), currentInboundOffset, target, 0, bytes);
        currentInboundOffset += bytes;
        if (currentInboundOffset >= currentInbound.size()) {
            closeCurrentInbound();
        }
        return bytes;
    }

    /**
     * Writes {@code length} bytes from {@code source}: written straight to the socket with no
     * intermediate copy when nothing is already queued and the stream is plaintext; otherwise
     * copied into buffered pending writes, which a TLS stream always uses and which may still
     * drain before this call returns.
     *
     * <p>May park the caller while the stream's outbound queue is at its bound, until the peer has
     * taken enough bytes or the stream ends; it never parks a reactor thread, and a write made on one
     * queues past the bound instead. A write that throws after parking may already have queued a
     * prefix of {@code source}.
     *
     * @param source off-heap segment containing the data to send; this stream never retains a
     *               reference to it after the call returns
     * @param length number of bytes to write, from offset {@code 0}; {@code 0} is a no-op
     * @throws IllegalArgumentException if {@code source} is {@code null} or {@code length} is out
     *                                   of range for it
     * @throws TransportStreamClosedException if this stream has been closed ({@code EX-NET-4008} when
     *                                   the carrier observed the remote end first, {@code EX-NET-4009}
     *                                   otherwise)
     * @throws TlsHandshakeException    if the TLS handshake failed ({@code EX-NET-2001}), including a
     *                                   server certificate that failed verification
     * @throws TransportException       if an unrecoverable send failure occurs ({@code EX-NET-4002})
     */
    @Override
    public void write(MemorySegment source, int length) {
        ensureOpen();
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (length < 0 || length > source.byteSize()) {
            throw new IllegalArgumentException("length out of range for source segment");
        }
        if (length == 0) {
            return;
        }
        // Both public egress entries stamp. write() reaches queueWrite ONLY on the TLS branch, so
        // with a stamp in queueWrite alone every plaintext response — which is
        // CommunityHttpExchange's path, and the default one — would move bytes without counting as
        // activity, and idle reclamation would become a property of whether TLS is configured.
        lastActivityNanos = System.nanoTime();

        if (tlsEngine == null) {
            if (hasPendingData()) {
                enqueueBounded(source, 0, length);
            } else {
                writeDirect(source, length);
            }
            return;
        }

        requireTlsReady();
        int offset = 0;
        while (offset < length) {
            writeBound.awaitCapacity();
            int chunk = Math.min(length - offset, writeBound.chunkBytes());
            queueTlsCopy(source, offset, chunk);
            offset += chunk;
        }
    }

    private void queueTlsCopy(MemorySegment source, int offset, int length) {
        try (LoanedBuffer plain = allocator.allocateNetwork(length)) {
            MemorySegment.copy(source, offset, plain.segment(), 0, length);
            plain.setSize(length);
            plain.retain();
            try {
                queueWrite(plain, length);
            } catch (RuntimeException failure) {
                // queueWrite keeps the buffer with the caller when it throws, so the retain() above
                // is a ref nothing will ever release: try-with-resources drops the allocation's own
                // ref and leaves this one, and the PARANOID leak detector reports it as a
                // LeakDetectedError rather than the write failure that caused it.
                plain.close();
                throw failure;
            }
        }
    }

    @Override
    public void queueWrite(LoanedBuffer buffer, int length) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        // Neither of these closes the buffer. The SPI puts it back with the caller on any thrown
        // exception — it says so for the closed-stream signal by name — so closing it here too makes
        // the caller's own release a double-free: the SIGSEGV shape the ownership policy exists to
        // rule out, reached by both sides obeying different halves of one contract. The two branches
        // must agree; one of them closing and the other not is the same defect wearing a smaller
        // hat, and in write() — which allocates and retains, so holds two refs — a close landing
        // between ensureOpen() and here would issue three releases against them.
        if (length < 0 || length > buffer.capacity()) {
            throw new IllegalArgumentException("length out of range for loaned buffer");
        }
        lastActivityNanos = System.nanoTime();
        if (closeRequested.get() || closed.get()) {
            tlsHandshake.throwRecordedFailure();
            throw closedSignal();
        }
        if (length == 0) {
            buffer.close();
            return;
        }

        requireTlsReady();
        Thread observedConsumer = runtime.outboundConsumer().get();
        NativeTcpStreamPendingWrite pendingWrite = newPendingWrite(buffer, length);
        boolean queueWasEmpty = enqueuePendingWrite(pendingWrite, false);
        if (!queueWasEmpty) {
            Thread activeConsumer = runtime.outboundConsumer().get();
            if (observedConsumer != null
                    && !Objects.equals(observedConsumer, Thread.currentThread())
                    && activeConsumer == null
                    && needsWriteInterest()) {
                writeInterestCallback.run();
            }
            return;
        }

        try {
            flushPendingWrites();
            // Re-checked after the consumer slot is released, whatever the flush returned. The
            // depth counter is raised before the write reaches the queue, so another producer can
            // sit between its increment and its offer while this flush empties the queue: that
            // producer saw a non-empty depth, so it is not a flusher, and this flush never saw its
            // write. A depth still above zero here hands the stream to the reactor, which keeps
            // write interest armed until it observes the depth at zero.
            if (needsWriteInterest()) {
                writeInterestCallback.run();
            }
        } catch (TransportStreamClosedException ended) {
            // The abort finishes the close after the slot is released; close() would end the survival
            // of the peer's bytes, which only the application's own close or reset may do.
            throw ended;
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    /**
     * Nanosecond stamp of the last read or queued write, for the owning reactor's idle sweep.
     *
     * @return a {@link System#nanoTime()} reading; comparable only by subtraction
     */
    /* default */ long lastActivityNanos() {
        return lastActivityNanos;
    }

    @Override
    public long streamId() {
        return streamId;
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
    public TransportConnection connection() {
        return connection;
    }

    @Override
    public boolean hasPendingData() {
        return outboundQueueDepth.get() > 0;
    }

    /**
     * Closes this stream: wakes any thread waiting to read, write, or complete registration (on
     * every call, not only the first), waits briefly for whichever thread currently holds the
     * outbound consumer slot to yield it, then best-effort flushes any remaining queued writes
     * before finishing the teardown. Idempotent.
     */
    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            wakeAllWaiters();
            finishCloseIfDrained();
            endInboundSurvival();
            return;
        }
        wakeAllWaiters();
        awaitOutboundConsumerToYield();

        if (hasPendingData()) {
            try {
                flushPendingWrites();
            } catch (RuntimeException error) {
                logBestEffortCleanupFailure("flushPendingWrites", error);
            }
            writeInterestCallback.run();
        }

        finishCloseIfDrained();
    }

    /**
     * Abortively terminates the stream: queued writes are abandoned rather than drained, and the
     * socket is closed with {@code SO_LINGER} {@code 0} so the peer observes a TCP RST instead of
     * a graceful FIN (best effort — if the socket option cannot be set, the terminal close falls
     * back to an ordinary FIN).
     *
     * @param errorCode advisory only; the Community TCP driver has no protocol-level channel to
     *                   carry it, unlike a transport whose own stream reset is a protocol frame
     */
    @Override
    public void reset(long errorCode) {
        // Idempotent abortive termination (TransportStream#reset). Distinct from close():
        // queued writes are abandoned (not drained) and the socket is closed with SO_LINGER 0
        // so the peer observes a RST rather than a graceful FIN. errorCode is advisory for TCP.
        if (!resetRequested.compareAndSet(false, true)) {
            wakeAllWaiters();
            finishCloseIfDrained();
            endInboundSurvival();
            if (!closed.get()) {
                writeInterestCallback.run();
            }
            return;
        }
        closeRequested.set(true);
        wakeAllWaiters();
        try {
            channel.setOption(StandardSocketOptions.SO_LINGER, 0);
        } catch (IOException | RuntimeException _) {
            // Best effort: if SO_LINGER can't be set, the terminal close falls back to FIN.
        }
        // Terminate now if this thread owns (or no one owns) the outbound consumer; otherwise the
        // owning carrier reactor completes the abort on its next cycle (closeRequested + the
        // resetRequested gate in finishCloseIfDrained).
        finishCloseIfDrained();
        if (!closed.get()) {
            // The carrier reactor still owns the consumer slot — wake it to finalize the deferred
            // abort. If finishCloseIfDrained() already closed the stream, the channel is
            // deregistered and no wake is needed.
            writeInterestCallback.run();
        }
    }

    // ---------------------------------------------------------------------
    // Test-only seams (package-private; used by CommunityTransportTestHarness
    // to make the AbstractTransportStreamTck reset()-abandon discriminator
    // deterministic — see issue #180). NOT part of the production API.
    // ---------------------------------------------------------------------

    /**
     * Test-only: pins the outbound single-consumer slot to {@code owner} so a concurrent
     * {@link #queueWrite} cannot synchronously flush the queued write (it stays genuinely
     * pending). Returns {@code true} once {@code owner} holds the slot.
     */
    /* default */ boolean tryPinOutboundConsumerForTest(Thread owner) {
        return NativeTcpStreamConsumerGate.tryAcquireSingleConsumer(runtime.outboundConsumer(), owner);
    }

    /** Test-only: releases a slot taken by {@link #tryPinOutboundConsumerForTest}. */
    /* default */ void unpinOutboundConsumerForTest(Thread owner) {
        NativeTcpStreamConsumerGate.releaseSingleConsumer(runtime.outboundConsumer(), owner);
    }

    /** Test-only: registers {@code waiter} as the parked reader a terminal transition has to wake. */
    /* default */ void registerReaderWaiterForTest(Thread waiter) {
        runtime.streamVt().set(waiter);
    }

    /**
     * Test-only: completes a requested close or reset while {@code owner} still holds the outbound
     * slot, then releases it. A reset discards the queued write before the reactor can flush it; a
     * graceful close stays deferred while data is queued and the reactor drains it after the release.
     */
    /* default */ void completeRequestedCloseAndUnpinOutboundConsumerForTest(Thread owner) {
        assert closeRequested.get()
                : "completeRequestedCloseAndUnpinOutboundConsumerForTest requires a prior close() or "
                + "reset() — completing a close while holding the consumer slot without a close "
                + "request would tear down a still-live stream";
        try {
            finishCloseIfDrained();
        } finally {
            NativeTcpStreamConsumerGate.releaseSingleConsumer(runtime.outboundConsumer(), owner);
        }
    }

    /**
     * Test-only: performs the first half of an enqueue — the depth increment — without offering
     * a write, leaving the stream in the state of a producer suspended between the two. Pair with
     * {@link #publishReservedWriteForTest}. Not called from production code.
     */
    /* default */ void reserveOutboundWriteForTest() {
        outboundQueueDepth.getAndIncrement();
    }

    /**
     * Test-only: the second half of an enqueue begun by {@link #reserveOutboundWriteForTest()} —
     * offers the write and returns without flushing or signalling, as a producer that observed a
     * non-empty queue and no consumer does. Takes ownership of {@code buffer}.
     *
     * @return whether the queue accepted the write
     */
    /* default */ boolean publishReservedWriteForTest(LoanedBuffer buffer, int length) {
        NativeTcpStreamPendingWrite pendingWrite = newPendingWrite(buffer, length);
        writeBound.add(length);
        boolean offered = outboundQueue.offer(pendingWrite);
        if (!offered) {
            writeBound.add(-length);
        }
        return offered;
    }

    private static int validBound(int bound) {
        if (bound < 1 && bound != TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES) {
            throw new IllegalArgumentException("maxQueuedWriteBytes must be >= 1, or -1 for no bound, got: " + bound);
        }
        return bound;
    }

    /** The outbound bound this stream was built with; {@code -1} when there is none. */
    /* default */ int maxQueuedWriteBytes() {
        return writeBound.maxQueuedWriteBytes();
    }

    /** Test-only: bytes currently held in the outbound queue, from both write entry points. */
    /* default */ long outboundQueuedBytesForTest() {
        return writeBound.queuedBytes();
    }

    /* default */ void offerIngress(LoanedBuffer ingressBuffer) {
        if (closed.get()) {
            try (ingressBuffer) {
                return;
            }
        }

        int currentQueueDepth = inboundQueueDepth.get();
        String trend = currentQueueDepth >= lastQueueDepth ? "up" : "down";
        if (currentQueueDepth >= QUEUE_DEPTH_THRESHOLD_HIGH) {
            TransportIngressQueueDepthEvent.emit(streamId, currentQueueDepth, QUEUE_DEPTH_THRESHOLD_HIGH, trend);
        } else if (currentQueueDepth >= QUEUE_DEPTH_THRESHOLD_MID) {
            TransportIngressQueueDepthEvent.emit(streamId, currentQueueDepth, QUEUE_DEPTH_THRESHOLD_MID, trend);
        } else if (currentQueueDepth >= QUEUE_DEPTH_THRESHOLD_LOW) {
            TransportIngressQueueDepthEvent.emit(streamId, currentQueueDepth, QUEUE_DEPTH_THRESHOLD_LOW, trend);
        }

        int reservedDepth = inboundQueueDepth.incrementAndGet();
        if (QUEUE_BACKPRESSURE_ENABLED && reservedDepth > INBOUND_QUEUE_CAPACITY) {
            try (ingressBuffer) {
                decrementDepth(inboundQueueDepth);
                TransportQueueBackpressureAlertEvent.emit(1, reservedDepth, trend);
                throw new IllegalStateException("Rejected inbound buffer due to backpressure for stream " + streamId);
            }
        }

        boolean offered = inboundQueue.offer(ingressBuffer);
        if (!offered) {
            try (ingressBuffer) {
                decrementDepth(inboundQueueDepth);
                if (QUEUE_BACKPRESSURE_ENABLED) {
                    TransportQueueBackpressureAlertEvent.emit(1, reservedDepth, trend);
                }
                throw new IllegalStateException("Rejected inbound buffer due to backpressure for stream " + streamId);
            }
        }
        lastQueueDepth = reservedDepth;
        signalReadableIngress();
    }

    /* default */ void markRemoteClosed() {
        if (!closeRequested.get()) {
            peerEndedFirst.set(true);
        }
        remoteClosed.set(true);
        connection.markClosedByCarrier();
        wakeAllWaiters();
    }

    /* default */ void markRegistrationPending() {
        runtime.registrationGate().markPending();
    }

    /* default */ void markRegistrationReady() {
        runtime.registrationGate().markReady();
        registrationReadyNanos = System.nanoTime();
        if (tlsEngine == null) {
            // Plaintext has no handshake: the connection is established the moment the reactor
            // has armed the key. (TLS defers to fireEstablishedOnce() in readTlsIngressFromFd
            // once the reactor-driven handshake reaches ACTIVE.)
            fireEstablishedOnce();
        }
    }

    /**
     * Arms the one-shot established callback. Set by the carrier before the registration is
     * enqueued so the reactor can fire it as soon as the connection is established. Replaces
     * the former acceptor-thread {@code awaitHandshakeReadyForConnection()} block, which
     * serialised every TLS handshake on the single acceptor thread.
     */
    /* default */ void onEstablished(Runnable callback) {
        this.onEstablishedCallback = callback;
    }

    /**
     * Fires the established callback at most once. Invoked on the reactor thread; the callback
     * (connection-handler notification + PAQS schedule) is contractually non-blocking
     * ({@code ConnectionHandler} runs on a carrier thread).
     */
    private void fireEstablishedOnce() {
        Runnable callback = onEstablishedCallback;
        if (callback != null && establishedFired.compareAndSet(false, true)) {
            long startNanos = System.nanoTime();
            callback.run();
            // Once per connection (CAS-gated), off the request hot path. handlerDurationNanos
            // surfaces a ConnectionHandler that blocks the reactor in violation of its contract.
            ConnectionEstablishedEvent.emit(
                    streamId,
                    tlsEngine != null,
                    Math.max(0L, startNanos - registrationReadyNanos),
                    System.nanoTime() - startNanos);
        }
    }

    /* default */ boolean isClosed() {
        return isEndingOrEnded();
    }

    /** Whether a close or reset was requested, or the teardown already finished. */
    private boolean isEndingOrEnded() {
        return closeRequested.get() || closed.get();
    }

    /* default */ boolean isRemoteClosed() {
        return remoteClosed.get();
    }

    /* default */ TlsEngine tlsEngine() {
        return tlsEngine;
    }

    /* default */ String plainSocketBackendName() {
        return plainSocketBackend.configValue();
    }

    /* default */ void markTlsBoundFromCarrier() {
        tlsHandshake.markBound();
    }

    /* default */ int readPlainIngress(MemorySegment target, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            return 0;
        }
        if (plainSocketBackend.usesCoreSocketSeam()) {
            return NativeTcpStreamPlainSocketIo.seamRead(socketHandles, plainSocketHandle, target, maxBytes, streamId);
        }
        return NativeTcpStreamPlainSocketIo.nioFallbackRead(channel, target, maxBytes);
    }

    /* default */ boolean usesFdOwnerTls() {
        return tlsEngine instanceof CommunityTlsEngine;
    }

    // PERF-062: try-with-resources is intentionally NOT used here. The success path transfers
    // ownership of `plaintext` to the caller (returned at refcount 1, freed by the queue
    // consumer); only the failure path closes via the `transferred` ownership flag. A
    // try-with-resources would force an extra retain()/close() ceremony per TLS record on the
    // hot ingress path. Same pattern in decryptIngress below.
    @SuppressWarnings("PMD.UseTryWithResources")
    /* default */ LoanedBuffer readTlsIngressFromFd() {
        // After the peer's close an unwrap throws, and a throw here resets the stream over its queued bytes.
        if (tlsEngine == null || remoteClosed.get()) {
            return null;
        }
        if (!tlsHandshake.ensureReady(false)) {
            return null;
        }
        // TLS handshake just reached ACTIVE on the reactor thread: signal the connection as
        // established exactly once (idempotent CAS), driving onConnectionEstablished + PAQS
        // schedule without the acceptor ever blocking on the handshake.
        fireEstablishedOnce();
        // PERF-062: fast-fail before allocation when offerIngress would reject.
        // Saves the slab borrow + TLS unwrap on closing-stream / backpressured paths.
        if (closed.get()) {
            return null;
        }
        if (QUEUE_BACKPRESSURE_ENABLED && inboundQueueDepth.get() >= INBOUND_QUEUE_CAPACITY) {
            return null;
        }

        // PERF: reuse the per-stream empty placeholder — fd-owner unwrap never reads it (see field
        // doc). Only the plaintext slab is allocated per record; the prior per-read placeholder
        // allocate+close pair is gone.
        LoanedBuffer plaintext = allocator.allocateCarrierSlab(0);
        // PERF-062: ownership-transfer flag replaces the prior retain()/auto-close ceremony.
        // The successful return path keeps the buffer alive at refcount 1 without an extra
        // retain+close pair; the unsuccessful path closes the slab in finally.
        boolean transferred = false;
        try {
            TlsStatus status;
            synchronized (tlsLock) {
                status = tlsEngine.unwrap(tlsCiphertextPlaceholder, plaintext);
            }
            if (status == TlsStatus.OK && plaintext.size() > 0) {
                transferred = true;
                return plaintext;
            }
            if (status == TlsStatus.CLOSED) {
                markRemoteClosed();
            }
            return null;
        } finally {
            if (!transferred) {
                plaintext.close();
            }
        }
    }

    @SuppressWarnings("PMD.UseTryWithResources") // see readTlsIngressFromFd above — transferred-flag ownership
    /* default */ LoanedBuffer decryptIngress(LoanedBuffer ciphertext, int length) {
        // PERF-062: fast-fail before allocation when offerIngress would reject.
        // Saves the network buffer borrow + TLS unwrap on backpressured / closing-stream paths.
        if (closed.get()) {
            return null;
        }
        if (QUEUE_BACKPRESSURE_ENABLED && inboundQueueDepth.get() >= INBOUND_QUEUE_CAPACITY) {
            return null;
        }
        LoanedBuffer plain = allocator.allocateNetwork(length);
        // PERF-062: ownership-transfer flag — see readTlsIngressFromFd for rationale.
        boolean transferred = false;
        try {
            TlsStatus status;
            synchronized (tlsLock) {
                status = tlsEngine.unwrap(ciphertext, plain);
            }
            if (status == TlsStatus.OK && plain.size() > 0) {
                transferred = true;
                return plain;
            }
            if (status == TlsStatus.CLOSED) {
                close();
            }
            return null;
        } finally {
            if (!transferred) {
                plain.close();
            }
        }
    }

    /**
     * Whether the head write waits for the peer's next record instead of for socket space. Write
     * interest stays off meanwhile, because a writable socket would not change anything for it.
     */
    /* default */ boolean stalledOnRead() {
        return tlsDrain != null && tlsDrain.stalledOnRead();
    }

    private boolean needsWriteInterest() {
        return hasPendingData() && !stalledOnRead();
    }

    /** Flushes a write that waited for the peer, after an ingress batch that may have satisfied it. */
    /* default */ void flushPendingAfterIngress() {
        if (tlsDrain != null && tlsDrain.markRetryAfterIngress() && hasPendingData()) {
            try {
                flushPendingWrites();
            } catch (TransportStreamClosedException _) {
                // The abort already released the queue and finishes the close; the writer sees the
                // signal on its next call.
                return;
            }
            if (needsWriteInterest()) {
                writeInterestCallback.run();
            }
        }
    }

    /* default */ boolean flushPendingWrites() {
        Thread currentThread = Thread.currentThread();
        int entry = NativeTcpStreamConsumerGate.enterSingleConsumer(runtime.outboundConsumer(), currentThread);
        if (entry == NativeTcpStreamConsumerGate.NOT_ACQUIRED) {
            return false;
        }
        boolean drained;
        try {
            try {
                drained = drainPendingWrites();
            } finally {
                NativeTcpStreamConsumerGate.exitSingleConsumer(runtime.outboundConsumer(), currentThread, entry);
            }
        } finally {
            // An abort, or a close deferred while this thread held the slot, finishes here after the
            // release. The finishes inside drainPendingWrites run under the slot, which is safe
            // because a nested cleanup leaves the holder's slot in place.
            if (entry == NativeTcpStreamConsumerGate.ACQUIRED && closeRequested.get()) {
                finishAfterRelease();
            }
        }
        // An ingress batch that found the slot held left a retry request; the release above is what
        // lets it run. The check is on the stall path only, so a drained queue pays nothing for it.
        boolean retry = !drained && entry == NativeTcpStreamConsumerGate.ACQUIRED
                && tlsDrain != null && tlsDrain.consumeRetryAfterIngress();
        return retry ? flushPendingWrites() : drained;
    }

    private void finishAfterRelease() {
        try {
            finishCloseIfDrained();
        } catch (RuntimeException error) {
            logBestEffortCleanupFailure("finishAfterRelease", error);
        }
    }

    private boolean drainPendingWrites() {
        if (closed.get()) {
            drainOutboundQueue();
            return true;
        }

        if (!tlsHandshake.ensureReady(false)) {
            finalizeCloseIfRequestedAfterBestEffortFlush();
            return outboundQueue.peek() == null;
        }

        NativeTcpStreamPendingWrite pending = outboundQueue.peek();
        while (pending != null) {
            int result = drainSinglePendingWrite(pending);
            if (result == DRAIN_STALLED) {
                finalizeCloseIfRequestedAfterBestEffortFlush();
                return false;
            }
            // Covers the TLS drain, whose ciphertext goes out through tlsEngine.wrap and so never
            // passes tryWrite: without this, a response queued once and drained slowly would carry
            // only its enqueue stamp.
            lastActivityNanos = System.nanoTime();
            completeQueueHeadAfterDrain(pending);
            pending = outboundQueue.peek();
        }
        if (closeRequested.get()) {
            finishCloseIfDrained();
        }
        return true;
    }

    private NativeTcpStreamPendingWrite newPendingWrite(LoanedBuffer buffer, int length) {
        return new NativeTcpStreamPendingWrite(buffer, length, pendingWriteTlsContext, pendingWriteTryWriter);
    }

    private int drainSinglePendingWrite(NativeTcpStreamPendingWrite pending) {
        if (tlsEngine == null) {
            return tryDrainPlainWrite(pending) ? DRAIN_OK : DRAIN_STALLED;
        }
        return tlsDrain.drain(pending);
    }

    private void completeQueueHeadAfterDrain(NativeTcpStreamPendingWrite expectedHead) {
        NativeTcpStreamPendingWrite completed = outboundQueue.poll();
        if (completed == null || !completed.equals(expectedHead)) {
            throw new IllegalStateException("Outbound queue head changed during flush for stream " + streamId);
        }
        closePendingWriteAfterPoll(completed);
    }

    private void closePendingWriteAfterPoll(NativeTcpStreamPendingWrite pendingWrite) {
        long length = pendingWrite.plainLength();
        decrementDepth(outboundQueueDepth);
        pendingWrite.close();
        writeBound.release(length);
    }

    private boolean tryDrainPlainWrite(NativeTcpStreamPendingWrite pending) {
        try {
            return pending.writePlain();
        } catch (IOException e) {
            if (closed.get()) {
                return true;
            }
            if (isPeerGone(e)) {
                throw abortOnPeerGone(pending.bytesWritten(), e);
            }
            requestAbort(false);
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }

    /**
     * Whether a failed send found the peer gone: its end was observed, the send's errno says reset, or,
     * on the NIO fallback, which reports no errno and whose messages are locale text, the channel failed
     * on a send for any reason but a local close. A local close is a {@link ClosedChannelException}, so
     * the class alone separates it from a broken connection.
     */
    private boolean isPeerGone(IOException failure) {
        return remoteClosed.get()
                || failure instanceof NativeTcpPeerResetException
                || (!plainSocketBackend.usesCoreSocketSeam()
                        && NativeTcpStreamPlainSocketIo.isBrokenConnection(failure));
    }

    /**
     * Ends the stream after a send found the peer gone and returns the signal to throw. The remote end
     * is recorded first, so the peer ended first unless the application closed earlier. The caller
     * holds the outbound slot.
     */
    private TransportStreamClosedException abortOnPeerGone(long bytesSent, Throwable lowLevel) {
        markRemoteClosed();
        requestAbort(true);
        return closedSignal(TransportException.sendFailure(engineName, bytesSent, lowLevel));
    }

    /**
     * Reacts to an unrecoverable outbound failure by abandoning the queued writes, marking the
     * stream for abortive termination and waking every waiter.
     *
     * <p>Without the discard, a failed write would stay queued forever and hang teardown. The caller
     * holds the outbound slot (via {@code flushPendingWrites}), so {@link #drainOutboundQueue} is
     * safe here; {@code flushPendingWrites} finishes the teardown after releasing the slot. When the
     * peer had already ended the stream, bytes it sent first stay readable until the application
     * closes or resets the stream.
     *
     * @param peerGone whether the peer already ended the stream (then it ended first)
     */
    private void requestAbort(boolean peerGone) {
        if (peerGone && !closeRequested.get()) {
            peerEndedFirst.set(true);
            inboundOutlivesAbort.set(true);
        }
        resetRequested.set(true);
        closeRequested.set(true);
        drainOutboundQueue();
        wakeAllWaiters();
    }

    private void writeDirect(MemorySegment source, int length) {
        int writtenTotal = 0;
        try {
            if (awaitRegistrationReady(false)) {
                while (writtenTotal < length) {
                    int written = tryWrite(source, writtenTotal, length - writtenTotal);
                    if (written <= 0) {
                        break;
                    }
                    writtenTotal += written;
                }
            }
        } catch (IOException e) {
            if (!closeRequested.get() && !closed.get()) {
                throw directSendFailure(e, writtenTotal);
            }
            return;
        }

        if (writtenTotal < length && !closeRequested.get() && !closed.get()) {
            enqueueBounded(source, writtenTotal, length - writtenTotal);
        }
    }

    /** Whether a read reports the closed stream: ending or ended, unless an abort kept the peer's bytes. */
    private boolean mustReportClosed() {
        return isEndingOrEnded() && !(inboundOutlivesAbort.get() && remoteClosed.get()
                && (currentInbound != null || inboundQueueDepth.get() > 0));
    }

    /** The application closed or reset: end the survival and release what is queued. */
    private void endInboundSurvival() {
        if (inboundOutlivesAbort.compareAndSet(true, false) && closed.get()) {
            try {
                cleanupInboundIfOwnedByCurrentThreadOrIdle();
            } catch (RuntimeException error) {
                logBestEffortCleanupFailure("cleanupInbound", error);
            }
        }
    }

    private void awaitReadableIngress() {
        runtime.readParked().set(true);
        try {
            if (closed.get() || closeRequested.get() || remoteClosed.get()
                    || currentInbound != null || inboundQueueDepth.get() > 0) {
                return;
            }
            LockSupport.park();
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Read interrupted");
            }
        } finally {
            runtime.readParked().set(false);
        }
    }

    private boolean awaitRegistrationReady(boolean blocking) {
        return runtime.registrationGate().awaitReady(blocking);
    }

    /**
     * Wakes the reader, a writer and a registration waiter. Called after each terminal flag is
     * written, whichever caller won it, and never gated on a parked hint: a surplus permit is
     * harmless because every park loop re-checks. Registrations stay in place for later wakes.
     */
    private void wakeAllWaiters() {
        unparkIfSet(runtime.streamVt().get());
        unparkIfSet(writeBound.waiter());
        unparkIfSet(runtime.registrationGate().waiter());
    }

    private static void unparkIfSet(Thread waiter) {
        if (waiter != null) {
            LockSupport.unpark(waiter);
        }
    }

    private void signalReadableIngress() {
        if (!runtime.readParked().get()) {
            return;
        }
        Thread readerThread = runtime.streamVt().get();
        if (readerThread != null) {
            LockSupport.unpark(readerThread);
        }
    }

    private int tryWrite(MemorySegment source, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        // The single point plaintext bytes leave this socket: writeDirect's loop and the
        // reactor's deferred drain (via pendingWriteTryWriter) both arrive here. Stamping on
        // egress — not only on the application's call — is what keeps a large response draining
        // to a slow client from being reclaimed mid-transfer while its bytes are still flowing.
        // One volatile store per syscall.
        int written = plainSocketBackend.usesCoreSocketSeam()
                ? NativeTcpStreamPlainSocketIo.seamWriteWithNioFallback(
                        socketHandles, plainSocketHandle, channel, source, offset, length, streamId)
                : NativeTcpStreamPlainSocketIo.nioFallbackWrite(channel, source, offset, length);
        if (written > 0) {
            lastActivityNanos = System.nanoTime();
        }
        return written;
    }

    /**
     * The closed-stream signal for a stream observed closed. The first side to end the stream decides
     * the code: the remote end (or a carrier teardown) observed before any local close request reports
     * {@code EX-NET-4008}, even when a local close followed; a local close request that came first
     * reports {@code EX-NET-4009}, even when the remote end was observed afterwards.
     */
    private TransportStreamClosedException closedSignal() {
        return peerEndedFirst.get()
                ? TransportStreamClosedException.peerClosed()
                : TransportStreamClosedException.locallyClosed();
    }

    /**
     * What a failed direct write throws. No slot is held and nothing is queued, so a peer that is gone
     * leaves nothing to abort: the application's close ends the stream.
     */
    private RuntimeException directSendFailure(IOException failure, int bytesSent) {
        if (isPeerGone(failure)) {
            markRemoteClosed();
            return closedSignal(TransportException.sendFailure(engineName, bytesSent, failure));
        }
        return TransportException.sendFailure(engineName, bytesSent, failure);
    }

    /** The closed-stream signal for a failed send, carrying the transport failure as its cause. */
    private TransportStreamClosedException closedSignal(Throwable cause) {
        TransportStreamClosedException signal = closedSignal();
        signal.initCause(cause);
        return signal;
    }

    private void ensureOpen() {
        if (closeRequested.get() || closed.get()) {
            tlsHandshake.throwRecordedFailure();
            throw closedSignal();
        }
    }

    /**
     * Completes the handshake for a sender; {@link NativeTcpStreamTlsHandshake#ensureReady} throws a
     * recorded failure itself.
     */
    private void requireTlsReady() {
        if (!tlsHandshake.ensureReady(true)) {
            throw closedSignal();
        }
    }

    /* default */ NativeTcpTlsFailure recordedTlsFailure() {
        return tlsHandshake.recordedFailure();
    }

    private int closedReadOutcome() {
        closeCurrentInbound();
        drainInboundQueue();
        tlsHandshake.throwRecordedFailure();
        if (remoteClosed.get()) {
            return -1;
        }
        throw TransportStreamClosedException.locallyClosed();
    }

    private void cleanupInboundIfOwnedByCurrentThreadOrIdle() {
        Thread currentThread = Thread.currentThread();
        int entry = NativeTcpStreamConsumerGate.enterSingleConsumer(runtime.inboundConsumer(), currentThread);
        if (entry == NativeTcpStreamConsumerGate.NOT_ACQUIRED) {
            return;
        }
        try {
            closeCurrentInbound();
            drainInboundQueue();
        } finally {
            NativeTcpStreamConsumerGate.exitSingleConsumer(runtime.inboundConsumer(), currentThread, entry);
        }
    }

    /** Queues {@code source} as chunks, each once the queue is at the resume mark; unbounded is one chunk. */
    private void enqueueBounded(MemorySegment source, int offset, int length) {
        int done = 0;
        while (done < length) {
            writeBound.awaitCapacity();
            int chunk = Math.min(length - done, writeBound.chunkBytes());
            enqueueDeferredWrite(source, offset + done, chunk);
            done += chunk;
        }
    }

    private void enqueueDeferredWrite(MemorySegment source, int offset, int length) {
        if (length <= 0) {
            return;
        }
        try (LoanedBuffer buffered = allocator.allocateNetwork(length)) {
            MemorySegment.copy(source, offset, buffered.segment(), 0, length);
            buffered.setSize(length);
            buffered.retain();
            enqueuePendingWrite(newPendingWrite(buffered, length), true);
        }
    }

    private boolean enqueuePendingWrite(NativeTcpStreamPendingWrite pendingWrite, boolean signalWriteInterest) {
        int previousDepth = outboundQueueDepth.getAndIncrement();
        writeBound.add(pendingWrite.plainLength());
        boolean offered = outboundQueue.offer(pendingWrite);
        if (!offered) {
            try (pendingWrite) {
                writeBound.add(-pendingWrite.plainLength());
                decrementDepth(outboundQueueDepth);
                throw new IllegalStateException("Failed to enqueue outbound write for stream " + streamId);
            }
        }
        boolean queueWasEmpty = previousDepth == 0;
        if (signalWriteInterest && queueWasEmpty) {
            writeInterestCallback.run();
        }
        return queueWasEmpty;
    }

    private void awaitOutboundConsumerToYield() {
        Thread currentThread = Thread.currentThread();
        Thread owner = runtime.outboundConsumer().get();
        int attempts = 0;
        while (owner != null
                && !Objects.equals(owner, currentThread)
                && attempts < CLOSE_OUTBOUND_CONSUMER_DRAIN_ATTEMPTS) {
            LockSupport.unpark(owner);
            LockSupport.parkNanos(CLOSE_OUTBOUND_CONSUMER_BACKOFF_NANOS);
            owner = runtime.outboundConsumer().get();
            attempts++;
        }
    }

    private void cleanupOutboundIfOwnedByCurrentThreadOrIdle() {
        Thread currentThread = Thread.currentThread();
        int entry = NativeTcpStreamConsumerGate.enterSingleConsumer(runtime.outboundConsumer(), currentThread);
        if (entry == NativeTcpStreamConsumerGate.NOT_ACQUIRED) {
            return;
        }
        try {
            drainOutboundQueue();
        } finally {
            NativeTcpStreamConsumerGate.exitSingleConsumer(runtime.outboundConsumer(), currentThread, entry);
        }
    }

    private void finalizeCloseIfRequestedAfterBestEffortFlush() {
        if (!closeRequested.get()) {
            return;
        }
        finishCloseIfDrained();
    }

    private void finishCloseIfDrained() {
        Thread currentThread = Thread.currentThread();
        Thread owner = runtime.outboundConsumer().get();
        if (owner != null && !Objects.equals(owner, currentThread)) {
            return;
        }
        // A graceful close waits for queued writes to drain; an abortive reset abandons them.
        if (!closeRequested.get() || (hasPendingData() && !resetRequested.get())) {
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        wakeAllWaiters();

        NativeTcpStreamTeardown.releaseTlsResources(tlsEngine, allocator, tlsCiphertextPlaceholder);

        if (!inboundOutlivesAbort.get()) {
            try {
                cleanupInboundIfOwnedByCurrentThreadOrIdle();
            } catch (RuntimeException error) {
                logBestEffortCleanupFailure("cleanupInbound", error);
            }
        }

        try {
            cleanupOutboundIfOwnedByCurrentThreadOrIdle();
        } catch (RuntimeException error) {
            logBestEffortCleanupFailure("cleanupOutbound", error);
        }

        NativeTcpStreamTeardown.closeChannelQuietly(channel, resetRequested.get());

        connection.markClosedByCarrier();
        closeCallback.run();
    }

    private void closeCurrentInbound() {
        if (currentInbound != null) {
            currentInbound.close();
            currentInbound = null;
            currentInboundOffset = 0;
        }
    }

    private void drainInboundQueue() {
        LoanedBuffer inbound = inboundQueue.poll();
        while (inbound != null) {
            decrementDepth(inboundQueueDepth);
            inbound.close();
            inbound = inboundQueue.poll();
        }
    }

    private void drainOutboundQueue() {
        NativeTcpStreamPendingWrite pending = outboundQueue.poll();
        while (pending != null) {
            closePendingWriteAfterPoll(pending);
            pending = outboundQueue.poll();
        }
    }

    private static int decrementDepth(AtomicInteger counter) {
        return counter.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    private static void logBestEffortCleanupFailure(String stage, RuntimeException error) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG, "Best-effort cleanup failed at stage: " + stage, error);
        }
    }

    private final class StreamRuntimeState {

        private final AtomicReference<Thread> streamVt = new AtomicReference<>();
        private final AtomicReference<Thread> inboundConsumer = new AtomicReference<>();
        private final AtomicReference<Thread> outboundConsumer = new AtomicReference<>();
        private final NativeTcpStreamRegistrationGate registrationGate =
                new NativeTcpStreamRegistrationGate(NativeTcpStream.this::isEndingOrEnded);
        private final AtomicBoolean readParked = new AtomicBoolean(false);

        private AtomicReference<Thread> streamVt() {
            return streamVt;
        }

        private AtomicReference<Thread> inboundConsumer() {
            return inboundConsumer;
        }

        private AtomicReference<Thread> outboundConsumer() {
            return outboundConsumer;
        }

        private NativeTcpStreamRegistrationGate registrationGate() {
            return registrationGate;
        }

        private AtomicBoolean readParked() {
            return readParked;
        }
    }

}
