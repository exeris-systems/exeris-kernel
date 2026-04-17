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
import eu.exeris.kernel.core.transport.jfr.TransportIngressQueueDepthEvent;
import eu.exeris.kernel.core.transport.jfr.TransportQueueBackpressureAlertEvent;
import eu.exeris.kernel.core.transport.syscall.SyscallErrno;
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
import java.nio.ByteBuffer;
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
 * @since 0.5.0
 */
@SuppressWarnings({
    "PMD.GodClass",
    "PMD.CyclomaticComplexity",
    "PMD.TooManyMethods",
    "PMD.CognitiveComplexity",
    "PMD.CloseResource",
    "PMD.AvoidDeeplyNestedIfStmts",
    "PMD.AvoidBranchingStatementAsLastInLoop",
    "PMD.AvoidCatchingGenericException",
    "PMD.NullAssignment"
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
    private static final int POSIX_SOCKET_IO_FLAGS = 0;
    
    // Phase 1B: TLS ingress queue monitoring thresholds and backpressure control
    private static final boolean QUEUE_BACKPRESSURE_ENABLED =
            Boolean.parseBoolean(System.getProperty("exeris.transport.queueBackpressureEnabled", "false"));
    private static final int QUEUE_DEPTH_THRESHOLD_LOW = 100;
    private static final int QUEUE_DEPTH_THRESHOLD_MID = 500;
    private static final int QUEUE_DEPTH_THRESHOLD_HIGH = 1000;
    private static final int JCTOOLS_QUEUE_CHUNK_SIZE = 128;
    private static final int INBOUND_QUEUE_CAPACITY =
            QUEUE_BACKPRESSURE_ENABLED ? QUEUE_DEPTH_THRESHOLD_HIGH : Integer.MAX_VALUE;

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
    private final PlainSocketBackend plainSocketBackend;

    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean remoteClosed = new AtomicBoolean(false);
    private final Queue<PendingWrite> outboundQueue = new MpscUnboundedArrayQueue<>(JCTOOLS_QUEUE_CHUNK_SIZE);
    private final Queue<LoanedBuffer> inboundQueue = QUEUE_BACKPRESSURE_ENABLED
            ? new SpscArrayQueue<>(INBOUND_QUEUE_CAPACITY)
            : new SpscUnboundedArrayQueue<>(JCTOOLS_QUEUE_CHUNK_SIZE);
    private final AtomicInteger outboundQueueDepth = new AtomicInteger(0);
    private final AtomicInteger inboundQueueDepth = new AtomicInteger(0);
    private final AtomicBoolean tlsBound = new AtomicBoolean(false);
    private final AtomicBoolean tlsReady = new AtomicBoolean(false);
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
        this.plainSocketBackend = PlainSocketBackend.resolve(channel, tlsEngine, socketHandles);
        this.plainSocketHandle = plainSocketBackend.usesCoreSocketSeam()
                ? SocketChannelFdAccess.requireFd(channel)
                : -1;
        if (tlsEngine != null && !(tlsEngine instanceof CommunityTlsEngine)) {
            throw new IllegalArgumentException(
                    "NativeTcpStream only supports socket-owner TLS engines (CommunityTlsEngine); "
                    + "buffer-owner engines are not supported");
        }
    }

    @Override
    public int read(MemorySegment target, int maxBytes) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (maxBytes < 0 || maxBytes > target.byteSize()) {
            throw new IllegalArgumentException("maxBytes out of range for target segment");
        }
        if (maxBytes == 0) {
            return 0;
        }

        Thread currentThread = Thread.currentThread();
        acquireSingleConsumer(runtime.inboundConsumer(), currentThread, "inbound");
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
                if (currentInbound == null) {
                    LoanedBuffer next = inboundQueue.poll();
                    if (next == null) {
                        if (remoteClosed.get()) {
                            return -1;
                        }
                        awaitReadableIngress();
                        continue;
                    }
                    decrementDepth(inboundQueueDepth);
                    currentInbound = next;
                    currentInboundOffset = 0;
                }

                int available = (int) currentInbound.size() - currentInboundOffset;
                if (available <= 0) {
                    closeCurrentInbound();
                    continue;
                }

                int bytes = Math.min(available, maxBytes);
                MemorySegment.copy(
                        currentInbound.segment(),
                        currentInboundOffset,
                        target,
                        0,
                        bytes);
                currentInboundOffset += bytes;
                if (currentInboundOffset >= currentInbound.size()) {
                    closeCurrentInbound();
                }
                return bytes;
            }
        } finally {
            releaseSingleConsumer(runtime.inboundConsumer(), currentThread);
        }
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
        PendingWrite pendingWrite = new PendingWrite(buffer, length);
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

    /* default */ void offerIngress(LoanedBuffer ingressBuffer) {
        if (closed.get()) {
            ingressBuffer.close();
            return;
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
            decrementDepth(inboundQueueDepth);
            ingressBuffer.close();
            TransportQueueBackpressureAlertEvent.emit(1, reservedDepth, trend);
            throw new IllegalStateException("Rejected inbound buffer due to backpressure for stream " + streamId);
        }

        boolean offered = inboundQueue.offer(ingressBuffer);
        if (!offered) {
            decrementDepth(inboundQueueDepth);
            ingressBuffer.close();
            if (QUEUE_BACKPRESSURE_ENABLED) {
                TransportQueueBackpressureAlertEvent.emit(1, reservedDepth, trend);
            }
            throw new IllegalStateException("Rejected inbound buffer due to backpressure for stream " + streamId);
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
        signalWriteReady();
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
            return trySocketSeamRead(target, maxBytes);
        }
        ByteBuffer targetBuffer = target.asSlice(0, maxBytes).asByteBuffer();
        targetBuffer.clear();
        return channel.read(targetBuffer);
    }

    /* default */ boolean usesFdOwnerTls() {
        return tlsEngine instanceof CommunityTlsEngine;
    }

    /* default */ boolean awaitRegistrationReadyForConnection() {
        return awaitRegistrationReady(true);
    }

    /* default */ boolean awaitHandshakeReadyForConnection() {
        return ensureTlsReady(true);
    }

    /* default */ LoanedBuffer readTlsIngressFromFd() {
        if (tlsEngine == null) {
            return null;
        }
        if (!ensureTlsReady(false)) {
            return null;
        }

        try (LoanedBuffer ciphertextPlaceholder = allocator.allocateInfrastructure(1);
             LoanedBuffer plaintext = allocator.allocateCarrierSlab(0)) {
            ciphertextPlaceholder.setSize(0);
            TlsStatus status;
            synchronized (tlsLock) {
                status = tlsEngine.unwrap(ciphertextPlaceholder, plaintext);
            }
            if (status == TlsStatus.OK && plaintext.size() > 0) {
                plaintext.retain();
                return plaintext;
            }
            if (status == TlsStatus.CLOSED) {
                markRemoteClosed();
            }
            return null;
        }
    }

    /* default */ LoanedBuffer decryptIngress(LoanedBuffer ciphertext, int length) {
        try (LoanedBuffer plain = allocator.allocateNetwork(length)) {
            TlsStatus status;
            synchronized (tlsLock) {
                status = tlsEngine.unwrap(ciphertext, plain);
            }
            if (status == TlsStatus.OK && plain.size() > 0) {
                plain.retain();
                return plain;
            }
            if (status == TlsStatus.CLOSED) {
                close();
            }
            return null;
        }
    }

    /* default */ boolean flushPendingWrites() {
        Thread currentThread = Thread.currentThread();
        if (!tryAcquireSingleConsumer(runtime.outboundConsumer(), currentThread)) {
            return false;
        }
        try {
            return drainPendingWrites();
        } finally {
            releaseSingleConsumer(runtime.outboundConsumer(), currentThread);
        }
    }

    private boolean tryFlushPendingWritesNow() {
        Thread currentThread = Thread.currentThread();
        if (!tryAcquireSingleConsumer(runtime.outboundConsumer(), currentThread)) {
            return false;
        }
        try {
            return drainPendingWrites();
        } finally {
            releaseSingleConsumer(runtime.outboundConsumer(), currentThread);
        }
    }

    private boolean drainPendingWrites() {
        if (closed.get()) {
            drainOutboundQueue();
            return true;
        }

        if (!ensureTlsReady(false)) {
            return abortCloseAfterBestEffortFlush() || outboundQueue.peek() == null;
        }

        PendingWrite pending = outboundQueue.peek();
        while (pending != null) {
            if (tlsEngine == null) {
                if (!tryDrainPlainWrite(pending)) {
                    return abortCloseAfterBestEffortFlush();
                }
            } else {
                TlsStatus status = pending.prepareCipher();
                if (status == TlsStatus.CLOSED) {
                    close();
                    return true;
                }
                if (status != TlsStatus.OK) {
                    throw TransportException.sendFailure(engineName, pending.bytesWritten(), null);
                }
                if (!tryDrainCipherWrite(pending)) {
                    return abortCloseAfterBestEffortFlush();
                }
            }
            PendingWrite completed = outboundQueue.poll();
            if (!Objects.equals(completed, pending)) {
                throw new IllegalStateException("Outbound queue head changed during flush for stream " + streamId);
            }
            decrementDepth(outboundQueueDepth);
            completed.close();
            pending = outboundQueue.peek();
        }
        if (closeRequested.get()) {
            finishCloseIfDrained();
        }
        return true;
    }

    private boolean tryDrainPlainWrite(PendingWrite pending) {
        try {
            return pending.writePlain();
        } catch (IOException e) {
            if (closed.get()) {
                return true;
            }
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }

    private boolean tryDrainCipherWrite(PendingWrite pending) {
        try {
            return pending.writeCipher();
        } catch (IOException e) {
            if (closed.get()) {
                return true;
            }
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }

    private void writeDirect(MemorySegment source, int length) {
        int writtenTotal = 0;
        try {
            if (awaitRegistrationReady(false)) {
                while (writtenTotal < length) {
                    int written = tryWrite(source, writtenTotal, length - writtenTotal);
                    if (written > 0) {
                        writtenTotal += written;
                        continue;
                    }
                    break;
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
            return trySocketSeamWrite(source, offset, length);
        }
        MemorySegment slice = source.asSlice(offset, length);
        ByteBuffer sourceBuffer = slice.asByteBuffer();
        return channel.write(sourceBuffer);
    }

    private int trySocketSeamRead(MemorySegment target, int maxBytes) throws IOException {
        try {
            long result = (long) socketHandles.recv().invokeExact(
                    plainSocketHandle,
                    target.asSlice(0, maxBytes),
                    (long) maxBytes,
                    POSIX_SOCKET_IO_FLAGS);
            if (result > 0) {
                return (int) result;
            }
            if (result == 0) {
                return -1;
            }
            int errno = SyscallErrno.currentSocketErrno(socketHandles);
            if (SyscallErrno.isRetryable(errno)) {
                return 0;
            }
            throw new IOException("Core socket recv() failed with errno=" + errno + " for stream " + streamId);
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IOException("Core socket recv() invocation failed for stream " + streamId, ex);
        }
    }

    private int trySocketSeamWrite(MemorySegment source, int offset, int length) throws IOException {
        try {
            long result = (long) socketHandles.send().invokeExact(
                    plainSocketHandle,
                    source.asSlice(offset, length),
                    (long) length,
                    POSIX_SOCKET_IO_FLAGS);
            if (result >= 0) {
                return (int) result;
            }
            int errno = SyscallErrno.currentSocketErrno(socketHandles);
            if (SyscallErrno.isRetryable(errno)) {
                return 0;
            }
            throw new IOException("Core socket send() failed with errno=" + errno + " for stream " + streamId);
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IOException("Core socket send() invocation failed for stream " + streamId, ex);
        }
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
        if (tlsEngine.isHandshakeComplete()) {
            tlsReady.set(true);
            return true;
        }

        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MILLIS);
        long deadline = System.nanoTime() + timeoutNanos;

        while (!closed.get()) {
            try (LoanedBuffer outbound = allocator.allocateInfrastructure(1)) {
                TlsStatus status;
                boolean handshakeComplete;
                synchronized (tlsLock) {
                    handshakeComplete = tlsEngine.isHandshakeComplete();
                    if (handshakeComplete) {
                        tlsReady.set(true);
                        return true;
                    }
                    status = tlsEngine.beginHandshake(outbound);
                    handshakeComplete = tlsEngine.isHandshakeComplete();
                }

                if (status == TlsStatus.FINISHED || handshakeComplete) {
                    tlsReady.set(true);
                    return true;
                }
                if (status == TlsStatus.CLOSED) {
                    close();
                    return false;
                }
                if (status == TlsStatus.NEED_WRAP) {
                    writeInterestCallback.run();
                }
            }

            if (!blocking) {
                return false;
            }
            if (System.nanoTime() >= deadline) {
                throw TransportException.receiveTimeout(engineName, HANDSHAKE_TIMEOUT_MILLIS);
            }
            LockSupport.parkNanos(HANDSHAKE_BACKOFF_NANOS);
        }
        return false;
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
        if (!tryAcquireSingleConsumer(runtime.inboundConsumer(), currentThread)) {
            return;
        }
        try {
            closeCurrentInbound();
            drainInboundQueue();
        } finally {
            releaseSingleConsumer(runtime.inboundConsumer(), currentThread);
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
            enqueuePendingWrite(new PendingWrite(buffered, length), true);
        }
    }

    private boolean enqueuePendingWrite(PendingWrite pendingWrite, boolean signalWriteInterest) {
        int previousDepth = outboundQueueDepth.getAndIncrement();
        boolean offered = outboundQueue.offer(pendingWrite);
        if (!offered) {
            decrementDepth(outboundQueueDepth);
            pendingWrite.close();
            throw new IllegalStateException("Failed to enqueue outbound write for stream " + streamId);
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
        if (!tryAcquireSingleConsumer(runtime.outboundConsumer(), currentThread)) {
            return;
        }
        try {
            drainOutboundQueue();
        } finally {
            releaseSingleConsumer(runtime.outboundConsumer(), currentThread);
        }
    }

    private boolean abortCloseAfterBestEffortFlush() {
        if (!closeRequested.get()) {
            return false;
        }
        drainOutboundQueue();
        finishCloseIfDrained();
        return true;
    }

    private void finishCloseIfDrained() {
        Thread currentThread = Thread.currentThread();
        Thread owner = runtime.outboundConsumer().get();
        if (owner != null && !Objects.equals(owner, currentThread)) {
            return;
        }
        if (!closeRequested.get() || hasPendingData()) {
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

        try {
            channel.shutdownOutput();
        } catch (IOException | RuntimeException _) {
            // best effort half-close before full close
        }

        try {
            channel.close();
        } catch (IOException _) {
            // best effort close
        }

        connection.markClosedByCarrier();
        closeCallback.run();
    }

    private static void acquireSingleConsumer(AtomicReference<Thread> consumerRef,
                                              Thread currentThread,
                                              String queueName) {
        Thread owner = consumerRef.get();
        if (Objects.equals(owner, currentThread)) {
            return;
        }
        if (!consumerRef.compareAndSet(null, currentThread)) {
            throw new IllegalStateException(
                    "Concurrent " + queueName + " queue consumer detected for stream thread "
                            + currentThread.getName());
        }
    }

    private static boolean tryAcquireSingleConsumer(AtomicReference<Thread> consumerRef, Thread currentThread) {
        Thread owner = consumerRef.get();
        return Objects.equals(owner, currentThread)
                || (owner == null && consumerRef.compareAndSet(null, currentThread));
    }

    private static void releaseSingleConsumer(AtomicReference<Thread> consumerRef, Thread currentThread) {
        consumerRef.compareAndSet(currentThread, null);
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
        PendingWrite pending = outboundQueue.poll();
        while (pending != null) {
            decrementDepth(outboundQueueDepth);
            pending.close();
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

    private enum PlainSocketBackend {
        CORE_SOCKET_SEAM("core-socket-seam"),
        NIO_FALLBACK("nio-fallback");

        private final String configValue;

        PlainSocketBackend(String configValue) {
            this.configValue = configValue;
        }

        private boolean usesCoreSocketSeam() {
            return this == CORE_SOCKET_SEAM;
        }

        private String configValue() {
            return configValue;
        }

        private static PlainSocketBackend resolve(SocketChannel channel,
                                                  TlsEngine tlsEngine,
                                                  SyscallHandles socketHandles) {
            if (tlsEngine != null || socketHandles == null || !socketHandles.supportsPlainSocketIo()) {
                return NIO_FALLBACK;
            }
            return SocketChannelFdAccess.canResolveFd(channel) ? CORE_SOCKET_SEAM : NIO_FALLBACK;
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

    private final class PendingWrite implements AutoCloseable {

        private LoanedBuffer plainBuffer;
        private final int plainLength;
        private int plainOffset;
        private LoanedBuffer cipherBuffer;
        private int cipherLength;
        private int cipherOffset;

        private PendingWrite(LoanedBuffer plainBuffer, int plainLength) {
            this.plainBuffer = plainBuffer;
            this.plainLength = plainLength;
            this.plainBuffer.setSize(plainLength);
        }

        private boolean writePlain() throws IOException {
            int written = tryWrite(plainBuffer.segment(), plainOffset, plainLength - plainOffset);
            if (written > 0) {
                plainOffset += written;
            }
            return plainOffset >= plainLength;
        }

        private TlsStatus prepareCipher() {
            if (cipherBuffer != null) {
                return TlsStatus.OK;
            }
            try (LoanedBuffer cipher = allocator.allocateNetwork(plainLength + 1024)) {
                TlsStatus status;
                synchronized (tlsLock) {
                    status = tlsEngine.wrap(plainBuffer, cipher);
                }
                if (status != TlsStatus.OK) {
                    return status;
                }
                if (cipher.size() > 0) {
                    cipher.retain();
                    cipherBuffer = cipher;
                    cipherLength = (int) cipher.size();
                }
                return TlsStatus.OK;
            }
        }

        private boolean writeCipher() throws IOException {
            if (cipherLength == 0) {
                return true;
            }
            int written = tryWrite(cipherBuffer.segment(), cipherOffset, cipherLength - cipherOffset);
            if (written > 0) {
                cipherOffset += written;
            }
            return cipherOffset >= cipherLength;
        }

        private long bytesWritten() {
            return cipherBuffer != null ? cipherOffset : plainOffset;
        }

        @Override
        public void close() {
            if (cipherBuffer != null) {
                cipherBuffer.close();
                cipherBuffer = null;
            }
            if (plainBuffer != null) {
                plainBuffer.close();
                plainBuffer = null;
            }
        }
    }
}
