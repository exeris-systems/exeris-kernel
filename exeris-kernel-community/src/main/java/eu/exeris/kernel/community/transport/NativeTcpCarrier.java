/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.WatermarkManager;
import eu.exeris.kernel.core.transport.scheduler.AdmissionController;
import eu.exeris.kernel.core.transport.scheduler.PaqsScheduler;
import eu.exeris.kernel.core.transport.scheduler.StreamLoadShedder;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.ConnectionHandler;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportEngineCapabilities;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Community native TCP carrier with FD-owner reactor and VT-per-stream dispatch via PAQS.
 *
 * @since 0.5.0
 */
@SuppressWarnings({
    "PMD.TooManyMethods",
    "PMD.ExcessiveImports",
    "PMD.CouplingBetweenObjects",
    "PMD.GodClass",
    "PMD.CyclomaticComplexity",
    "PMD.ExceptionAsFlowControl",
    "PMD.AvoidCatchingGenericException",
    "PMD.CloseResource",
    "PMD.CognitiveComplexity",
    "PMD.LawOfDemeter",
    "PMD.AvoidInstantiatingObjectsInLoops"
})
public final class NativeTcpCarrier implements TransportEngine {

    private static final String ENGINE_NAME = "CommunityNativeTcpCarrier";
    private static final TransportEngineCapabilities CAPS =
            TransportEngineCapabilities.STANDARD.withProvider("community-transport");

    private final TransportConfig config;
    private final MemoryAllocator allocator;
    private final KernelCryptoProvider cryptoProvider;
    private final CryptoProviderConfig cryptoConfig;
    private final TransportEngineCapabilities capabilities;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicLong streamSeq = new AtomicLong(1);
    private final AtomicLong connectionSeq = new AtomicLong(1);
    private final AtomicLong activeConnections = new AtomicLong();
    private final AtomicLong activeStreams = new AtomicLong();
    private final AtomicLong totalAccepted = new AtomicLong();

    private volatile StreamHandler streamHandler;
    private volatile ConnectionHandler connectionHandler = connection -> {
    };

    private volatile ServerSocketChannel serverChannel;
    private volatile Thread acceptorThread;
    private volatile PaqsScheduler paqs;
    private final List<ReactorLoop> reactors = new ArrayList<>();
    private final AtomicInteger nextReactorIndex = new AtomicInteger(0);

    private final ConcurrentMap<SocketChannel, NativeTcpStream> streamByChannel = new ConcurrentHashMap<>();
    private final ConcurrentMap<SocketChannel, ReactorLoop> channelOwner = new ConcurrentHashMap<>();
    private final ConcurrentMap<SocketChannel, Thread> clientIngressThreads = new ConcurrentHashMap<>();

    /* default */ NativeTcpCarrier(TransportConfig config,
                     MemoryAllocator allocator,
                     KernelCryptoProvider cryptoProvider,
                     CryptoProviderConfig cryptoConfig,
                     String providerId) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.cryptoProvider = cryptoProvider;
        this.cryptoConfig = cryptoConfig;
        String resolvedProviderId = Objects.requireNonNull(providerId, "providerId must not be null");
        this.capabilities = new TransportEngineCapabilities(
            CAPS.supportsMultiplexing(),
            true,
            CAPS.transportName(),
            resolvedProviderId
        );
    }

    @Override
    public void setStreamHandler(StreamHandler handler) {
        if (running.get()) {
            throw new IllegalStateException("Cannot set stream handler after start()");
        }
        this.streamHandler = Objects.requireNonNull(handler, "handler must not be null");
    }

    @Override
    public void setConnectionHandler(ConnectionHandler handler) {
        if (running.get()) {
            throw new IllegalStateException("Cannot set connection handler after start()");
        }
        this.connectionHandler = Objects.requireNonNull(handler, "handler must not be null");
    }

    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Engine is closed");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            if (mode() == TransportMode.SERVER || mode() == TransportMode.DUAL) {
                if (streamHandler == null) {
                    throw new IllegalStateException("StreamHandler must be set before start() in SERVER/DUAL mode");
                }
                initPaqs();
                startServerRuntime();
            }
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        closeQuietly(serverChannel);

        Thread acceptor = acceptorThread;
        if (acceptor != null) {
            try {
                acceptor.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        for (ReactorLoop reactor : reactors) {
            reactor.wakeup();
        }
        for (ReactorLoop reactor : reactors) {
            reactor.join(2_000L);
        }

        closeSelectorAndChannels();

        PaqsScheduler localPaqs = paqs;
        if (localPaqs != null) {
            localPaqs.close();
        }
    }

    @Override
    public TransportConnection connect(String host, int port) {
        if (mode() != TransportMode.CLIENT && mode() != TransportMode.DUAL) {
            throw new IllegalStateException("Transport mode does not support outbound connect");
        }
        if (!running.get()) {
            throw new IllegalStateException("Engine is not running");
        }

        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(host, port));
            TlsEngine tlsEngine = createTlsEngineIfEnabled();
            bindTlsFdIfRequired(tlsEngine, channel);
            if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                channel.configureBlocking(false);
            }

            NativeTcpConnection connection = new NativeTcpConnection(
                    connectionSeq.getAndIncrement(),
                    host,
                    port);

            NativeTcpStream stream = new NativeTcpStream(
                    engineName(),
                    streamSeq.getAndIncrement(),
                    channel,
                    connection,
                    allocator,
                    tlsEngine,
                    () -> flushClientStream(channel),
                    () -> onStreamClosed(channel));
            if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                stream.markTlsBoundFromCarrier();
            }

            connection.bindSingleStream(stream);
            streamByChannel.put(channel, stream);
            startClientIngressPump(channel, stream);
            activeConnections.incrementAndGet();
            activeStreams.incrementAndGet();
            totalAccepted.incrementAndGet();
            return connection;
        } catch (IOException e) {
            throw TransportException.bindFailure(engineName(), port, e);
        }
    }

    @Override
    public TransportMode mode() {
        return config.mode();
    }

    @Override
    public TransportStats stats() {
        if (!running.get()) {
            return TransportStats.EMPTY;
        }
        long rejected = 0L;
        PaqsScheduler localPaqs = paqs;
        if (localPaqs != null) {
            rejected = localPaqs.loadShedder().shedCount();
        }
        return new TransportStats(
                (int) activeConnections.get(),
                activeStreams.get(),
                totalAccepted.get(),
                rejected,
                0,
                0
        );
    }

    @Override
    public TransportEngineCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stop();
    }

    private void initPaqs() {
        WatermarkManager watermarkManager = new WatermarkManager(allocator);
        ResourceArbiter arbiter = new ResourceArbiter(watermarkManager);
        AdmissionController admissionController = new AdmissionController(arbiter);
        StreamLoadShedder shedder = new StreamLoadShedder(engineName());
        this.paqs = new PaqsScheduler(
                admissionController,
                shedder,
                streamHandler,
                stream -> StreamPriority.NORMAL,
                engineName());
    }

    private void startServerRuntime() {
        try {
            int reactorCount = Math.max(1, config.reactorCount());
            reactors.clear();
            for (int i = 0; i < reactorCount; i++) {
                reactors.add(new ReactorLoop(i, Selector.open()));
            }
            nextReactorIndex.set(0);

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.bind(new InetSocketAddress(config.bindAddress(), config.port()));

            for (ReactorLoop reactor : reactors) {
                reactor.start();
            }

            acceptorThread = Thread.ofPlatform()
                    .name("carrier/native-tcp/acceptor")
                    .start(this::runAcceptorLoop);
        } catch (IOException e) {
            throw TransportException.engineStartFailure(engineName(), config.port(), e);
        }
    }

    private void runAcceptorLoop() {
        while (running.get()) {
            try {
                acceptPendingConnections();
            } catch (AsynchronousCloseException ignored) {
                // shutdown path
                return;
            } catch (IOException e) {
                if (!running.get()) {
                    return;
                }
                running.set(false);
                throw TransportException.engineStartFailure(engineName(), config.port(), e);
            }
        }
    }

    private void closeKeyStream(SelectionKey key) {
        if (!(key.channel() instanceof SocketChannel channel)) {
            return;
        }
        NativeTcpStream stream = streamByChannel.get(channel);
        if (stream == null) {
            return;
        }
        stream.markRemoteClosed();
        stream.close();
    }

    private void acceptPendingConnections() throws IOException {
        SocketChannel acceptedChannel = serverChannel.accept();
        while (acceptedChannel != null && running.get()) {
            SocketChannel currentChannel = acceptedChannel;
            NativeTcpConnection connection = null;
            try {
                currentChannel.configureBlocking(false);
                currentChannel.socket().setTcpNoDelay(true);

                InetSocketAddress remote = resolveRemoteAddress(currentChannel);
                connection = new NativeTcpConnection(
                        connectionSeq.getAndIncrement(),
                        remote.getAddress().getHostAddress(),
                        remote.getPort());
                TlsEngine tlsEngine = createTlsEngineIfEnabled();
                bindTlsFdIfRequired(tlsEngine, currentChannel);

                NativeTcpStream stream = new NativeTcpStream(
                        engineName(),
                        streamSeq.getAndIncrement(),
                        currentChannel,
                        connection,
                        allocator,
                        tlsEngine,
                        () -> requestWriteInterest(currentChannel),
                        () -> onStreamClosed(currentChannel));
                if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                    stream.markTlsBoundFromCarrier();
                }

                connection.bindSingleStream(stream);
                streamByChannel.put(currentChannel, stream);
                ReactorLoop owner = selectReactor();
                channelOwner.put(currentChannel, owner);
                owner.enqueueRegistration(currentChannel);

                activeConnections.incrementAndGet();
                activeStreams.incrementAndGet();
                totalAccepted.incrementAndGet();

                try {
                    connectionHandler.onConnectionEstablished(connection);
                } catch (RuntimeException ignored) {
                    connection.close();
                    acceptedChannel = serverChannel.accept();
                    continue;
                }

                try {
                    paqs.schedule(stream);
                } catch (RuntimeException ex) {
                    connection.close();
                }
            } catch (RuntimeException exception) {
                if (connection != null) {
                    connection.close();
                } else {
                    closeQuietly(currentChannel);
                }
            }
            acceptedChannel = serverChannel.accept();
        }
    }

    private ReactorLoop selectReactor() {
        int size = reactors.size();
        if (size == 0) {
            throw new IllegalStateException("No reactor loops initialized");
        }
        int index = Math.floorMod(nextReactorIndex.getAndIncrement(), size);
        return reactors.get(index);
    }

    private void readIngress(SocketChannel channel) {
        NativeTcpStream stream = streamByChannel.get(channel);
        if (stream == null) {
            return;
        }

        if (stream.usesFdOwnerTls()) {
            LoanedBuffer offered = stream.readTlsIngressFromFd();
            if (offered != null) {
                stream.offerIngress(offered);
            }
            return;
        }

        try (LoanedBuffer slab = allocator.allocateCarrierSlab(0)) {
            ByteBuffer target = slab.segment().asByteBuffer();
            target.clear();
            int read = channel.read(target);
            if (read > 0) {
                if (stream.isClosed()) {
                    return;
                }
                slab.setSize(read);
                LoanedBuffer offered = adaptTlsIfNeeded(stream, slab, read);
                if (offered != null) {
                    stream.offerIngress(offered);
                }
                return;
            }
            if (read < 0) {
                stream.markRemoteClosed();
            }
        } catch (IOException e) {
            stream.close();
        }
    }

    private LoanedBuffer adaptTlsIfNeeded(NativeTcpStream stream, LoanedBuffer slab, int read) {
        TlsEngine tlsEngine = stream.tlsEngine();
        if (tlsEngine == null) {
            slab.retain();
            return slab;
        }

        try (LoanedBuffer plain = allocator.allocateNetwork(read)) {
            TlsStatus status = tlsEngine.unwrap(slab, plain);
            if (status == TlsStatus.OK && plain.size() > 0) {
                plain.retain();
                return plain;
            }
            if (status == TlsStatus.CLOSED) {
                stream.close();
            }
        }
        return null;
    }

    private void flushStream(SocketChannel channel, SelectionKey key) {
        NativeTcpStream stream = streamByChannel.get(channel);
        if (stream == null) {
            key.interestOps(SelectionKey.OP_READ);
            return;
        }

        stream.flushPendingWrites();
        if (!stream.hasPendingData()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void requestWriteInterest(SocketChannel channel) {
        ReactorLoop owner = channelOwner.get(channel);
        if (owner == null) {
            return;
        }
        owner.enqueueWriteInterest(channel);
    }

    private void onStreamClosed(SocketChannel channel) {
        ReactorLoop owner = channelOwner.remove(channel);
        if (owner != null) {
            owner.cancelKey(channel);
        }
        Thread clientIngressThread = clientIngressThreads.remove(channel);
        if (clientIngressThread != null) {
            clientIngressThread.interrupt();
        }
        if (streamByChannel.remove(channel) != null) {
            activeStreams.decrementAndGet();
            activeConnections.decrementAndGet();
        }
    }

    private void flushClientStream(SocketChannel channel) {
        NativeTcpStream stream = streamByChannel.get(channel);
        if (stream == null) {
            return;
        }
        stream.flushPendingWrites();
    }

    private void startClientIngressPump(SocketChannel channel, NativeTcpStream stream) {
        Thread thread = Thread.ofVirtual()
                .name("carrier/native-tcp-client-ingress/" + stream.streamId())
                .start(() -> runClientIngressLoop(channel, stream));
        clientIngressThreads.put(channel, thread);
    }

    private void runClientIngressLoop(SocketChannel channel, NativeTcpStream stream) {
        while (running.get()) {
            try {
                if (stream.isClosed()) {
                    return;
                }

                if (stream.usesFdOwnerTls()) {
                    LoanedBuffer offered = stream.readTlsIngressFromFd();
                    if (offered != null) {
                        stream.offerIngress(offered);
                        continue;
                    }
                    if (stream.isClosed()) {
                        return;
                    }
                    LockSupport.parkNanos(250_000L);
                    continue;
                }

                readIngress(channel);
            } catch (RuntimeException _) {
                stream.markRemoteClosed();
                return;
            }
        }
    }

    private TlsEngine createTlsEngineIfEnabled() {
        if (cryptoProvider == null || cryptoConfig == null) {
            return null;
        }
        return cryptoProvider.createTlsEngine(cryptoConfig);
    }

    private static void bindTlsFdIfRequired(TlsEngine tlsEngine, SocketChannel channel) {
        if (!(tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine communityTlsEngine)) {
            return;
        }
        communityTlsEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(channel));
    }

    private void closeSelectorAndChannels() {
        for (NativeTcpStream stream : streamByChannel.values()) {
            stream.close();
        }
        streamByChannel.clear();
        channelOwner.clear();

        for (Thread thread : clientIngressThreads.values()) {
            thread.interrupt();
        }
        clientIngressThreads.clear();

        for (ReactorLoop reactor : reactors) {
            reactor.closeSelector();
        }
        reactors.clear();
    }

    private static InetSocketAddress resolveRemoteAddress(SocketChannel channel) throws IOException {
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private final class ReactorLoop {

        private final int index;
        private final Selector selector;
        private final Queue<SocketChannel> pendingRegistrations = new ConcurrentLinkedQueue<>();
        private final Queue<SocketChannel> pendingWriteInterests = new ConcurrentLinkedQueue<>();

        private volatile Thread thread;

        private ReactorLoop(int index, Selector selector) {
            this.index = index;
            this.selector = selector;
        }

        private void start() {
            this.thread = Thread.ofPlatform()
                    .name("carrier/native-tcp/reactor/" + index)
                    .start(this::runLoop);
        }

        private void enqueueRegistration(SocketChannel channel) {
            pendingRegistrations.offer(channel);
            selector.wakeup();
        }

        private void enqueueWriteInterest(SocketChannel channel) {
            pendingWriteInterests.offer(channel);
            selector.wakeup();
        }

        private void cancelKey(SocketChannel channel) {
            SelectionKey key = channel.keyFor(selector);
            if (key != null) {
                key.cancel();
            }
            selector.wakeup();
        }

        private void wakeup() {
            selector.wakeup();
        }

        private void join(long timeoutMs) {
            Thread localThread = thread;
            if (localThread == null) {
                return;
            }
            try {
                localThread.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void closeSelector() {
            closeQuietly(selector);
        }

        private void runLoop() {
            while (running.get()) {
                try {
                    drainRegistrations();
                    drainWriteInterests();

                    selector.select(100L);
                    Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();

                        try {
                            if (!key.isValid()) {
                                continue;
                            }
                            if (key.isReadable()) {
                                readIngress((SocketChannel) key.channel());
                            }
                            if (key.isWritable()) {
                                flushStream((SocketChannel) key.channel(), key);
                            }
                        } catch (CancelledKeyException ignored) {
                            // channel closed concurrently by VT handler path
                        } catch (RuntimeException _) {
                            closeKeyStream(key);
                        }
                    }
                } catch (IOException e) {
                    if (!running.get()) {
                        return;
                    }
                    running.set(false);
                    throw TransportException.engineStartFailure(engineName(), config.port(), e);
                }
            }
        }

        private void drainRegistrations() {
            SocketChannel channel = pendingRegistrations.poll();
            while (channel != null) {
                try {
                    if (channel.isOpen()) {
                        channel.register(selector, SelectionKey.OP_READ);
                    }
                } catch (IOException e) {
                    NativeTcpStream stream = streamByChannel.get(channel);
                    if (stream != null) {
                        stream.close();
                    }
                }
                channel = pendingRegistrations.poll();
            }
        }

        private void drainWriteInterests() {
            SocketChannel channel = pendingWriteInterests.poll();
            while (channel != null) {
                SelectionKey key = channel.keyFor(selector);
                if (key != null && key.isValid()) {
                    try {
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                    } catch (RuntimeException ignored) {
                        // key may be cancelled concurrently
                    }
                }
                channel = pendingWriteInterests.poll();
            }
        }
    }
}
