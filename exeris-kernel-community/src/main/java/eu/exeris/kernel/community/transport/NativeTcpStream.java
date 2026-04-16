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
    private final AtomicReference<Thread> streamVt = new AtomicReference<>();
    private final AtomicReference<Thread> inboundConsumer = new AtomicReference<>();
    private final AtomicReference<Thread> outboundConsumer = new AtomicReference<>();
    private final AtomicBoolean readParked = new AtomicBoolean(false);
    private final AtomicReference<Thread> writeWaiter = new AtomicReference<>();
    private final AtomicBoolean writeParked = new AtomicBoolean(false);
    
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
        acquireSingleConsumer(inboundConsumer, currentThread, "inbound");
        try {
            if (closed.get()) {
                return closedReadOutcome();
            }
            if (remoteClosed.get() && currentInbound == null && inboundQueueDepth.get() == 0) {
                return -1;
            }
            ensureTlsReady(true);

            streamVt.compareAndSet(null, currentThread);

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
            releaseSingleConsumer(inboundConsumer, currentThread);
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
            writeDirect(source, length);
            return;
        }
        ensureTlsReady(true);

        try (LoanedBuffer plain = allocator.allocateNetwork(length);
             LoanedBuffer cipher = allocator.allocateNetwork(length + 1024)) {
            MemorySegment.copy(source, 0, plain.segment(), 0, length);
            plain.setSize(length);
            TlsStatus status;
            synchronized (tlsLock) {
                status = tlsEngine.wrap(plain, cipher);
            }
            if (status == TlsStatus.OK) {
                if (cipher.size() > 0) {
                    writeDirect(cipher.segment(), (int) cipher.size());
                }
                return;
            }
            if (status == TlsStatus.CLOSED) {
                close();
                return;
            }
            throw TransportException.sendFailure(engineName, 0, null);
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
        if (closed.get()) {
            buffer.close();
            throw new IllegalStateException(STREAM_CLOSED_MESSAGE);
        }

        ensureTlsReady(true);

        boolean offered = outboundQueue.offer(new PendingWrite(buffer, length));
        if (!offered) {
            buffer.close();
            throw new IllegalStateException("Failed to enqueue outbound write for stream " + streamId);
        }
        outboundQueueDepth.incrementAndGet();
        writeInterestCallback.run();
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Thread readerThread = streamVt.getAndSet(null);
        if (readerThread != null) {
            LockSupport.unpark(readerThread);
        }
        Thread writerThread = writeWaiter.getAndSet(null);
        if (writerThread != null) {
            LockSupport.unpark(writerThread);
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
        } catch (IOException | RuntimeException ignored) {
            // best effort half-close before full close
        }

        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort close
        }

        connection.markClosedByCarrier();
        closeCallback.run();
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

        if (QUEUE_BACKPRESSURE_ENABLED && currentQueueDepth >= INBOUND_QUEUE_CAPACITY) {
            ingressBuffer.close();
            TransportQueueBackpressureAlertEvent.emit(1, currentQueueDepth, trend);
            throw new IllegalStateException("Rejected inbound buffer due to backpressure for stream " + streamId);
        }

        boolean offered = inboundQueue.offer(ingressBuffer);
        if (!offered) {
            ingressBuffer.close();
            if (QUEUE_BACKPRESSURE_ENABLED) {
                TransportQueueBackpressureAlertEvent.emit(1, currentQueueDepth, trend);
            }
            throw new IllegalStateException("Rejected inbound buffer due to backpressure for stream " + streamId);
        }
        lastQueueDepth = inboundQueueDepth.incrementAndGet();
        signalReadableIngress();
    }

    /* default */ void markRemoteClosed() {
        remoteClosed.set(true);
        signalReadableIngress();
    }

    /* default */ boolean isClosed() {
        return closed.get();
    }

    /* default */ TlsEngine tlsEngine() {
        return tlsEngine;
    }

    /* default */ void markTlsBoundFromCarrier() {
        tlsBound.set(true);
    }

    /* default */ boolean usesFdOwnerTls() {
        return tlsEngine instanceof CommunityTlsEngine;
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
        acquireSingleConsumer(outboundConsumer, currentThread, "outbound");
        try {
            if (closed.get()) {
                drainOutboundQueue();
                return true;
            }

            if (!ensureTlsReady(false)) {
                return outboundQueue.peek() == null;
            }

            PendingWrite pending = outboundQueue.peek();
            while (pending != null) {
                if (tlsEngine == null) {
                    if (!tryDrainPlainWrite(pending)) {
                        return false;
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
                        return false;
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
            return true;
        } finally {
            releaseSingleConsumer(outboundConsumer, currentThread);
        }
    }

    private boolean tryDrainPlainWrite(PendingWrite pending) {
        try {
            return pending.writePlain();
        } catch (IOException e) {
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }

    private boolean tryDrainCipherWrite(PendingWrite pending) {
        try {
            return pending.writeCipher();
        } catch (IOException e) {
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }

    private void writeDirect(MemorySegment source, int length) {
        long writtenTotal = 0;
        try {
            while (writtenTotal < length) {
                int written = tryWrite(source, (int) writtenTotal, length - (int) writtenTotal);
                if (written > 0) {
                    writtenTotal += written;
                    continue;
                }
                if (written == 0) {
                    awaitWritable();
                    continue;
                }
                throw TransportException.sendFailure(engineName, writtenTotal, null);
            }
        } catch (IOException e) {
            throw TransportException.sendFailure(engineName, writtenTotal, e);
        }
    }

    private void awaitReadableIngress() {
        readParked.set(true);
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
            readParked.set(false);
        }
    }

    private void awaitWritable() {
        ensureOpen();
        Thread currentThread = Thread.currentThread();
        writeWaiter.set(currentThread);
        writeParked.set(true);
        try {
            writeInterestCallback.run();
            if (closed.get()) {
                throw new IllegalStateException(STREAM_CLOSED_MESSAGE);
            }
            LockSupport.park();
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Write interrupted");
            }
            ensureOpen();
        } finally {
            writeParked.set(false);
            writeWaiter.compareAndSet(currentThread, null);
        }
    }

    /* default */ void signalWriteReady() {
        if (!writeParked.get()) {
            return;
        }
        Thread writerThread = writeWaiter.get();
        if (writerThread != null) {
            LockSupport.unpark(writerThread);
        }
    }

    private void signalReadableIngress() {
        if (!readParked.get()) {
            return;
        }
        Thread readerThread = streamVt.get();
        if (readerThread != null) {
            LockSupport.unpark(readerThread);
        }
    }

    private int tryWrite(MemorySegment source, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        MemorySegment slice = source.asSlice(offset, length);
        ByteBuffer sourceBuffer = slice.asByteBuffer();
        return channel.write(sourceBuffer);
    }

    private void ensureOpen() {
        if (closed.get()) {
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
        if (!tryAcquireSingleConsumer(inboundConsumer, currentThread)) {
            return;
        }
        try {
            closeCurrentInbound();
            drainInboundQueue();
        } finally {
            releaseSingleConsumer(inboundConsumer, currentThread);
        }
    }

    private void cleanupOutboundIfOwnedByCurrentThreadOrIdle() {
        Thread currentThread = Thread.currentThread();
        if (!tryAcquireSingleConsumer(outboundConsumer, currentThread)) {
            return;
        }
        try {
            drainOutboundQueue();
        } finally {
            releaseSingleConsumer(outboundConsumer, currentThread);
        }
    }

    private static void acquireSingleConsumer(AtomicReference<Thread> consumerRef,
                                              Thread currentThread,
                                              String queueName) {
        Thread owner = consumerRef.get();
        if (owner == currentThread) {
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
        return owner == currentThread || (owner == null && consumerRef.compareAndSet(null, currentThread));
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
