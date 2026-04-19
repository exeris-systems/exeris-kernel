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
import eu.exeris.kernel.core.transport.syscall.CoreSyscallLoader;
import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
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
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final System.Logger LOG = System.getLogger(NativeTcpCarrier.class.getName());
    private static final String ENGINE_NAME = "CommunityNativeTcpCarrier";
    private static final String SOCKET_BACKEND_PROPERTY = "exeris.community.transport.socket.backend";
    private static final String SOCKET_BACKEND_ENV = "EXERIS_COMMUNITY_TRANSPORT_SOCKET_BACKEND";
    private static final String SOCKET_BACKEND_ENV_ALIAS = "SOCKET_BACKEND_ENV";
    private static final String SOCKET_BACKEND_AUTO = "auto";
    private static final String SOCKET_BACKEND_NIO = "nio";
    private static final String SOCKET_BACKEND_POSIX_HYBRID = "posix-hybrid";
    private static final String ACTIVE_NIO_FALLBACK_DETAIL = "continuing on the active NIO fallback.";
    private static final String LOOPBACK_HOST = InetAddress.getLoopbackAddress().getHostAddress();
    private static final byte LOOPBACK_FIRST_OCTET = 127;
    private static final byte LOOPBACK_SECOND_OCTET = 0;
    private static final byte LOOPBACK_THIRD_OCTET = 0;
    private static final byte LOOPBACK_FOURTH_OCTET = 1;
    private static final int AF_INET = 2;
    private static final int SOCK_STREAM = 1;
    private static final int DEFAULT_IP_PROTOCOL = 0;
    private static final int SOCKADDR_IN_SIZE = 16;
    private static final boolean SOCKADDR_INCLUDES_LENGTH = usesBsdSockaddrLayout();
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
    private final SocketBackendMode requestedSocketBackend;
    private final Arena socketBackendArena;
    private final SyscallHandles socketHandles;
    private final boolean ffmSocketBackendArmed;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean serverSocketValidationAttempted = new AtomicBoolean(false);
    private final AtomicBoolean serverSocketValidationSuccessful = new AtomicBoolean(false);
    private final AtomicBoolean clientSocketValidationAttempted = new AtomicBoolean(false);
    private final AtomicBoolean clientSocketValidationSuccessful = new AtomicBoolean(false);

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

    /**
     * Canonical per-channel runtime registry. Carrier-side ownership, client pumps,
     * and stream lifecycle coordination resolve through this map.
     */
    private final ConcurrentMap<SocketChannel, ChannelRuntimeState> runtimeByChannel = new ConcurrentHashMap<>();

    /**
     * Compatibility mirrors retained for diagnostics and existing transport tests.
     */
    private final ConcurrentMap<SocketChannel, NativeTcpStream> streamByChannel = new ConcurrentHashMap<>();
    private final ConcurrentMap<SocketChannel, ReactorLoop> channelOwner = new ConcurrentHashMap<>();

    /* default */ NativeTcpCarrier(TransportConfig config,
                     MemoryAllocator allocator,
                     KernelCryptoProvider cryptoProvider,
                     CryptoProviderConfig cryptoConfig) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.cryptoProvider = cryptoProvider;
        this.cryptoConfig = cryptoConfig;
        SocketBackendSelection backendSelection = SocketBackendSelection.resolve();
        this.requestedSocketBackend = backendSelection.requestedMode();
        this.socketBackendArena = backendSelection.socketBackendArena();
        this.socketHandles = backendSelection.socketHandles();
        this.ffmSocketBackendArmed = backendSelection.ffmSocketBackendArmed();
        LOG.log(System.Logger.Level.INFO, () ->
                "[NativeTcpCarrier] Community socket backend mode="
                        + backendSelection.requestedMode().configValue()
                        + ", active=" + activeSocketBackend()
                        + ", ffmArmed=" + backendSelection.ffmSocketBackendArmed()
                        + " - " + backendSelection.detail());
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

        SocketChannel channel = null;
        TlsEngine tlsEngine = null;
        try {
            validateClientSocketBackendOnce(host, port);
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
                    socketHandles);
            if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                stream.markTlsBoundFromCarrier();
            }

            connection.bindSingleStream(stream);
            ChannelRuntimeState runtime = registerRuntime(stream, connectedChannel);
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
        final Arena backendArena = socketBackendArena;
        if (backendArena == null) {
            stop();
            return;
        }
        try (backendArena) {
            stop();
        }
    }

    /* default */ String requestedSocketBackend() {
        return requestedSocketBackend.configValue();
    }

    /* default */ String activeSocketBackend() {
        if (requestedSocketBackend == SocketBackendMode.NIO) {
            return SOCKET_BACKEND_NIO;
        }
        return isPosixHybridBackendActive() ? SOCKET_BACKEND_POSIX_HYBRID : SOCKET_BACKEND_NIO;
    }

    private boolean isPosixHybridBackendActive() {
        return ffmSocketBackendArmed;
    }

    /* default */ boolean isFfmSocketBackendArmed() {
        return ffmSocketBackendArmed;
    }

    /* default */ boolean isServerSocketValidationSuccessful() {
        return serverSocketValidationSuccessful.get();
    }

    /* default */ boolean isClientSocketValidationSuccessful() {
        return clientSocketValidationSuccessful.get();
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
            validateServerSocketBootstrapOnce();
            int reactorCount = Math.max(1, config.reactorCount());
            reactors.clear();
            for (int i = 0; i < reactorCount; i++) {
                reactors.add(new ReactorLoop(i, Selector.open()));
            }
            nextReactorIndex.set(0);

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.bind(new InetSocketAddress(config.bindAddress(), config.port()), listenerBacklog());

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
                if (running.get()) {
                    handleAsyncFailure("acceptor", e);
                }
                return;
            }
        }
    }

    private void handleAsyncFailure(String stage, Exception error) {
        boolean wasRunning = running.getAndSet(false);
        closeQuietly(serverChannel);
        for (ReactorLoop reactor : reactors) {
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

    private void closeKeyStream(SelectionKey key) {
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
                        () -> onStreamClosed(currentChannel),
                        socketHandles);
                if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                    stream.markTlsBoundFromCarrier();
                }

                connection.bindSingleStream(stream);
                ChannelRuntimeState runtime = registerRuntime(stream, currentChannel);
                connectionManagedByStreamLifecycle = true;
                ReactorLoop owner = selectReactor();
                runtime.markRegistrationPending();
                runtime.bindOwner(owner);
                owner.enqueueRegistration(currentChannel);

                activeStreams.incrementAndGet();
                totalAccepted.incrementAndGet();

                if (!stream.awaitRegistrationReadyForConnection()) {
                    connection.close();
                    acceptedChannel = serverChannel.accept();
                    continue;
                }

                if (!stream.awaitHandshakeReadyForConnection()) {
                    connection.close();
                    acceptedChannel = serverChannel.accept();
                    continue;
                }

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
            } finally {
                if (slotReserved && !connectionManagedByStreamLifecycle) {
                    activeConnections.decrementAndGet();
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

    private ChannelRuntimeState registerRuntime(NativeTcpStream stream,
                                                SocketChannel channel) {
        ChannelRuntimeState runtime = new ChannelRuntimeState(channel, stream);
        ChannelRuntimeState previous = runtimeByChannel.putIfAbsent(channel, runtime);
        if (previous != null) {
            throw new IllegalStateException(
                    "Transport runtime already registered for channel on backend "
                            + previous.socketBackend() + ": " + channel);
        }
        streamByChannel.put(channel, stream);
        return runtime;
    }

    private ChannelRuntimeState resolveRuntime(SocketChannel channel) {
        return runtimeByChannel.get(channel);
    }

    private NativeTcpStream resolveStream(SocketChannel channel) {
        ChannelRuntimeState runtime = resolveRuntime(channel);
        return runtime != null ? runtime.stream() : streamByChannel.get(channel);
    }

    private void readIngress(SocketChannel channel) {
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

    private void flushStream(SocketChannel channel, SelectionKey key) {
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
        ChannelRuntimeState runtime = resolveRuntime(channel);
        ReactorLoop owner = runtime != null ? runtime.owner() : channelOwner.get(channel);
        if (owner == null) {
            return;
        }
        owner.enqueueWriteInterest(channel);
    }

    private void onStreamClosed(SocketChannel channel) {
        ChannelRuntimeState runtime = runtimeByChannel.remove(channel);
        if (runtime == null) {
            ReactorLoop owner = channelOwner.remove(channel);
            if (owner != null) {
                owner.cancelKey(channel);
            }
            if (streamByChannel.remove(channel) != null) {
                activeStreams.decrementAndGet();
                activeConnections.decrementAndGet();
            }
            return;
        }

        ReactorLoop owner = runtime.detachOwner();
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
        ChannelRuntimeState runtime = resolveRuntime(channel);
        if (runtime == null) {
            return;
        }
        Thread writer = runtime.clientWriterThread();
        if (writer != null) {
            LockSupport.unpark(writer);
        }
    }

    private void startClientIngressPump(ChannelRuntimeState runtime) {
        Thread thread = Thread.ofVirtual()
                .name("carrier/native-tcp-client-ingress/" + runtime.stream().streamId())
                .start(() -> runClientIngressLoop(runtime.channel(), runtime.stream()));
        runtime.bindClientIngressThread(thread);
    }

    private void startClientWriterPump(ChannelRuntimeState runtime) {
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

    private void validateServerSocketBootstrapOnce() {
        if (!ffmSocketBackendArmed || !serverSocketValidationAttempted.compareAndSet(false, true)) {
            return;
        }
        serverSocketValidationSuccessful.set(runServerSocketValidationProbe());
        if (!serverSocketValidationSuccessful.get()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Core socket seam could not be validated for the server path; continuing on the active NIO path.");
        }
    }

    private void validateClientSocketBackendOnce(String host, int port) {
        if (!ffmSocketBackendArmed || !clientSocketValidationAttempted.compareAndSet(false, true)) {
            return;
        }
        clientSocketValidationSuccessful.set(runClientSocketValidationProbe(host, port));
        if (!clientSocketValidationSuccessful.get()) {
            LOG.log(System.Logger.Level.DEBUG, () ->
                    "Core socket seam could not be validated for the client path " + host + ':' + port
                            + "; continuing on the active NIO path.");
        }
    }

    private SyscallHandles resolvedSocketHandlesForValidation() {
        if (!ffmSocketBackendArmed) {
            return null;
        }
        SyscallHandles handles = this.socketHandles;
        return handles != null && handles.supportsPlainSocketIo() ? handles : null;
    }

    private boolean runServerSocketValidationProbe() {
        SyscallHandles handles = resolvedSocketHandlesForValidation();
        return handles != null && validateServerSocketBootstrap(handles);
    }

    private boolean runClientSocketValidationProbe(String host, int port) {
        SyscallHandles handles = resolvedSocketHandlesForValidation();
        return handles != null && validateClientSocketConnect(handles, host, port);
    }

    private boolean validateServerSocketBootstrap(SyscallHandles handles) {
        int socketFd = -1;
        try (LoanedBuffer scratch = allocator.allocateInfrastructure(SOCKADDR_IN_SIZE)) {
            socketFd = openPosixStreamSocket(handles);
            MemorySegment sockaddr = scratch.segment().asSlice(0, SOCKADDR_IN_SIZE);
            writeIpv4Sockaddr(sockaddr,
                    0,
                    LOOPBACK_FIRST_OCTET,
                    LOOPBACK_SECOND_OCTET,
                    LOOPBACK_THIRD_OCTET,
                    LOOPBACK_FOURTH_OCTET);
            return bindSocket(handles, socketFd, sockaddr) == 0
                    && listenSocket(handles, socketFd) == 0;
        } catch (RuntimeException _) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Core socket server bootstrap probe failed; compatibility fallback remains active.");
            return false;
        } finally {
            closePosixSocketQuietly(handles, socketFd);
        }
    }

    private boolean validateClientSocketConnect(SyscallHandles handles, String host, int port) {
        int socketFd = -1;
        try (ServerSocketChannel probeListener = ServerSocketChannel.open();
             LoanedBuffer scratch = allocator.allocateInfrastructure(SOCKADDR_IN_SIZE)) {
            probeListener.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
            int probePort = ((InetSocketAddress) probeListener.getLocalAddress()).getPort();

            socketFd = openPosixStreamSocket(handles);
            MemorySegment sockaddr = scratch.segment().asSlice(0, SOCKADDR_IN_SIZE);
            writeIpv4Sockaddr(sockaddr,
                    probePort,
                    LOOPBACK_FIRST_OCTET,
                    LOOPBACK_SECOND_OCTET,
                    LOOPBACK_THIRD_OCTET,
                    LOOPBACK_FOURTH_OCTET);
            if (connectSocket(handles, socketFd, sockaddr) != 0) {
                return false;
            }

            try (SocketChannel accepted = probeListener.accept()) {
                return accepted != null;
            }
        } catch (IOException | RuntimeException _) {
            LOG.log(System.Logger.Level.DEBUG, () ->
                    "Core socket client probe failed for " + host + ':' + port
                            + "; compatibility fallback remains active.");
            return false;
        } finally {
            closePosixSocketQuietly(handles, socketFd);
        }
    }

    private static void writeIpv4Sockaddr(MemorySegment target,
                                          int port,
                                          byte firstOctet,
                                          byte secondOctet,
                                          byte thirdOctet,
                                          byte fourthOctet) {
        target.fill((byte) 0);
        if (SOCKADDR_INCLUDES_LENGTH) {
            target.set(ValueLayout.JAVA_BYTE, 0, (byte) SOCKADDR_IN_SIZE);
            target.set(ValueLayout.JAVA_BYTE, 1, (byte) AF_INET);
        } else {
            target.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
        }
        target.set(ValueLayout.JAVA_BYTE, 2, (byte) ((port >>> 8) & 0xFF));
        target.set(ValueLayout.JAVA_BYTE, 3, (byte) (port & 0xFF));
        target.set(ValueLayout.JAVA_BYTE, 4, firstOctet);
        target.set(ValueLayout.JAVA_BYTE, 5, secondOctet);
        target.set(ValueLayout.JAVA_BYTE, 6, thirdOctet);
        target.set(ValueLayout.JAVA_BYTE, 7, fourthOctet);
    }

    private static boolean usesBsdSockaddrLayout() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac")
                || osName.contains("darwin")
                || osName.contains("bsd");
    }

    private static int openPosixStreamSocket(SyscallHandles handles) {
        try {
            return (int) handles.socket().invokeExact(AF_INET, SOCK_STREAM, DEFAULT_IP_PROTOCOL);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core socket() probe failed", ex);
        }
    }

    private static int bindSocket(SyscallHandles handles, int socketFd, MemorySegment sockaddr) {
        try {
            return (int) handles.bind().invokeExact(socketFd, sockaddr, SOCKADDR_IN_SIZE);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core bind() probe failed", ex);
        }
    }

    private static int connectSocket(SyscallHandles handles, int socketFd, MemorySegment sockaddr) {
        try {
            return (int) handles.connect().invokeExact(socketFd, sockaddr, SOCKADDR_IN_SIZE);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core connect() probe failed", ex);
        }
    }

    private static int listenSocket(SyscallHandles handles, int socketFd) {
        try {
            return (int) handles.listen().invokeExact(socketFd, 1);
        } catch (Throwable ex) {
            throw new IllegalStateException("Core listen() probe failed", ex);
        }
    }

    private static void closePosixSocketQuietly(SyscallHandles handles, int socketFd) {
        if (socketFd < 0) {
            return;
        }
        try {
            @SuppressWarnings("unused")
            int ignored = (int) handles.close().invokeExact(socketFd);
        } catch (Throwable _) {
            // best effort cleanup on the validation probe path
        }
    }

    private void closeSelectorAndChannels() {
        for (ChannelRuntimeState runtime : new ArrayList<>(runtimeByChannel.values())) {
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

    private enum SocketBackendMode {
        AUTO(SOCKET_BACKEND_AUTO),
        NIO(SOCKET_BACKEND_NIO),
        POSIX_HYBRID(SOCKET_BACKEND_POSIX_HYBRID);

        private final String configValue;

        SocketBackendMode(String configValue) {
            this.configValue = configValue;
        }

        private String configValue() {
            return configValue;
        }

        private static SocketBackendMode resolveConfiguredMode() {
            String configured = System.getProperty(SOCKET_BACKEND_PROPERTY);
            if (configured == null || configured.isBlank()) {
                configured = System.getProperty(SOCKET_BACKEND_ENV_ALIAS);
            }
            if (configured == null || configured.isBlank()) {
                configured = System.getenv(SOCKET_BACKEND_ENV);
            }
            if (configured == null || configured.isBlank()) {
                configured = System.getenv(SOCKET_BACKEND_ENV_ALIAS);
            }
            if (configured == null || configured.isBlank()) {
                return AUTO;
            }
            return switch (configured.trim().toLowerCase(Locale.ROOT)) {
                case SOCKET_BACKEND_AUTO -> AUTO;
                case SOCKET_BACKEND_NIO -> NIO;
                case SOCKET_BACKEND_POSIX_HYBRID -> POSIX_HYBRID;
                default -> {
                    String invalidMode = configured;
                    LOG.log(System.Logger.Level.WARNING, () ->
                            "Unknown Community socket backend mode '" + invalidMode + "'; defaulting to auto.");
                    yield AUTO;
                }
            };
        }
    }

    private record SocketBackendSelection(SocketBackendMode requestedMode,
                                          Arena socketBackendArena,
                                          SyscallHandles socketHandles,
                                          boolean ffmSocketBackendArmed,
                                          String detail) {

        private static SocketBackendSelection resolve() {
            SocketBackendMode configuredMode = SocketBackendMode.resolveConfiguredMode();
            if (configuredMode == SocketBackendMode.NIO) {
                return new SocketBackendSelection(
                        configuredMode,
                        null,
                        null,
                        false,
                        "NIO backend pinned explicitly; Core syscall load skipped.");
            }

            //CHECKSTYLE:OFF
            // Direct shared-arena allocation: the Community carrier is the sole owner of
            // the socket backend seam lifetime. Shared scope is required because resolved
            // syscall handles are used across transport threads, and carrier.close()
            // deterministically closes this arena.
            Arena arena = Arena.ofShared();
            //CHECKSTYLE:ON
            SyscallHandles handles = null;
            try {
                handles = CoreSyscallLoader.load(arena);
            } catch (IllegalCallerException | IllegalStateException | UnsupportedOperationException ex) {
                LOG.log(System.Logger.Level.DEBUG,
                        "[NativeTcpCarrier] Community-owned Core socket load unavailable; continuing on NIO fallback.",
                        ex);
            }

            if (handles == null) {
                closeQuietly(arena);
                String detail;
                if (configuredMode == SocketBackendMode.POSIX_HYBRID) {
                    detail = "Core socket seam was requested explicitly but is unavailable; "
                            + ACTIVE_NIO_FALLBACK_DETAIL;
                } else {
                    detail = "Core socket seam probe unavailable on this platform; "
                            + ACTIVE_NIO_FALLBACK_DETAIL;
                }
                return new SocketBackendSelection(configuredMode, null, null, false, detail);
            }

            boolean plainSocketIoAvailable = handles.supportsPlainSocketIo();
            boolean fdAccessAvailable = SocketChannelFdAccess.isRuntimeFdAccessAvailable();
            boolean winsockModel = handles.hasIoctlsocket();
            boolean seamArmed = plainSocketIoAvailable && fdAccessAvailable && !winsockModel;
            String detail = resolveDetail(configuredMode, seamArmed, plainSocketIoAvailable, winsockModel);
            return new SocketBackendSelection(configuredMode, arena, handles, seamArmed, detail);
        }

        private static String resolveDetail(SocketBackendMode configuredMode,
                                            boolean seamArmed,
                                            boolean plainSocketIoAvailable,
                                            boolean winsockModel) {
            if (seamArmed) {
                return "Core socket seam armed for plain TCP traffic; "
                        + "selector ownership and NIO fallback remain intact.";
            }
            if (configuredMode == SocketBackendMode.POSIX_HYBRID) {
                return resolveRequestedFallbackDetail(plainSocketIoAvailable, winsockModel);
            }
            return resolveAutoFallbackDetail(plainSocketIoAvailable, winsockModel);
        }

        private static String resolveRequestedFallbackDetail(boolean plainSocketIoAvailable,
                                                             boolean winsockModel) {
            if (!plainSocketIoAvailable) {
                return "Core socket seam was requested explicitly but is unavailable; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            if (winsockModel) {
                return "Core socket seam was requested explicitly but this platform uses the Winsock model; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            return "Core socket seam was requested explicitly but "
                    + "SocketChannel FD access is blocked by runtime openness; "
                    + "add the required --add-opens flags or "
                    + ACTIVE_NIO_FALLBACK_DETAIL;
        }

        private static String resolveAutoFallbackDetail(boolean plainSocketIoAvailable,
                                                        boolean winsockModel) {
            if (!plainSocketIoAvailable) {
                return "Core socket seam resolved without plain socket syscall support; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            if (winsockModel) {
                return "Core socket seam resolved on a Winsock platform; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            return "Core socket seam resolved but SocketChannel FD access "
                    + "is blocked by runtime openness; "
                    + "add the required --add-opens flags or "
                    + ACTIVE_NIO_FALLBACK_DETAIL;
        }

    }

    private final class ChannelRuntimeState {

        private final SocketChannel channel;
        private final NativeTcpStream stream;
        private final String socketBackend;
        private final AtomicReference<ReactorLoop> owner = new AtomicReference<>();
        private final AtomicReference<Thread> clientIngressThread = new AtomicReference<>();
        private final AtomicReference<Thread> clientWriterThread = new AtomicReference<>();
        private final AtomicBoolean lifecycleCleanup = new AtomicBoolean(false);

        private ChannelRuntimeState(SocketChannel channel,
                                    NativeTcpStream stream) {
            this.channel = channel;
            this.stream = stream;
            this.socketBackend = stream.plainSocketBackendName();
        }

        private SocketChannel channel() {
            return channel;
        }

        private NativeTcpStream stream() {
            return stream;
        }

        private String socketBackend() {
            return socketBackend;
        }

        private void bindOwner(ReactorLoop newOwner) {
            owner.set(newOwner);
            channelOwner.put(channel, newOwner);
        }

        private void markRegistrationPending() {
            stream.markRegistrationPending();
        }

        private ReactorLoop owner() {
            return owner.get();
        }

        private ReactorLoop detachOwner() {
            return owner.getAndSet(null);
        }

        private void bindClientIngressThread(Thread thread) {
            clientIngressThread.set(thread);
        }

        private Thread detachClientIngressThread() {
            return clientIngressThread.getAndSet(null);
        }

        private void bindClientWriterThread(Thread thread) {
            clientWriterThread.set(thread);
        }

        private Thread clientWriterThread() {
            return clientWriterThread.get();
        }

        private Thread detachClientWriterThread() {
            return clientWriterThread.getAndSet(null);
        }

        private boolean beginLifecycleCleanup() {
            return lifecycleCleanup.compareAndSet(false, true);
        }
    }

    private enum ReactorRequestType {
        REGISTER,
        ENABLE_WRITE,
        CANCEL
    }

    private record ReactorRequest(ReactorRequestType type, SocketChannel channel) {
    }

    private final class ReactorLoop {

        private final int index;
        private final Selector selector;
        private final Queue<ReactorRequest> pendingRequests = new ConcurrentLinkedQueue<>();
        private final Set<SocketChannel> pendingWriteInterest = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean wakeupPending = new AtomicBoolean(false);

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
            enqueueRequest(new ReactorRequest(ReactorRequestType.REGISTER, channel));
        }

        private void enqueueWriteInterest(SocketChannel channel) {
            enqueueRequest(new ReactorRequest(ReactorRequestType.ENABLE_WRITE, channel));
        }

        private void cancelKey(SocketChannel channel) {
            enqueueRequest(new ReactorRequest(ReactorRequestType.CANCEL, channel));
        }

        private void wakeup() {
            signalSelector();
        }

        private void enqueueRequest(ReactorRequest request) {
            pendingRequests.offer(request);
            signalSelector();
        }

        private void signalSelector() {
            if (wakeupPending.compareAndSet(false, true)) {
                selector.wakeup();
            }
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
                    boolean drainedRequests = drainPendingRequests();
                    if (!running.get()) {
                        return;
                    }

                    if (drainedRequests) {
                        selector.selectNow();
                    } else {
                        selector.select(100L);
                    }

                    drainPendingRequests();
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
                        } catch (CancelledKeyException _) {
                            // channel closed concurrently by VT handler path
                        } catch (RuntimeException _) {
                            closeKeyStream(key);
                        }
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        handleAsyncFailure("reactor/" + index, e);
                    }
                    return;
                }
            }
        }

        private boolean drainPendingRequests() {
            boolean drainedAny = false;
            ReactorRequest request = pendingRequests.poll();
            while (request != null) {
                drainedAny = true;
                processPendingRequest(request);
                request = pendingRequests.poll();
            }

            wakeupPending.set(false);
            if (!pendingRequests.isEmpty()) {
                signalSelector();
                return true;
            }
            return drainedAny;
        }

        private void processPendingRequest(ReactorRequest request) {
            SocketChannel channel = request.channel();
            switch (request.type()) {
                case REGISTER -> registerChannel(channel);
                case ENABLE_WRITE -> enableWriteInterest(channel);
                case CANCEL -> cancelSelection(channel);
            }
        }

        private void registerChannel(SocketChannel channel) {
            try {
                if (channel.isOpen()) {
                    NativeTcpStream stream = resolveStream(channel);
                    boolean enableWriteOnRegister = pendingWriteInterest.remove(channel)
                            || (stream != null && stream.hasPendingData());
                    int interestOps = enableWriteOnRegister
                            ? SelectionKey.OP_READ | SelectionKey.OP_WRITE
                            : SelectionKey.OP_READ;
                    channel.register(selector, interestOps);
                    if (stream != null) {
                        stream.markRegistrationReady();
                    }
                } else {
                    pendingWriteInterest.remove(channel);
                }
            } catch (IOException e) {
                pendingWriteInterest.remove(channel);
                NativeTcpStream stream = streamByChannel.get(channel);
                if (stream != null) {
                    stream.close();
                }
            }
        }

        private void enableWriteInterest(SocketChannel channel) {
            SelectionKey key = channel.keyFor(selector);
            if (key == null || !key.isValid()) {
                pendingWriteInterest.add(channel);
                return;
            }
            pendingWriteInterest.remove(channel);
            try {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE | SelectionKey.OP_READ);
            } catch (RuntimeException _) {
                pendingWriteInterest.add(channel);
            }
        }

        private void cancelSelection(SocketChannel channel) {
            pendingWriteInterest.remove(channel);
            SelectionKey key = channel.keyFor(selector);
            if (key != null) {
                key.cancel();
            }
        }
    }
}
