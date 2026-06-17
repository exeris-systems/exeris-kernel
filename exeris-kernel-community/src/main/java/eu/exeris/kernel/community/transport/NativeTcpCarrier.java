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
import java.util.concurrent.atomic.AtomicLongArray;

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
    // Fairness cap on TLS records drained per readable event (see readIngress / PERF-062 in
    // docs/subsystems/transport.md). Overridable for field tuning, mirroring
    // exeris.transport.queueBackpressureEnabled.
    private static final int MAX_TLS_RECORDS_PER_READ =
            Integer.parseInt(System.getProperty("exeris.transport.maxTlsRecordsPerRead", "32"));

    // PERF-RESEARCH (research/0.9.0-tls-records-per-event): confirm-experiment (1) for the
    // TLS-vs-plaintext CPU gap. Records the distribution of TLS records drained per readable event
    // so we can tell whether the dominant cost is per-record segment churn (mean >= 2) or reactor
    // iteration frequency (mean ~ 1.0 / many empty events). Default ON on this branch; not for merge.
    private static final boolean TLS_DRAIN_HISTOGRAM =
            Boolean.parseBoolean(System.getProperty("exeris.transport.tls.ingressDrainHistogram", "true"));
    // Power-of-two so the emit gate is a cheap mask, not a modulo on the hot path.
    private static final long DRAIN_EMIT_INTERVAL = 4_096L;
    // A readable event is "multi-record" (the coalescing-pays-off signal) at >= this many records.
    private static final int MULTI_RECORD_THRESHOLD = 2;

    private final TransportConfig config;
    private final MemoryAllocator allocator;
    private final KernelCryptoProvider cryptoProvider;
    private final CryptoProviderConfig cryptoConfig;
    private final NativeTcpSocketBackend backend;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // PERF-RESEARCH: TLS records-drained-per-readable-event distribution (see TLS_DRAIN_HISTOGRAM).
    private final AtomicLongArray tlsDrainHistogram = new AtomicLongArray(MAX_TLS_RECORDS_PER_READ + 1);
    private final AtomicLong tlsReadableEvents = new AtomicLong();
    private final AtomicLong tlsTotalRecords = new AtomicLong();
    // PERF-RESEARCH: closeKeyStream invocation/time accounting (see closeKeyStream).
    private final AtomicLong closeKeyInvocations = new AtomicLong();
    private final AtomicLong closeKeyActualCloses = new AtomicLong();
    private final AtomicLong closeKeyNanos = new AtomicLong();

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
            emitTlsDrainSnapshot(true); // PERF-RESEARCH: final cumulative distribution before teardown
            emitCloseKeyStreamSnapshot(true);
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
        // PERF-RESEARCH: time the whole entry so invocations x mean resolves the 15%-CPU claim.
        long t0 = TLS_DRAIN_HISTOGRAM ? System.nanoTime() : 0L;
        boolean actualClose = false;
        try {
            if (!(key.channel() instanceof SocketChannel channel)) {
                return;
            }
            NativeTcpStream stream = resolveStream(channel);
            if (stream == null) {
                return;
            }
            actualClose = true;
            stream.markRemoteClosed();
            stream.close();
        } finally {
            if (TLS_DRAIN_HISTOGRAM) {
                closeKeyNanos.addAndGet(System.nanoTime() - t0);
                if (actualClose) {
                    closeKeyActualCloses.incrementAndGet();
                }
                closeKeyInvocations.incrementAndGet();
            }
        }
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
                registerConnection(connection, stream, currentChannel);
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
            int drained = 0;
            while (drained < MAX_TLS_RECORDS_PER_READ) {
                LoanedBuffer offered = stream.readTlsIngressFromFd();
                if (offered == null) {
                    break;
                }
                stream.offerIngress(offered);
                drained++;
            }
            recordTlsDrain(drained);
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

    // PERF-RESEARCH: record one readable-event's drained-record count (0..MAX). Single atomic
    // increment per readable event — no per-record cost added. Emits a cumulative JFR snapshot every
    // DRAIN_EMIT_INTERVAL events so the last event in the recording holds the full distribution.
    private void recordTlsDrain(int drained) {
        if (!TLS_DRAIN_HISTOGRAM) {
            return;
        }
        tlsDrainHistogram.incrementAndGet(drained);
        tlsTotalRecords.addAndGet(drained);
        long events = tlsReadableEvents.incrementAndGet();
        if ((events & (DRAIN_EMIT_INTERVAL - 1)) == 0) {
            emitTlsDrainSnapshot(false);
            emitCloseKeyStreamSnapshot(false);
        }
    }

    private void emitCloseKeyStreamSnapshot(boolean finalSnapshot) {
        long invocations = closeKeyInvocations.get();
        if (invocations == 0) {
            return;
        }
        long nanos = closeKeyNanos.get();
        double meanMicros = nanos / 1_000.0 / invocations;
        CommunityCloseKeyStreamEvent.emit(invocations, closeKeyActualCloses.get(), nanos,
                meanMicros, finalSnapshot);
    }

    private void emitTlsDrainSnapshot(boolean finalSnapshot) {
        long events = tlsReadableEvents.get();
        if (events == 0) {
            return;
        }
        long total = tlsTotalRecords.get();
        long multi = 0;
        StringBuilder hist = new StringBuilder(96);
        for (int bucket = 0; bucket <= MAX_TLS_RECORDS_PER_READ; bucket++) {
            long count = tlsDrainHistogram.get(bucket);
            if (count == 0) {
                continue;
            }
            if (bucket >= MULTI_RECORD_THRESHOLD) {
                multi += count;
            }
            if (!hist.isEmpty()) {
                hist.append(',');
            }
            hist.append(bucket).append(':').append(count);
        }
        double mean = (double) total / events;
        double pctMulti = 100.0 * multi / events;
        CommunityTlsIngressDrainEvent.emit(events, total, mean, pctMulti,
                tlsDrainHistogram.get(0), tlsDrainHistogram.get(MAX_TLS_RECORDS_PER_READ),
                hist.toString(), finalSnapshot);
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
