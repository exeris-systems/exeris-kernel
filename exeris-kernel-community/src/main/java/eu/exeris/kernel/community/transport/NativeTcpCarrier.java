/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
    private static final int MIN_LISTENER_BACKLOG = 64;
    private static final int MAX_LISTENER_BACKLOG = 1_024;
    // Advisory TCP reset code for an abortive teardown driven by a reactor dispatch fault
    // (see closeKeyStream). The peer observes a RST; the value carries no application semantics.
    private static final long DISPATCH_FAULT_RESET_CODE = 0L;
    // Fairness cap on TLS records drained per readable event (see readIngress / PERF-062 in
    // docs/subsystems/transport.md). Overridable for field tuning, mirroring
    // exeris.transport.queueBackpressureEnabled.
    private static final int MAX_TLS_RECORDS_PER_READ = readMaxTlsRecordsPerRead();
    // Optional SO_SNDBUF override for accepted sockets (bytes). 0 = leave OS default. A small send
    // buffer tightens egress backpressure so a slow/stalled peer parks the writer sooner; used by the
    // streaming backpressure TCK probe and available for field tuning. Mirrors the other
    // exeris.transport.* knobs.
    private static final int ACCEPTED_SEND_BUFFER_BYTES =
            Integer.getInteger("exeris.transport.acceptedSendBufferBytes", 0);

    private final TransportConfig config;
    private final MemoryAllocator allocator;
    private final KernelCryptoProvider cryptoProvider;
    private final CryptoProviderConfig cryptoConfig;
    private final NativeTcpSocketBackend backend;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicLong streamSeq = new AtomicLong(1);
    private final AtomicLong connectionSeq = new AtomicLong(1);
    private final AtomicLong activeConnections = new AtomicLong();
    private final AtomicLong activeStreams = new AtomicLong();
    private final AtomicLong totalAccepted = new AtomicLong();
    private final AtomicLong refusedConnections = new AtomicLong();
    private final AtomicLong acceptFaults = new AtomicLong();

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
            } else {
                // CLIENT mode: there is no acceptor/PAQS, but outbound channels still need a reactor
                // to drive non-blocking ingress and egress (TCK-064). Stand up the reactor loop(s)
                // here so connect() can register channels against them.
                startClientRuntime();
            }
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    /**
     * Graceful stop per the {@code TransportEngine.stop()} contract: stop accepting, drain in-flight
     * streams for a bounded period, then force-close whatever is left.
     *
     * <p>The drain machinery is {@link PaqsScheduler#close()} — it waits for the admission
     * controller's active-stream count to reach zero under a 60-second hard deadline. It was already
     * here, but it ran <em>last</em>, after {@code closeSelectorAndChannels()} had closed the sockets
     * and the reactors had exited. Handlers were therefore drained against dead file descriptors:
     * a request that had reached its handler completed, and its response went nowhere. The peer saw a
     * connection closed with no reply, and an idempotent client retried into a listener that was
     * already gone.
     *
     * <p>So the order below is the fix, not the mechanism. Ingress closes first, the reactors stay up
     * to carry responses out, the drain runs while they can still flush, and only then does anything
     * get torn down.
     */
    @Override
    public void stop() {
        // Claim the stop before clearing `running`, and in this order. The reactors' liveness
        // predicate is `running || draining`, so raising `draining` second would leave a window where
        // both read false and a reactor could exit before the drain has even begun — the very failure
        // this method exists to prevent, reintroduced at instruction scale. Entering through the
        // `draining` CAS also makes the guard atomic: a concurrent second stop() loses the race here
        // and returns, instead of both threads passing a separate check.
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        if (!running.compareAndSet(true, false)) {
            // Not running — nothing to drain, and `draining` must not stay raised or the reactors of a
            // subsequent start() would never be allowed to exit.
            draining.set(false);
            return;
        }

        try {
            // Phase 1 — close ingress. No new connections; the reactors keep serving admitted ones.
            closeQuietly(serverChannel);

            Thread acceptor = acceptorThread;
            if (acceptor != null) {
                try {
                    acceptor.join(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Phase 2 — drain in-flight streams while the write path is still alive.
            long drainStartNanos = System.nanoTime();
            PaqsScheduler localPaqs = paqs;
            int openAtStart = paqsActiveStreams();
            int busyAtStart = localPaqs == null ? 0 : localPaqs.drainCoordinator().busyStreams();
            if (localPaqs != null) {
                localPaqs.close();
            }
            // busyAtSeal(), not busyStreams(): close() always leaves the coordinator sealed, and a
            // sealed count reads as 0 by design, so asking after the fact reported "nothing was left
            // behind" even when the 60s deadline had just abandoned live streams. The coordinator
            // records what it discarded at the moment it sealed.
            int busyRemaining = localPaqs == null ? 0 : localPaqs.drainCoordinator().busyAtSeal();
            CommunityTransportDrainEvent.emit(
                    engineName(),
                    busyAtStart,
                    busyRemaining,
                    openAtStart,
                    System.nanoTime() - drainStartNanos);
        } finally {
            // Phase 3 — the drain is over by completion, by deadline, or by a throw above; either way
            // the loops come down. In a finally because `draining` is this method's own re-entry
            // guard: leaving it raised makes the engine permanently unstoppable. The reactors' liveness
            // predicate is `running || draining`, `running` is already false, and a second stop() loses
            // the entry CAS and returns — so the reactors would spin forever and every accepted fd
            // would leak, with no recovery short of restarting the JVM.
            draining.set(false);
            for (NativeTcpReactor reactor : reactors) {
                reactor.wakeup();
            }
            for (NativeTcpReactor reactor : reactors) {
                reactor.join(2_000L);
            }

            closeSelectorAndChannels();
        }
    }

    private int paqsActiveStreams() {
        PaqsScheduler localPaqs = paqs;
        return localPaqs == null ? 0 : localPaqs.admissionController().activeStreamCount();
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
            // Connect blocking (avoids OP_CONNECT handling), then flip the connected channel to
            // non-blocking UNCONDITIONALLY before reactor registration. This is the TCK-064 root
            // cause fix: a blocking plain client FD makes seamRead's EAGAIN->0 path unreachable, so
            // native recv() blocks and pins the VT carrier. Non-blocking recv yields cleanly to the
            // reactor instead. TLS-FD channels were already flipped here; plain channels now are too.
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(host, port));
            channel.configureBlocking(false);
            tlsEngine = createTlsEngineIfEnabled();
            bindTlsFdIfRequired(tlsEngine, channel);
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
                    () -> requestWriteInterest(connectedChannel),
                    () -> onStreamClosed(connectedChannel),
                    backend.socketHandles());
            if (tlsEngine instanceof eu.exeris.kernel.community.crypto.CommunityTlsEngine) {
                stream.markTlsBoundFromCarrier();
            }

            connection.bindSingleStream(stream);
            ChannelRuntimeRegistry.ChannelRuntimeState runtime = registerRuntime(stream, connectedChannel);
            registerClientChannel(runtime, connectedChannel);
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
        // Both refusal paths, not just the PAQS one. A caller reading totalRejected is asking "is
        // this server turning work away", and an accept-time refusal is the most total form of that.
        long rejected = refusedConnections.get();
        PaqsScheduler localPaqs = paqs;
        if (localPaqs != null) {
            rejected += localPaqs.loadShedder().shedCount();
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

    /**
     * Whether the reactor loops should keep selecting.
     *
     * <p>Distinct from {@link #isRunning()} because a graceful stop has two phases, and collapsing
     * them is what broke the drain: {@code running} goes false the instant {@link #stop()} decides to
     * stop <em>accepting</em>, while the reactors must keep flushing responses for streams already
     * admitted. A reactor that exits on {@code running} alone takes the write path down with it, so
     * the drain that follows has nothing left to drain through.
     *
     * <p>These are two independent reads, not one atomic state, so {@link #stop()} owes them an
     * ordering invariant: at least one of the two flags reads true from the moment a stop is claimed
     * until the drain has finished. {@code stop()} raises {@code draining} <em>before</em> clearing
     * {@code running} for exactly that reason — the reverse order leaves a window, however narrow, in
     * which both read false and a reactor exits ahead of the drain.
     */
    /* default */ boolean isReactorActive() {
        return running.get() || draining.get();
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

    private static int readMaxTlsRecordsPerRead() {
        String raw = System.getProperty("exeris.transport.maxTlsRecordsPerRead", "32");
        try {
            int parsed = Integer.parseInt(raw);
            // A non-positive cap would silently disable per-readable record draining (the drain loop
            // never iterates), starving every connection on this carrier — reject it like a malformed
            // value rather than honouring the foot-gun.
            if (parsed <= 0) {
                LOG.log(System.Logger.Level.WARNING,
                        "exeris.transport.maxTlsRecordsPerRead must be positive (was \"{0}\"); using default 32", raw);
                return 32;
            }
            return parsed;
        } catch (NumberFormatException _) {
            // A malformed tuning flag must not fail class init — fall back to the documented default.
            LOG.log(System.Logger.Level.WARNING,
                    "Invalid exeris.transport.maxTlsRecordsPerRead=\"{0}\"; using default 32", raw);
            return 32;
        }
    }

    private void initPaqs() {
        WatermarkManager watermarkManager = new WatermarkManager(allocator);
        ResourceArbiter arbiter = new ResourceArbiter(watermarkManager);
        AdmissionController admissionController =
                new AdmissionController(arbiter, config.maxActiveStreams());
        StreamLoadShedder shedder = new StreamLoadShedder(engineName());
        this.paqs = new PaqsScheduler(
                admissionController,
                shedder,
                streamHandler,
                stream -> StreamPriority.NORMAL,
                engineName());
    }

    /**
     * Builds and starts {@code reactorCount} reactor loops, replacing any prior set.
     * Shared by SERVER/DUAL bootstrap and CLIENT bootstrap so both roles drive the same
     * non-blocking selector model.
     */
    private void startReactors(int reactorCount) throws IOException {
        reactors.clear();
        for (int i = 0; i < reactorCount; i++) {
            reactors.add(new NativeTcpReactor(this, i, Selector.open()));
        }
        nextReactorIndex.set(0);
        for (NativeTcpReactor reactor : reactors) {
            reactor.start();
        }
    }

    private void startServerRuntime() {
        try {
            backend.validateServerSocketBootstrapOnce(allocator);

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.bind(new InetSocketAddress(config.bindAddress(), config.port()), listenerBacklog());

            startReactors(Math.max(1, config.reactorCount()));

            acceptorThread = Thread.ofPlatform()
                    .name("carrier/native-tcp/acceptor")
                    .start(this::runAcceptorLoop);
        } catch (IOException e) {
            throw TransportException.engineStartFailure(engineName(), config.port(), e);
        }
    }

    private void startClientRuntime() {
        try {
            // CLIENT/DUAL outbound paths multiplex a small number of channels; a single reactor
            // is sufficient and intentionally avoids spawning surplus reactor platform threads.
            // Surplus client reactors would oversubscribe constrained cores (2-vCPU CI) and starve
            // the VT carriers running stream handlers — the same class of stall TCK-064 fixes.
            startReactors(1);
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

    /**
     * Records a connection refused at the {@code maxConnections} ceiling.
     *
     * <p>Observability only — the refusal itself is unchanged. Whether an accept-time cap is the
     * right mechanism, and whether its default is right, are separate decisions; this makes the
     * existing behaviour visible so they can be taken on evidence.
     */
    private void recordRefusal() {
        long total = refusedConnections.incrementAndGet();
        CommunityConnectionRefusedEvent.emit(
                config.bindAddress(), config.port(), activeConnections.get(),
                config.maxConnections(), total);
    }

    /**
     * Records a connection that was accepted and then failed during setup.
     *
     * <p>Recovery is unchanged — continuing the accept loop after a per-connection failure is
     * correct, and making this fatal would trade a silent drop for an availability regression. Only
     * the silence is removed.
     */
    private void recordAcceptFault(RuntimeException exception) {
        long total = acceptFaults.incrementAndGet();
        CommunityAcceptFaultEvent.emit(
                config.bindAddress(), config.port(), exception.getClass().getName(), total);
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

    /**
     * The configured idle timeout in milliseconds, {@code 0} when reclamation is disabled.
     *
     * <p>Read once per reactor at construction to build its {@link NativeTcpIdleReaper}. It comes
     * straight off {@code TransportConfig}, which is where {@code transport.idleTimeoutMillis}
     * and {@code http.idleTimeoutMillis} both land.
     *
     * @return the timeout carried on this carrier's transport configuration
     */
    /* default */ long idleTimeoutMillis() {
        return config.idleTimeoutMillis();
    }

    /* default */ void closeKeyStream(SelectionKey key) {
        // Two callers, both on the reactor thread, and both wanting the same teardown. First, the
        // select-loop catch(RuntimeException) (NativeTcpReactor) after a per-key dispatch
        // (read-ingress or flush) faulted — most often an unwrap-on-closed TlsDecryptException once
        // the stream's TLS engine is already closed. Second, NativeTcpIdleReaper, when a connection
        // has moved no bytes for transport.idleTimeoutMillis. The first connection is unrecoverable
        // and the second is unattended; neither can be waited on, so both tear down ABORTIVELY.
        // Point 2 below is why the idle path in particular must not take a graceful close: a peer
        // quiet enough to be reclaimed is a peer that may never drain the queue that close waits on.
        //  1. cancel the key synchronously — removes it from this reactor's selector so the
        //     level-triggered dead channel cannot re-fire (read OR write) and busy-spin the reactor
        //     while teardown completes. Direct cancel honours the single-consumer key protocol
        //     (this runs on the reactor thread); a graceful interest-narrow does not suffice because
        //     a closed-engine stream's queued egress is undrainable and would keep OP_WRITE armed.
        //  2. reset() rather than close(): reset abandons the undrainable queue and sets
        //     resetRequested — the designed escape hatch (issue #180) that lets finishCloseIfDrained
        //     bypass its drain gate, finalize, and deregister the channel, including under
        //     slot-owner deferral via the deferred-abort wake. A graceful close() would defer
        //     forever here.
        if (key.isValid()) {
            key.cancel();
        }
        if (!(key.channel() instanceof SocketChannel channel)) {
            return;
        }
        NativeTcpStream stream = resolveStream(channel);
        if (stream == null) {
            return;
        }
        stream.markRemoteClosed();
        stream.reset(DISPATCH_FAULT_RESET_CODE);
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
                    recordRefusal();
                    closeQuietly(currentChannel);
                    acceptedChannel = serverChannel.accept();
                    continue;
                }
                configureAcceptedChannel(currentChannel);

                InetSocketAddress remote = resolveRemoteAddress(currentChannel);
                connection = new NativeTcpConnection(
                        connectionSeq.getAndIncrement(),
                        remote.getAddress().getHostAddress(),
                        remote.getPort());

                NativeTcpStream stream = buildAcceptedStream(currentChannel, connection);
                connection.bindSingleStream(stream);
                connectionManagedByStreamLifecycle = true;
                registerConnection(connection, stream, currentChannel);
            } catch (RuntimeException exception) {
                recordAcceptFault(exception);
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

    private static void configureAcceptedChannel(SocketChannel channel) throws IOException {
        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
        if (ACCEPTED_SEND_BUFFER_BYTES > 0) {
            channel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, ACCEPTED_SEND_BUFFER_BYTES);
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
     * Registers the stream with a reactor and arms the one-shot established hook, then returns
     * immediately — the acceptor thread never blocks on the TLS handshake.
     *
     * <p>The hook ({@link #completeEstablished}) fires on the reactor thread once the connection
     * is established: plaintext as soon as the key is armed, TLS once the reactor-driven handshake
     * reaches ACTIVE. This deserialises handshakes that previously queued behind one another on the
     * single acceptor thread, while preserving the {@code onConnectionEstablished} → {@code schedule}
     * ordering. Slot accounting stays lifecycle-based (released on stream close), so a failed/aborted
     * handshake that closes the stream releases the slot without acceptor involvement.
     */
    private void registerConnection(NativeTcpConnection connection,
                                    NativeTcpStream stream,
                                    SocketChannel currentChannel) {
        ChannelRuntimeRegistry.ChannelRuntimeState runtime = registerRuntime(stream, currentChannel);
        NativeTcpReactor owner = selectReactor();
        runtime.markRegistrationPending();
        runtime.bindOwner(owner);

        // Arm the established hook BEFORE enqueueing registration so the reactor can fire it the
        // instant a plaintext key is armed (no lost-wakeup window).
        stream.onEstablished(() -> completeEstablished(connection, stream));

        activeStreams.incrementAndGet();
        totalAccepted.incrementAndGet();

        owner.enqueueRegistration(currentChannel);
    }

    /**
     * Fired once on the reactor thread when a connection is established: notifies the connection
     * handler (contractually non-blocking) and schedules the stream in PAQS, in that order. Any
     * failure closes the connection (and releases its slot via the stream close path).
     */
    private void completeEstablished(NativeTcpConnection connection, NativeTcpStream stream) {
        try {
            connectionHandler.onConnectionEstablished(connection);
        } catch (RuntimeException handlerFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "ConnectionHandler.onConnectionEstablished failed; closing connection", handlerFailure);
            connection.close();
            return;
        }
        try {
            paqs.schedule(stream);
        } catch (RuntimeException scheduleFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "PAQS schedule rejected stream on establish; closing connection", scheduleFailure);
            connection.close();
        }
    }

    /**
     * Registers an outbound (client) channel with a reactor exactly like an accepted server
     * channel: bind the owner, mark registration pending, and enqueue the REGISTER request so the
     * reactor arms OP_READ (and OP_WRITE when there is already-queued data) on its own thread. This
     * is the TCK-064 fix — client ingress is now reactor-driven against a non-blocking FD instead of
     * a blocking native {@code recv()} on a VT (which pinned the carrier). Egress is unified onto the
     * same key via {@link #requestWriteInterest}: the stream's write callback arms OP_WRITE and the
     * reactor thread runs {@link #flushStream}. No separate ingress/writer VT survives for either
     * plain or TLS-FD client channels.
     */
    private void registerClientChannel(ChannelRuntimeRegistry.ChannelRuntimeState runtime,
                                       SocketChannel channel) {
        NativeTcpReactor owner = selectReactor();
        runtime.markRegistrationPending();
        runtime.bindOwner(owner);
        owner.enqueueRegistration(channel);
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

    /* default */ void readIngress(NativeTcpStream stream) {
        if (stream.usesFdOwnerTls()) {
            // PERF-062: drain all buffered TLS records in one readable event (loop until null =
            // WANT_READ / backpressure / close), bounded by MAX_TLS_RECORDS_PER_READ for fairness.
            // See docs/subsystems/transport.md. The level-triggered selector re-reports any remainder.
            for (int drained = 0; drained < MAX_TLS_RECORDS_PER_READ; drained++) {
                LoanedBuffer offered = stream.readTlsIngressFromFd();
                if (offered == null) {
                    return;
                }
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

    /* default */ void flushStream(NativeTcpStream stream, SelectionKey key) {
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

        streamByChannel.remove(channel, runtime.stream());
        channelOwner.remove(channel);

        if (runtime.beginLifecycleCleanup()) {
            activeStreams.decrementAndGet();
            activeConnections.decrementAndGet();
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

}
