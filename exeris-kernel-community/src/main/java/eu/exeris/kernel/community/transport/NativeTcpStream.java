/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Community TCP stream backed by a single socket file descriptor.
 *
 * <p>Ingress is slab-backed: carrier loop reads directly to {@link LoanedBuffer} and
 * enqueues slabs for the stream VT.
 *
 * <h2>Decomposition (v0.8 Sprint 3 QA-016)</h2>
 * <p>Plain-socket I/O dispatch (core-socket seam vs NIO fallback) lives in
 * {@link NativeTcpStreamPlainSocketIo}; single-consumer-gate helpers live in
 * {@link NativeTcpStreamConsumerGate}; outbound pending-write records (plain
 * + lazy ciphertext + offsets) live in {@link NativeTcpStreamPendingWrite}.
 * This class retains the {@link TransportStream} interface, carrier-facing
 * callbacks, TLS handshake state machine, inbound state, and close
 * orchestration.
 *
 * @since 0.5.0
 */
// QA-016 extracted 3 seams (PlainSocketIo, ConsumerGate, PendingWrite); residual TLS handshake state +
// inbound/outbound machinery remains cohesive. QA-016b can extract inbound state if WMC threshold
// drives a follow-up split.
@SuppressWarnings({
    "PMD.GodClass",
    "PMD.CyclomaticComplexity",                // multi-state read/drain/handshake loops are intrinsically cohesive.
    "PMD.TooManyMethods",                      // lifecycle + carrier callbacks + handshake + close orchestration.
    "PMD.CloseResource",                       // LoanedBuffer ownership transfers via retain (PERF-062).
    "PMD.AvoidBranchingStatementAsLastInLoop", // continue-then-return idiom in advanceInbound state machine.
    "PMD.AvoidCatchingGenericException",       // best-effort cleanup paths must absorb RuntimeException.
    "PMD.NullAssignment"                       // currentInbound reset to null after close() to release reference.
})
final class NativeTcpStream implements TransportStream {

    private static final System.Logger LOG = System.getLogger(NativeTcpStream.class.getName());
    private static final String STREAM_CLOSED_MESSAGE = "Stream is closed";
    private static final long HANDSHAKE_BACKOFF_NANOS = 250_000L;
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000L;
    private static final long REGISTRATION_BACKOFF_NANOS = 100_000L;
    private static final long REGISTRATION_TIMEOUT_MILLIS = 5_000L;
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

    private static final int DRAIN_CLOSED  = 0;
    private static final int DRAIN_OK      = 1;
    private static final int DRAIN_STALLED = 2;

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

    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean remoteClosed = new AtomicBoolean(false);
    // Set by reset(long): abandon queued writes (no drain wait) and terminate abortively (RST).
    private final AtomicBoolean resetRequested = new AtomicBoolean(false);
    private final Queue<NativeTcpStreamPendingWrite> outboundQueue =
            new MpscUnboundedArrayQueue<>(JCTOOLS_QUEUE_CHUNK_SIZE);
    private final Queue<LoanedBuffer> inboundQueue = QUEUE_BACKPRESSURE_ENABLED
            ? new SpscArrayQueue<>(INBOUND_QUEUE_CAPACITY)
            : new SpscUnboundedArrayQueue<>(JCTOOLS_QUEUE_CHUNK_SIZE);
    private final AtomicInteger outboundQueueDepth = new AtomicInteger(0);
    private final AtomicInteger inboundQueueDepth = new AtomicInteger(0);
    private final AtomicBoolean tlsBound = new AtomicBoolean(false);
    private final AtomicBoolean tlsReady = new AtomicBoolean(false);
    // One-shot latch + callback fired exactly once when the connection becomes established
    // (plaintext: reactor registration armed; TLS: reactor-driven handshake reached ACTIVE).
    // Replaces the acceptor thread blocking on the handshake — see fireEstablishedOnce().
    private final AtomicBoolean establishedFired = new AtomicBoolean(false);
    private volatile Runnable onEstablishedCallback;
    private volatile long registrationReadyNanos;
    private final Object tlsLock = new Object();
    private final StreamRuntimeState runtime = new StreamRuntimeState();
    
    // Phase 1B: Queue depth tracking for telemetry and backpressure
    private volatile int lastQueueDepth;

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
        this.engineName = Objects.requireNonNull(engineName, "engineName must not be null");
        this.streamId = streamId;
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
        this.pendingWriteTlsContext = tlsEngine == null
                ? null
                : new NativeTcpStreamPendingWrite.TlsContext(tlsEngine, tlsLock, allocator);
        this.pendingWriteTryWriter = this::tryWrite;
    }

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

        Thread currentThread = Thread.currentThread();
        NativeTcpStreamConsumerGate.acquireSingleConsumer(runtime.inboundConsumer(), currentThread, "inbound");
        try {
            if (closed.get()) {
                return closedReadOutcome();
            }
            if (remoteClosed.get() && currentInbound == null && inboundQueueDepth.get() == 0) {
                return -1;
            }
            ensureTlsReady(true);

            runtime.streamVt().compareAndSet(null, currentThread);

            while (true) {
                if (closed.get()) {
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

        if (tlsEngine == null) {
            if (hasPendingData()) {
                enqueueDeferredWrite(source, 0, length);
            } else {
                writeDirect(source, length);
            }
            return;
        }

        ensureTlsReady(true);
        try (LoanedBuffer plain = allocator.allocateNetwork(length)) {
            MemorySegment.copy(source, 0, plain.segment(), 0, length);
            plain.setSize(length);
            plain.retain();
            queueWrite(plain, length);
        }
    }

    @Override
    public void queueWrite(LoanedBuffer buffer, int length) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (length < 0 || length > buffer.capacity()) {
            buffer.close();
            throw new IllegalArgumentException("length out of range for loaned buffer");
        }
        if (closeRequested.get() || closed.get()) {
            buffer.close();
            throw new IllegalStateException(STREAM_CLOSED_MESSAGE);
        }
        if (length == 0) {
            buffer.close();
            return;
        }

        ensureTlsReady(true);
        Thread observedConsumer = runtime.outboundConsumer().get();
        NativeTcpStreamPendingWrite pendingWrite = newPendingWrite(buffer, length);
        boolean queueWasEmpty = enqueuePendingWrite(pendingWrite, false);
        if (!queueWasEmpty) {
            Thread activeConsumer = runtime.outboundConsumer().get();
            if (observedConsumer != null
                    && !Objects.equals(observedConsumer, Thread.currentThread())
                    && activeConsumer == null
                    && hasPendingData()) {
                writeInterestCallback.run();
            }
            return;
        }

        try {
            boolean drained = tryFlushPendingWritesNow();
            if (!drained && hasPendingData()) {
                writeInterestCallback.run();
            }
        } catch (RuntimeException error) {
            close();
            throw error;
        }
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

    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            finishCloseIfDrained();
            return;
        }
        Thread readerThread = runtime.streamVt().getAndSet(null);
        if (readerThread != null) {
            LockSupport.unpark(readerThread);
        }
        Thread writerThread = runtime.writeWaiter().getAndSet(null);
        if (writerThread != null) {
            LockSupport.unpark(writerThread);
        }
        Thread registrationThread = runtime.registrationGate().clearWaiter();
        if (registrationThread != null) {
            LockSupport.unpark(registrationThread);
        }
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

    @Override
    public void reset(long errorCode) {
        // Idempotent abortive termination (TransportStream#reset). Distinct from close():
        // queued writes are abandoned (not drained) and the socket is closed with SO_LINGER 0
        // so the peer observes a RST rather than a graceful FIN. errorCode is advisory for TCP.
        if (!resetRequested.compareAndSet(false, true)) {
            return;
        }
        closeRequested.set(true);
        try {
            channel.setOption(StandardSocketOptions.SO_LINGER, 0);
        } catch (IOException | RuntimeException _) {
            // Best effort: if SO_LINGER can't be set, the terminal close falls back to FIN.
        }
        // Unpark any reader/writer/registration waiter so they observe the closed state and bail.
        Thread readerThread = runtime.streamVt().getAndSet(null);
        if (readerThread != null) {
            LockSupport.unpark(readerThread);
        }
        Thread writerThread = runtime.writeWaiter().getAndSet(null);
        if (writerThread != null) {
            LockSupport.unpark(writerThread);
        }
        Thread registrationThread = runtime.registrationGate().clearWaiter();
        if (registrationThread != null) {
            LockSupport.unpark(registrationThread);
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

    /**
     * Test-only: completes a deferred abortive close while {@code owner} still holds the outbound
     * consumer slot, then releases it. Running {@link #finishCloseIfDrained()} on the slot owner
     * discards the queued write (it is never written to the socket) before the carrier reactor can
     * acquire the slot and flush it — the determinism the {@code reset()}-abandon discriminator needs.
     */
    /* default */ void completeAbortAndUnpinOutboundConsumerForTest(Thread owner) {
        assert resetRequested.get()
                : "completeAbortAndUnpinOutboundConsumerForTest requires a prior reset() — "
                + "completing a close while holding the consumer slot without an abort request "
                + "would tear down a still-live stream";
        try {
            finishCloseIfDrained();
        } finally {
            NativeTcpStreamConsumerGate.releaseSingleConsumer(runtime.outboundConsumer(), owner);
        }
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
        remoteClosed.set(true);
        signalReadableIngress();
    }

    /* default */ void markRegistrationPending() {
        runtime.registrationGate().markPending();
    }

    /* default */ void markRegistrationReady() {
        runtime.registrationGate().markReady();
        registrationReadyNanos = System.nanoTime();
        signalWriteReady();
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
        return closeRequested.get() || closed.get();
    }

    /* default */ TlsEngine tlsEngine() {
        return tlsEngine;
    }

    /* default */ String plainSocketBackendName() {
        return plainSocketBackend.configValue();
    }

    /* default */ void markTlsBoundFromCarrier() {
        tlsBound.set(true);
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
        if (tlsEngine == null) {
            return null;
        }
        if (!ensureTlsReady(false)) {
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

        LoanedBuffer ciphertextPlaceholder = allocator.allocateInfrastructure(1);
        LoanedBuffer plaintext = allocator.allocateCarrierSlab(0);
        // PERF-062: ownership-transfer flag replaces the prior retain()/auto-close ceremony.
        // The successful return path keeps the buffer alive at refcount 1 without an extra
        // retain+close pair; the unsuccessful path closes the slab in finally.
        boolean transferred = false;
        try {
            ciphertextPlaceholder.setSize(0);
            TlsStatus status;
            synchronized (tlsLock) {
                status = tlsEngine.unwrap(ciphertextPlaceholder, plaintext);
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
            ciphertextPlaceholder.close();
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

    /* default */ boolean flushPendingWrites() {
        Thread currentThread = Thread.currentThread();
        if (!NativeTcpStreamConsumerGate.tryAcquireSingleConsumer(runtime.outboundConsumer(), currentThread)) {
            return false;
        }
        try {
            return drainPendingWrites();
        } finally {
            NativeTcpStreamConsumerGate.releaseSingleConsumer(runtime.outboundConsumer(), currentThread);
        }
    }

    private boolean tryFlushPendingWritesNow() {
        return flushPendingWrites();
    }

    private boolean drainPendingWrites() {
        if (closed.get()) {
            drainOutboundQueue();
            return true;
        }

        if (!ensureTlsReady(false)) {
            finalizeCloseIfRequestedAfterBestEffortFlush();
            return outboundQueue.peek() == null;
        }

        NativeTcpStreamPendingWrite pending = outboundQueue.peek();
        while (pending != null) {
            int result = drainSinglePendingWrite(pending);
            if (result == DRAIN_CLOSED) {
                return true;
            }
            if (result == DRAIN_STALLED) {
                finalizeCloseIfRequestedAfterBestEffortFlush();
                return false;
            }
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
        return drainTlsPending(pending);
    }

    private void completeQueueHeadAfterDrain(NativeTcpStreamPendingWrite expectedHead) {
        NativeTcpStreamPendingWrite completed = outboundQueue.poll();
        if (!Objects.equals(completed, expectedHead)) {
            throw new IllegalStateException("Outbound queue head changed during flush for stream " + streamId);
        }
        closePendingWriteAfterPoll(completed);
    }

    private void closePendingWriteAfterPoll(NativeTcpStreamPendingWrite pendingWrite) {
        decrementDepth(outboundQueueDepth);
        pendingWrite.close();
    }

    private int drainTlsPending(NativeTcpStreamPendingWrite pending) {
        TlsStatus status = pending.prepareCipher();
        if (status == TlsStatus.CLOSED) {
            close();
            return DRAIN_CLOSED;
        }
        if (status != TlsStatus.OK) {
            // Same fail-closed discipline as the IOException paths: an unrecoverable TLS WRAP
            // status must not leave the queued write stuck (hasPendingData() would stay true and
            // hang teardown). We hold the single-consumer slot here, so the discard is safe.
            abortOnUnrecoverableWriteFailure();
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), null);
        }
        return tryDrainCipherWrite(pending) ? DRAIN_OK : DRAIN_STALLED;
    }

    private boolean tryDrainPlainWrite(NativeTcpStreamPendingWrite pending) {
        try {
            return pending.writePlain();
        } catch (IOException e) {
            if (closed.get()) {
                return true;
            }
            abortOnUnrecoverableWriteFailure();
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }

    private boolean tryDrainCipherWrite(NativeTcpStreamPendingWrite pending) {
        try {
            return pending.writeCipher();
        } catch (IOException e) {
            if (closed.get()) {
                return true;
            }
            abortOnUnrecoverableWriteFailure();
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }

    /**
     * Reacts to an unrecoverable outbound-write {@link IOException} by abandoning the queued
     * writes and marking the stream for abortive termination.
     *
     * <p>Without this, a failed write would leave the pending entry in the queue forever:
     * {@link #hasPendingData()} would stay {@code true} and {@link #close()} (and any drain-bounded
     * teardown) would hang until its timeout. This runs on the carrier reactor thread while it
     * holds the single-consumer slot (via {@code flushPendingWrites}), so {@link #drainOutboundQueue}
     * is safe to call directly — but {@code finishCloseIfDrained()} MUST NOT be invoked here, as its
     * nested consumer-slot acquire/release would prematurely free the slot held by the caller. The
     * terminal channel close therefore runs later on the {@link #close()} / teardown path, which by
     * then sees no pending data and the {@code resetRequested} flag (abortive close).
     */
    private void abortOnUnrecoverableWriteFailure() {
        resetRequested.set(true);
        closeRequested.set(true);
        drainOutboundQueue();
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
                throw TransportException.sendFailure(engineName, writtenTotal, e);
            }
            return;
        }

        if (writtenTotal < length && !closeRequested.get() && !closed.get()) {
            enqueueDeferredWrite(source, writtenTotal, length - writtenTotal);
        }
    }

    private void awaitReadableIngress() {
        runtime.readParked().set(true);
        try {
            if (closed.get() || remoteClosed.get() || currentInbound != null || inboundQueueDepth.get() > 0) {
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

    /* default */ void signalWriteReady() {
        if (!runtime.writeParked().get()) {
            return;
        }
        Thread writerThread = runtime.writeWaiter().get();
        if (writerThread != null) {
            LockSupport.unpark(writerThread);
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
        if (plainSocketBackend.usesCoreSocketSeam()) {
            return NativeTcpStreamPlainSocketIo.seamWriteWithNioFallback(
                    socketHandles, plainSocketHandle, channel, source, offset, length, streamId);
        }
        return NativeTcpStreamPlainSocketIo.nioFallbackWrite(channel, source, offset, length);
    }

    private void ensureOpen() {
        if (closeRequested.get() || closed.get()) {
            throw new IllegalStateException(STREAM_CLOSED_MESSAGE);
        }
    }

    private void ensureTlsBound() {
        if (!(tlsEngine instanceof CommunityTlsEngine communityTlsEngine)) {
            return;
        }
        if (tlsBound.get()) {
            return;
        }
        synchronized (this) {
            if (tlsBound.get()) {
                return;
            }
            communityTlsEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(channel));
            tlsBound.set(true);
        }
    }

    private boolean ensureTlsReady(boolean blocking) {
        if (tlsEngine == null || tlsReady.get()) {
            return true;
        }

        ensureTlsBound();
        if (markTlsReadyIfHandshakeComplete()) {
            return true;
        }

        long deadline = handshakeDeadlineNanos();

        while (!closed.get()) {
            TlsStatus status = executeHandshakeStep();
            if (status == TlsStatus.FINISHED) {
                return true;
            }
            if (status == TlsStatus.CLOSED) {
                close();
                return false;
            }
            if (status == TlsStatus.NEED_WRAP) {
                writeInterestCallback.run();
            }

            if (!blocking) {
                return false;
            }
            if (isHandshakeTimedOut(deadline)) {
                throw TransportException.receiveTimeout(engineName, HANDSHAKE_TIMEOUT_MILLIS);
            }
            backoffHandshake();
        }
        return false;
    }

    private TlsStatus executeHandshakeStep() {
        try (LoanedBuffer outbound = allocator.allocateInfrastructure(1)) {
            synchronized (tlsLock) {
                if (markTlsReadyIfHandshakeComplete()) {
                    return TlsStatus.FINISHED;
                }
                TlsStatus status = tlsEngine.beginHandshake(outbound);
                if (status == TlsStatus.FINISHED || markTlsReadyIfHandshakeComplete()) {
                    return TlsStatus.FINISHED;
                }
                return status;
            }
        }
    }

    private boolean markTlsReadyIfHandshakeComplete() {
        if (!tlsEngine.isHandshakeComplete()) {
            return false;
        }
        tlsReady.set(true);
        return true;
    }

    private static long handshakeDeadlineNanos() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MILLIS);
    }

    private static boolean isHandshakeTimedOut(long deadline) {
        return System.nanoTime() >= deadline;
    }

    private static void backoffHandshake() {
        LockSupport.parkNanos(HANDSHAKE_BACKOFF_NANOS);
    }

    private int closedReadOutcome() {
        closeCurrentInbound();
        drainInboundQueue();
        if (remoteClosed.get()) {
            return -1;
        }
        throw new IllegalStateException(STREAM_CLOSED_MESSAGE);
    }

    private void cleanupInboundIfOwnedByCurrentThreadOrIdle() {
        Thread currentThread = Thread.currentThread();
        if (!NativeTcpStreamConsumerGate.tryAcquireSingleConsumer(runtime.inboundConsumer(), currentThread)) {
            return;
        }
        try {
            closeCurrentInbound();
            drainInboundQueue();
        } finally {
            NativeTcpStreamConsumerGate.releaseSingleConsumer(runtime.inboundConsumer(), currentThread);
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
        boolean offered = outboundQueue.offer(pendingWrite);
        if (!offered) {
            try (pendingWrite) {
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
        if (!NativeTcpStreamConsumerGate.tryAcquireSingleConsumer(runtime.outboundConsumer(), currentThread)) {
            return;
        }
        try {
            drainOutboundQueue();
        } finally {
            NativeTcpStreamConsumerGate.releaseSingleConsumer(runtime.outboundConsumer(), currentThread);
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

        if (tlsEngine != null) {
            try {
                try (LoanedBuffer outbound = allocator.allocateNetwork(1024)) {
                    tlsEngine.initiateShutdown(outbound);
                }
            } catch (RuntimeException _) {
                // best effort
            }
            try {
                tlsEngine.close();
            } catch (RuntimeException _) {
                // best effort
            }
        }

        try {
            cleanupInboundIfOwnedByCurrentThreadOrIdle();
        } catch (RuntimeException error) {
            logBestEffortCleanupFailure("cleanupInbound", error);
        }

        try {
            cleanupOutboundIfOwnedByCurrentThreadOrIdle();
        } catch (RuntimeException error) {
            logBestEffortCleanupFailure("cleanupOutbound", error);
        }

        closeChannelQuietly();

        connection.markClosedByCarrier();
        closeCallback.run();
    }

    /**
     * Best-effort terminal close of the underlying channel. A graceful close half-closes the output
     * first (FIN); an abortive {@link #reset(long)} skips the half-close so the SO_LINGER-0 close
     * emits a RST instead.
     */
    private void closeChannelQuietly() {
        if (!resetRequested.get()) {
            try {
                channel.shutdownOutput();
            } catch (IOException | RuntimeException _) {
                // best effort half-close before full close
            }
        }
        try {
            channel.close();
        } catch (IOException _) {
            // best effort close
        }
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

    private final class RegistrationGate {

        private final AtomicBoolean ready = new AtomicBoolean(true);
        private final AtomicReference<Thread> waiter = new AtomicReference<>();

        private void markPending() {
            ready.set(false);
        }

        private void markReady() {
            ready.set(true);
            Thread waiterThread = waiter.getAndSet(null);
            if (waiterThread != null) {
                LockSupport.unpark(waiterThread);
            }
        }

        private Thread clearWaiter() {
            return waiter.getAndSet(null);
        }

        private boolean awaitReady(boolean blocking) {
            if (ready.get()) {
                return true;
            }
            if (!blocking) {
                return false;
            }

            Thread currentThread = Thread.currentThread();
            waiter.set(currentThread);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGISTRATION_TIMEOUT_MILLIS);
            try {
                while (!closeRequested.get() && !closed.get()) {
                    if (ready.get()) {
                        return true;
                    }
                    if (System.nanoTime() >= deadline) {
                        return false;
                    }
                    LockSupport.parkNanos(REGISTRATION_BACKOFF_NANOS);
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Registration wait interrupted");
                    }
                }
                return false;
            } finally {
                waiter.compareAndSet(currentThread, null);
            }
        }
    }

    private final class StreamRuntimeState {

        private final AtomicReference<Thread> streamVt = new AtomicReference<>();
        private final AtomicReference<Thread> inboundConsumer = new AtomicReference<>();
        private final AtomicReference<Thread> outboundConsumer = new AtomicReference<>();
        private final RegistrationGate registrationGate = new RegistrationGate();
        private final AtomicBoolean readParked = new AtomicBoolean(false);
        private final AtomicReference<Thread> writeWaiter = new AtomicReference<>();
        private final AtomicBoolean writeParked = new AtomicBoolean(false);

        private AtomicReference<Thread> streamVt() {
            return streamVt;
        }

        private AtomicReference<Thread> inboundConsumer() {
            return inboundConsumer;
        }

        private AtomicReference<Thread> outboundConsumer() {
            return outboundConsumer;
        }

        private RegistrationGate registrationGate() {
            return registrationGate;
        }

        private AtomicBoolean readParked() {
            return readParked;
        }

        private AtomicReference<Thread> writeWaiter() {
            return writeWaiter;
        }

        private AtomicBoolean writeParked() {
            return writeParked;
        }
    }

}
