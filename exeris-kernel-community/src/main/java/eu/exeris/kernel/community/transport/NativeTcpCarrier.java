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
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.ConnectionHandler;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    "PMD.LawOfDemeter",
    "PMD.AvoidInstantiatingObjectsInLoops"
})
public final class NativeTcpCarrier implements TransportEngine {

    private static final System.Logger LOG = System.getLogger(NativeTcpCarrier.class.getName());
    private static final String ENGINE_NAME = "CommunityNativeTcpCarrier";
    private static final long CLIENT_TLS_INGRESS_IDLE_BACKOFF_INITIAL_NANOS = 250_000L;
    private static final long CLIENT_TLS_INGRESS_IDLE_BACKOFF_MAX_NANOS = 2_000_000L;
    private static final long CLIENT_WRITER_BACKOFF_INITIAL_NANOS = 250_000L;
    private static final long CLIENT_WRITER_BACKOFF_MAX_NANOS = 2_000_000L;
    private static final int MIN_LISTENER_BACKLOG = 64;
    private static final int MAX_LISTENER_BACKLOG = 1_024;

    private final TransportConfig config;
    private final MemoryAllocator allocator;
    private final KernelCryptoProvider cryptoProvider;
    private final CryptoProviderConfig cryptoConfig;
    private final NativeTcpSocketBackend backend;

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
    private final List<NativeTcpReactor> reactors = new ArrayList<>();
    private final AtomicInteger nextReactorIndex = new AtomicInteger(0);

    private final ChannelRuntimeRegistry channelRuntimeRegistry = new ChannelRuntimeRegistry();

    /**
     * Compatibility mirrors retained for diagnostics and existing transport tests.
     */
    private final ConcurrentMap<SocketChannel, ChannelRuntimeRegistry.ChannelRuntimeState> runtimeByChannel =
            channelRuntimeRegistry.runtimeByChannel;
    private final ConcurrentMap<SocketChannel, NativeTcpStream> streamByChannel =
            channelRuntimeRegistry.streamByChannel;
    private final ConcurrentMap<SocketChannel, NativeTcpReactor> channelOwner =
            channelRuntimeRegistry.channelOwner;

    /* default */ NativeTcpCarrier(TransportConfig config,
                     MemoryAllocator allocator,
                     KernelCryptoProvider cryptoProvider,
                     CryptoProviderConfig cryptoConfig) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.cryptoProvider = cryptoProvider;
        this.cryptoConfig = cryptoConfig;
        this.backend = new NativeTcpSocketBackend();
        LOG.log(System.Logger.Level.INFO, () ->
                "[NativeTcpCarrier] Community socket backend mode="
                        + backend.requestedSocketBackend()
                        + ", active=" + backend.activeSocketBackend()
                        + ", ffmArmed=" + backend.isFfmSocketBackendArmed()
                        + " - " + backend.detail());
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

        for (NativeTcpReactor reactor : reactors) {
            reactor.wakeup();
        }
        for (NativeTcpReactor reactor : reactors) {
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

        SocketChannel channel = null;
        TlsEngine tlsEngine = null;
        try {
            backend.validateClientSocketBackendOnce(allocator, host, port);
            channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(host, port));
            tlsEngine = createTlsEngineIfEnabled();
            bindTlsFdIfRequired(tlsEngine, channel);
            if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                channel.configureBlocking(false);
            }
            final SocketChannel connectedChannel = channel;

            NativeTcpConnection connection = new NativeTcpConnection(
                    connectionSeq.getAndIncrement(),
                    host,
                    port);

            NativeTcpStream stream = new NativeTcpStream(
                    engineName(),
                    streamSeq.getAndIncrement(),
                    connectedChannel,
                    connection,
                    allocator,
                    tlsEngine,
                    () -> requestClientWriteFlush(connectedChannel),
                    () -> onStreamClosed(connectedChannel),
                    backend.socketHandles());
            if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                stream.markTlsBoundFromCarrier();
            }

            connection.bindSingleStream(stream);
            ChannelRuntimeRegistry.ChannelRuntimeState runtime = registerRuntime(stream, connectedChannel);
            startClientIngressPump(runtime);
            startClientWriterPump(runtime);
            activeConnections.incrementAndGet();
            activeStreams.incrementAndGet();
            totalAccepted.incrementAndGet();
            return connection;
        } catch (IOException e) {
            closeQuietly(tlsEngine);
            closeQuietly(channel);
            throw TransportException.bindFailure(engineName(), port, e);
        } catch (RuntimeException e) {
            closeQuietly(tlsEngine);
            closeQuietly(channel);
            throw e;
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
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            stop();
        } finally {
            backend.close();
        }
    }

    /* default */ boolean isRunning() {
        return running.get();
    }

    /* default */ String requestedSocketBackend() {
        return backend.requestedSocketBackend();
    }

    /* default */ String activeSocketBackend() {
        return backend.activeSocketBackend();
    }

    /* default */ boolean isFfmSocketBackendArmed() {
        return backend.isFfmSocketBackendArmed();
    }

    /* default */ boolean isServerSocketValidationSuccessful() {
        return backend.isServerSocketValidationSuccessful();
    }

    /* default */ boolean isClientSocketValidationSuccessful() {
        return backend.isClientSocketValidationSuccessful();
    }

    /* default */ int listenerBacklog() {
        return computeListenerBacklog(config.maxConnections());
    }

    private static int computeListenerBacklog(int maxConnections) {
        if (maxConnections <= 0) {
            return MIN_LISTENER_BACKLOG;
        }
        return Math.clamp(maxConnections, MIN_LISTENER_BACKLOG, MAX_LISTENER_BACKLOG);
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
            backend.validateServerSocketBootstrapOnce(allocator);
            int reactorCount = Math.max(1, config.reactorCount());
            reactors.clear();
            for (int i = 0; i < reactorCount; i++) {
                reactors.add(new NativeTcpReactor(this, i, Selector.open()));
            }
            nextReactorIndex.set(0);

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.bind(new InetSocketAddress(config.bindAddress(), config.port()), listenerBacklog());

            for (NativeTcpReactor reactor : reactors) {
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
                if (running.get()) {
                    handleAsyncFailure("acceptor", e);
                }
                return;
            }
        }
    }

    /* default */ void handleAsyncFailure(String stage, Exception error) {
        boolean wasRunning = running.getAndSet(false);
        closeQuietly(serverChannel);
        for (NativeTcpReactor reactor : reactors) {
            reactor.wakeup();
        }
        if (wasRunning) {
            LOG.log(System.Logger.Level.WARNING, "Async transport failure in " + stage, error);
        }
    }

    private boolean tryReserveConnectionSlot() {
        long maxConnections = config.maxConnections();
        while (true) {
            long current = activeConnections.get();
            if (current >= maxConnections) {
                return false;
            }
            if (activeConnections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /* default */ void closeKeyStream(SelectionKey key) {
        if (!(key.channel() instanceof SocketChannel channel)) {
            return;
        }
        NativeTcpStream stream = resolveStream(channel);
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
            boolean slotReserved = false;
            boolean connectionManagedByStreamLifecycle = false;
            try {
                slotReserved = tryReserveConnectionSlot();
                if (!slotReserved) {
                    closeQuietly(currentChannel);
                    acceptedChannel = serverChannel.accept();
                    continue;
                }
                currentChannel.configureBlocking(false);
                currentChannel.socket().setTcpNoDelay(true);

                InetSocketAddress remote = resolveRemoteAddress(currentChannel);
                connection = new NativeTcpConnection(
                        connectionSeq.getAndIncrement(),
                        remote.getAddress().getHostAddress(),
                        remote.getPort());

                NativeTcpStream stream = buildAcceptedStream(currentChannel, connection);
                connection.bindSingleStream(stream);
                connectionManagedByStreamLifecycle = true;
                registerAndHandshakeConnection(connection, stream, currentChannel);
            } catch (RuntimeException exception) {
                if (connection != null) {
                    connection.close();
                } else {
                    closeQuietly(currentChannel);
                }
            } finally {
                if (slotReserved && !connectionManagedByStreamLifecycle) {
                    activeConnections.decrementAndGet();
                }
            }
            acceptedChannel = serverChannel.accept();
        }
    }

    private NativeTcpStream buildAcceptedStream(SocketChannel channel, NativeTcpConnection connection) {
        TlsEngine tlsEngine = createTlsEngineIfEnabled();
        bindTlsFdIfRequired(tlsEngine, channel);
        NativeTcpStream stream = new NativeTcpStream(
                engineName(),
                streamSeq.getAndIncrement(),
                channel,
                connection,
                allocator,
                tlsEngine,
                () -> requestWriteInterest(channel),
                () -> onStreamClosed(channel),
                backend.socketHandles());
        if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
            stream.markTlsBoundFromCarrier();
        }
        return stream;
    }

    /**
     * Registers the stream with a reactor, awaits TLS handshake, notifies the connection handler,
     * and enqueues the stream in PAQS. Returns {@code false} if the connection should be rejected.
     */
    private boolean registerAndHandshakeConnection(NativeTcpConnection connection,
                                                   NativeTcpStream stream,
                                                   SocketChannel currentChannel) {
        ChannelRuntimeRegistry.ChannelRuntimeState runtime = registerRuntime(stream, currentChannel);
        NativeTcpReactor owner = selectReactor();
        runtime.markRegistrationPending();
        runtime.bindOwner(owner);
        owner.enqueueRegistration(currentChannel);

        activeStreams.incrementAndGet();
        totalAccepted.incrementAndGet();

        if (!stream.awaitRegistrationReadyForConnection()) {
            connection.close();
            return false;
        }
        if (!stream.awaitHandshakeReadyForConnection()) {
            connection.close();
            return false;
        }
        try {
            connectionHandler.onConnectionEstablished(connection);
        } catch (RuntimeException ignored) {
            connection.close();
            return false;
        }
        try {
            paqs.schedule(stream);
        } catch (RuntimeException ex) {
            connection.close();
        }
        return true;
    }

    private NativeTcpReactor selectReactor() {
        int size = reactors.size();
        if (size == 0) {
            throw new IllegalStateException("No reactor loops initialized");
        }
        int index = Math.floorMod(nextReactorIndex.getAndIncrement(), size);
        return reactors.get(index);
    }

    private ChannelRuntimeRegistry.ChannelRuntimeState registerRuntime(NativeTcpStream stream,
                                                                       SocketChannel channel) {
        return channelRuntimeRegistry.registerRuntime(stream, channel);
    }

    private ChannelRuntimeRegistry.ChannelRuntimeState resolveRuntime(SocketChannel channel) {
        return channelRuntimeRegistry.resolveRuntime(channel);
    }

    /* default */ NativeTcpStream resolveStream(SocketChannel channel) {
        return channelRuntimeRegistry.resolveStream(channel);
    }

    /* default */ void readIngress(SocketChannel channel) {
        NativeTcpStream stream = resolveStream(channel);
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
            int read = stream.readPlainIngress(slab.segment(), (int) slab.capacity());
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
            stream.markRemoteClosed();
            stream.close();
        }
    }

    private LoanedBuffer adaptTlsIfNeeded(NativeTcpStream stream, LoanedBuffer slab, int read) {
        if (stream.tlsEngine() == null) {
            slab.retain();
            return slab;
        }
        return stream.decryptIngress(slab, read);
    }

    /* default */ void flushStream(SocketChannel channel, SelectionKey key) {
        NativeTcpStream stream = resolveStream(channel);
        if (stream == null) {
            key.interestOps(SelectionKey.OP_READ);
            return;
        }

        stream.signalWriteReady();
        boolean drained = stream.flushPendingWrites();
        if (drained && !stream.hasPendingData()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void requestWriteInterest(SocketChannel channel) {
        ChannelRuntimeRegistry.ChannelRuntimeState runtime = resolveRuntime(channel);
        NativeTcpReactor owner = runtime != null ? runtime.owner() : channelOwner.get(channel);
        if (owner == null) {
            return;
        }
        owner.enqueueWriteInterest(channel);
    }

    private void onStreamClosed(SocketChannel channel) {
        ChannelRuntimeRegistry.ChannelRuntimeState runtime = runtimeByChannel.remove(channel);
        if (runtime == null) {
            NativeTcpReactor owner = channelOwner.remove(channel);
            if (owner != null) {
                owner.cancelKey(channel);
            }
            if (streamByChannel.remove(channel) != null) {
                activeStreams.decrementAndGet();
                activeConnections.decrementAndGet();
            }
            return;
        }

        NativeTcpReactor owner = runtime.detachOwner();
        if (owner != null) {
            owner.cancelKey(channel);
        }

        Thread clientIngressThread = runtime.detachClientIngressThread();
        if (clientIngressThread != null) {
            interruptAndJoin(clientIngressThread);
        }
        Thread clientWriterThread = runtime.detachClientWriterThread();
        if (clientWriterThread != null) {
            interruptAndJoin(clientWriterThread);
        }

        streamByChannel.remove(channel, runtime.stream());
        channelOwner.remove(channel);

        if (runtime.beginLifecycleCleanup()) {
            activeStreams.decrementAndGet();
            activeConnections.decrementAndGet();
        }
    }

    private void requestClientWriteFlush(SocketChannel channel) {
        ChannelRuntimeRegistry.ChannelRuntimeState runtime = resolveRuntime(channel);
        if (runtime == null) {
            return;
        }
        Thread writer = runtime.clientWriterThread();
        if (writer != null) {
            LockSupport.unpark(writer);
        }
    }

    private void startClientIngressPump(ChannelRuntimeRegistry.ChannelRuntimeState runtime) {
        Thread thread = Thread.ofVirtual()
                .name("carrier/native-tcp-client-ingress/" + runtime.stream().streamId())
                .start(() -> runClientIngressLoop(runtime.channel(), runtime.stream()));
        runtime.bindClientIngressThread(thread);
    }

    private void startClientWriterPump(ChannelRuntimeRegistry.ChannelRuntimeState runtime) {
        Thread thread = Thread.ofVirtual()
                .name("carrier/native-tcp-client-writer/" + runtime.stream().streamId())
                .start(() -> runClientWriterLoop(runtime.stream()));
        runtime.bindClientWriterThread(thread);
    }

    private void runClientIngressLoop(SocketChannel channel, NativeTcpStream stream) {
        long idleBackoffNanos = CLIENT_TLS_INGRESS_IDLE_BACKOFF_INITIAL_NANOS;
        while (running.get()) {
            try {
                if (stream.isClosed()) {
                    return;
                }

                if (stream.usesFdOwnerTls()) {
                    LoanedBuffer offered = stream.readTlsIngressFromFd();
                    if (offered != null) {
                        stream.offerIngress(offered);
                        idleBackoffNanos = CLIENT_TLS_INGRESS_IDLE_BACKOFF_INITIAL_NANOS;
                        continue;
                    }
                    if (stream.isClosed()) {
                        return;
                    }
                    LockSupport.parkNanos(idleBackoffNanos);
                    idleBackoffNanos = Math.min(
                            idleBackoffNanos << 1,
                            CLIENT_TLS_INGRESS_IDLE_BACKOFF_MAX_NANOS);
                    continue;
                }

                readIngress(channel);
                idleBackoffNanos = CLIENT_TLS_INGRESS_IDLE_BACKOFF_INITIAL_NANOS;
            } catch (RuntimeException _) {
                stream.markRemoteClosed();
                return;
            }
        }
    }

    private void runClientWriterLoop(NativeTcpStream stream) {
        long writeBackoffNanos = CLIENT_WRITER_BACKOFF_INITIAL_NANOS;
        while (running.get() && !stream.isClosed()) {
            try {
                stream.signalWriteReady();
                if (stream.hasPendingData()) {
                    boolean drained = stream.flushPendingWrites();
                    if (drained) {
                        writeBackoffNanos = CLIENT_WRITER_BACKOFF_INITIAL_NANOS;
                        continue;
                    }
                    LockSupport.parkNanos(writeBackoffNanos);
                    writeBackoffNanos = Math.min(writeBackoffNanos << 1, CLIENT_WRITER_BACKOFF_MAX_NANOS);
                    continue;
                }
                writeBackoffNanos = CLIENT_WRITER_BACKOFF_INITIAL_NANOS;
                LockSupport.park();
                if (Thread.interrupted() && (!running.get() || stream.isClosed())) {
                    return;
                }
            } catch (RuntimeException _) {
                stream.close();
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
        for (ChannelRuntimeRegistry.ChannelRuntimeState runtime : new ArrayList<>(runtimeByChannel.values())) {
            runtime.stream().close();
            Thread ingressThread = runtime.detachClientIngressThread();
            if (ingressThread != null) {
                ingressThread.interrupt();
            }
            Thread writerThread = runtime.detachClientWriterThread();
            if (writerThread != null) {
                writerThread.interrupt();
            }
        }
        runtimeByChannel.clear();
        streamByChannel.clear();
        channelOwner.clear();

        for (NativeTcpReactor reactor : reactors) {
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

    private static void interruptAndJoin(Thread thread) {
        thread.interrupt();
        if (Objects.equals(thread, Thread.currentThread())) {
            return;
        }
        try {
            thread.join(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
