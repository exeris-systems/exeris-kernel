/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.core.transport.jfr.PaqsHandlerFailureEvent;
import eu.exeris.kernel.core.transport.jfr.StreamAcceptedEvent;
import eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.Objects;
import java.util.function.Function;

/**
 * Core: Priority-Aware Queue Scheduler (PAQS) — the intelligent gatekeeper at the
 * transport edge that injects business context into the network layer.
 *
 * <h2>Architectural Role (The Brain)</h2>
 * <p>The PAQS is the Core's answer to the question: <em>"Should this stream be served?"</em>
 * It is the single point where:
 * <ol>
 *   <li><b>Admission</b> — the {@link AdmissionController} consults the {@link
 *       eu.exeris.kernel.core.memory.ResourceArbiter} and the stream's {@link StreamPriority}
 *       to make an O(1) admit/shed decision.</li>
 *   <li><b>Load Shedding</b> — rejected streams are closed by the {@link StreamLoadShedder}
 *       and a {@link eu.exeris.kernel.core.transport.jfr.StreamShedEvent} is emitted.</li>
 *   <li><b>Stream Orchestration</b> — admitted streams receive one Virtual Thread each,
 *       with {@link ScopedValue} context bindings set before the handler is invoked.</li>
 * </ol>
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <p>The PAQS operates exclusively on the SPI: {@link TransportStream}, {@link StreamHandler},
 * and {@link StreamPriority}. It is oblivious to whether data flows over TCP (Community)
 * or QUIC/io_uring (Enterprise). The {@code priorityExtractor} function provided at
 * construction time is the only point of protocol-specific context injection.
 *
 * <h2>Virtual Thread-per-Stream Model</h2>
 * <p>For every admitted stream, the PAQS spawns exactly one Virtual Thread using
 * {@link Thread#ofVirtual()}. The {@link AdmissionController}'s {@code activeStreamCount}
 * is the bounded resource governor that prevents unbounded VT proliferation.
 *
 * <h2>Concurrency Model — Deliberate Exception to StructuredTaskScope</h2>
 * <p>{@code StructuredTaskScope.fork()} requires that all {@code fork()} calls originate
 * from the thread that opened the scope (JDK 26: {@code WrongThreadException} otherwise).
 * PAQS {@link #schedule(TransportStream)} is called concurrently by multiple carrier threads
 * (NIO selectors, io_uring rings) — none of which own a shared scope. A long-lived STS is
 * therefore architecturally incompatible with the multi-carrier ingress model.
 * {@code Thread.ofVirtual().start()} is the sole deliberate exception to the STS mandate.
 * All concurrent operations <em>within</em> {@code runStream()} MUST use
 * {@code StructuredTaskScope}.
 *
 * <h2>ScopedValue Bindings (JEP 506)</h2>
 * <p>Before invoking the {@link StreamHandler}, the PAQS binds:
 * <ul>
 *   <li>{@link TransportScopes#STREAM_PRIORITY} — the stream's business priority</li>
 *   <li>{@link TransportScopes#STREAM_ID} — the stream's unique identifier</li>
 *   <li>{@link TransportScopes#ENGINE_NAME} — the transport engine name for diagnostics</li>
 * </ul>
 *
 * <h2>JFR-First Telemetry</h2>
 * <p>Every admission emits {@link StreamAcceptedEvent}. Every completion emits
 * {@link StreamLifecycleEvent}. Every rejection emits
 * {@link eu.exeris.kernel.core.transport.jfr.StreamShedEvent}.
 *
 * <h2>Thread Safety</h2>
 * <p>The PAQS is thread-safe. {@link #schedule(TransportStream)} may be called concurrently
 * by multiple carrier threads (e.g., multiple io_uring rings or NIO reactor threads).
 * All shared state mutations go through the {@link AdmissionController}'s VarHandle CAS path.
 *
 * @see AdmissionController
 * @see StreamLoadShedder
 * @see TransportScopes
 * @since 0.5.0
 */
public final class PaqsScheduler implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PaqsScheduler.class.getName());
    private static final long SPIN_THRESHOLD = 10_000L;
    private static final String PHASE_HANDLER = "HANDLER";

    private final AdmissionController admissionController;
    private final StreamLoadShedder loadShedder;
    private final StreamHandler handler;
    private final Function<TransportStream, StreamPriority> priorityExtractor;
    private final String engineName;
    private final StreamExecutionBackend executionBackend;

    /**
     * Creates a fully configured PAQS Scheduler using the default execution backend
     * (one Virtual Thread per admitted stream).
     *
     * @param admissionController the admission gate; must not be {@code null}
     * @param loadShedder         the stream shedder; must not be {@code null}
     * @param handler             the business-logic handler for admitted streams; must not be {@code null}
     * @param priorityExtractor   a protocol-specific function mapping an incoming stream to its
     *                            {@link StreamPriority}; must not be {@code null}. This is the
     *                            single point of protocol-context injection — it may read headers
     *                            (e.g., HTTP/3 urgency, custom priority header) to classify the stream.
     * @param engineName          transport engine name for JFR events and thread naming; must not be blank
     */
    public PaqsScheduler(AdmissionController admissionController,
                         StreamLoadShedder loadShedder,
                         StreamHandler handler,
                         Function<TransportStream, StreamPriority> priorityExtractor,
                         String engineName) {
        this(admissionController, loadShedder, handler, priorityExtractor, engineName,
                defaultExecutionBackend());
    }

    /**
     * Creates a fully configured PAQS Scheduler with a custom {@link StreamExecutionBackend}.
     *
     * @param admissionController the admission gate; must not be {@code null}
     * @param loadShedder         the stream shedder; must not be {@code null}
     * @param handler             the business-logic handler for admitted streams; must not be {@code null}
     * @param priorityExtractor   a protocol-specific function mapping an incoming stream to its
     *                            {@link StreamPriority}; must not be {@code null}. This is the
     *                            single point of protocol-context injection — it may read headers
     *                            (e.g., HTTP/3 urgency, custom priority header) to classify the stream.
     * @param engineName          transport engine name for JFR events and thread naming; must not be blank
     * @param executionBackend    the stream execution backend; must not be {@code null}. The default
     *                            ({@link #defaultExecutionBackend()}) spawns one Virtual Thread per stream.
     */
    public PaqsScheduler(AdmissionController admissionController,
                         StreamLoadShedder loadShedder,
                         StreamHandler handler,
                         Function<TransportStream, StreamPriority> priorityExtractor,
                         String engineName,
                         StreamExecutionBackend executionBackend) {
        Objects.requireNonNull(admissionController, "admissionController must not be null");
        Objects.requireNonNull(loadShedder, "loadShedder must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(priorityExtractor, "priorityExtractor must not be null");
        if (engineName == null || engineName.isBlank()) {
            throw new IllegalArgumentException("engineName must not be null or blank");
        }
        Objects.requireNonNull(executionBackend, "executionBackend must not be null");
        this.admissionController = admissionController;
        this.loadShedder = loadShedder;
        this.handler = handler;
        this.priorityExtractor = priorityExtractor;
        this.engineName = engineName;
        this.executionBackend = executionBackend;
    }

    // =========================================================================
    // Core API
    // =========================================================================

    /**
     * Schedules an incoming {@link TransportStream} for processing.
     *
     * <p>This is the <b>hot path</b>. Called by the transport carrier for every new stream.
     * The method is O(1) for the caller — all heavy work (stream handling) is delegated
     * to the spawned Virtual Thread.
     *
     * <h2>Decision Flow</h2>
     * <ol>
     *   <li>Extract the stream's priority via the {@code priorityExtractor}.</li>
     *   <li>Ask the {@link AdmissionController} — O(1).</li>
     *   <li>If admitted: increment active count, spawn VT, bind ScopedValues, run handler.</li>
     *   <li>If shed: close the stream, emit {@link eu.exeris.kernel.core.transport.jfr.StreamShedEvent}.</li>
     * </ol>
     *
     * @param stream the incoming stream from the transport driver; must not be {@code null}
     */
    public void schedule(TransportStream stream) {
        Objects.requireNonNull(stream, "stream must not be null");

        StreamPriority priority;
        try {
            priority = priorityExtractor.apply(stream);
            if (priority == null) {
                priority = StreamPriority.NORMAL;
            }
        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — carrier thread boundary isolation
            priority = StreamPriority.NORMAL;
        }

        AdmissionController.Decision decision = admissionController.admit(priority);

        if (!decision.isAdmit()) {
            loadShedder.shed(stream, priority, decision, admissionController.activeStreamCount());
            return;
        }

        try (SpawnGuard guard = new SpawnGuard(stream, admissionController)) {
            spawnStreamThread(stream, priority);
            guard.markSpawned();
        }
    }

    /**
     * Returns the {@link AdmissionController} for diagnostic inspection.
     *
     * @return the admission controller; never {@code null}
     */
    public AdmissionController admissionController() {
        return admissionController;
    }

    /**
     * Returns the {@link StreamLoadShedder} for diagnostic inspection (e.g., shed count).
     *
     * @return the load shedder; never {@code null}
     */
    public StreamLoadShedder loadShedder() {
        return loadShedder;
    }

    /**
     * Waits until the {@link AdmissionController}'s active stream count reaches zero,
     * applying a spin-then-yield backoff strategy to avoid burning CPU, and enforcing
     * a hard timeout to keep shutdown operationally safe even if a handler misbehaves.
     */
    @Override
    public void close() {
        final long deadlineNanos = System.nanoTime() + 60_000_000_000L;
        long spins = 0L;
        while (admissionController.activeStreamCount() > 0) {
            if (System.nanoTime() >= deadlineNanos) {
                int remaining = admissionController.activeStreamCount();
                if (remaining > 0) {
                    LOG.log(System.Logger.Level.WARNING,
                            "PaqsScheduler.close() timed out waiting for {0} active streams"
                                    + " to complete; proceeding with shutdown",
                            remaining);
                }
                break;
            }
            if (spins < SPIN_THRESHOLD) {
                Thread.onSpinWait();
                spins++;
            } else {
                Thread.yield();
            }
        }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Spawns a Virtual Thread for the admitted stream.
     *
     * <p>Each VT is named for observability (visible in thread dumps and JFR).
     * The {@link ScopedValue} bindings are set before the handler is invoked.
     * The {@code finally} block guarantees the active count is decremented
     * and a lifecycle JFR event is emitted regardless of how the handler exits.
     *
     * @param stream   the admitted stream
     * @param priority the resolved priority
     */
    private void spawnStreamThread(TransportStream stream, StreamPriority priority) {
        long streamId = stream.streamId();
        String threadName = buildThreadName(priority, streamId);
        String priorityName = priority.name();

        StreamAcceptedEvent.emit(streamId, priorityName, engineName, threadName);

        // ARCHITECTURE NOTE: This is the SOLE deliberate exception to the StructuredTaskScope mandate.
        // PAQS bridges a continuous, unbounded stream of events from concurrent carrier threads
        // (NIO selectors, io_uring rings). JDK 26 STS.fork() enforces WrongThreadException for any
        // caller that did not open the scope — making a shared long-lived STS incompatible with the
        // multi-carrier ingress model. The default StreamExecutionBackend spawns one Virtual Thread
        // per stream, acting as the Root of the Request Tree. All subsequent concurrent operations
        // within runStream() MUST use StructuredTaskScope.
        executionBackend.start(threadName, () -> runStream(stream, priority, streamId, priorityName));
    }

    /**
     * Executes the stream handler within the spawned Virtual Thread.
     *
     * <p>Binds the {@link TransportScopes} ScopedValues and guarantees cleanup in
     * the {@code finally} block. Exception handling is intentionally minimal:
     * a system logger warning is issued (no heap allocation for logs with {@code isLoggable}
     * guard) and the stream is force-closed.
     *
     * @param stream       the stream to handle
     * @param priority     the stream's priority
     * @param streamId     the stream ID (pre-captured to avoid re-reading on VT exit)
     * @param priorityName the priority name string (pre-captured to avoid enum.name() on exit path)
     */
    @SuppressWarnings("java:S1181") // Mandatory for L0 VT Boundary Resource Safety
    private void runStream(TransportStream stream, StreamPriority priority, long streamId, String priorityName) {
        long startNs = System.nanoTime();
        String outcome = StreamLifecycleEvent.OUTCOME_COMPLETE;

        try {
            ScopedValue.where(TransportScopes.STREAM_PRIORITY, priority)
                    .where(TransportScopes.STREAM_ID, streamId)
                    .where(TransportScopes.ENGINE_NAME, engineName)
                    .run(() -> handler.handle(stream));
        } catch (Error error) { //NOPMD AvoidCatchingGenericException — outcome must be set before rethrow
            outcome = StreamLifecycleEvent.OUTCOME_ERROR;
            PaqsHandlerFailureEvent.emit(streamId, engineName, error.getClass().getName(), PHASE_HANDLER);
            closeStreamBestEffort(stream, null, false);
            throw error;
        } catch (Throwable t) { //NOPMD AvoidCatchingGenericException,AvoidCatchingThrowable
            // VT stream boundary isolation
            outcome = StreamLifecycleEvent.OUTCOME_ERROR;
            PaqsHandlerFailureEvent.emit(streamId, engineName, t.getClass().getName(), PHASE_HANDLER);
            closeStreamBestEffort(stream, t, true);
            if (LOG.isLoggable(System.Logger.Level.WARNING)) {
                LOG.log(System.Logger.Level.WARNING, "Stream handler failed internally (VT boundary isolation)");
            }
        } finally {
            admissionController.onStreamCompleted();
            StreamLifecycleEvent.emit(streamId, priorityName, outcome, System.nanoTime() - startNs);
        }
    }

    @SuppressWarnings("java:S1181")
    private static void closeStreamBestEffort(TransportStream stream,
                                              Throwable primary,
                                              boolean attachSuppressed) {
        try {
            stream.close();
        } catch (Throwable closeError) { //NOPMD AvoidCatchingThrowable — best-effort close on failure path
            if (attachSuppressed && primary != null) {
                primary.addSuppressed(closeError);
            }
        }
    }

    /**
     * Builds a deterministic, human-readable Virtual Thread name for observability.
     *
     * <p>Format: {@code paqs/<engineName>/<priority>/<streamId>}
     * Example:  {@code paqs/CommunityTcpEngine/CRITICAL/42}
     *
     * <p>No format strings — uses {@link String#valueOf} and {@code +} concatenation,
     * which C2 JIT folds into a single {@code StringBuilder} invocation.
     *
     * @param priority stream priority
     * @param streamId stream ID
     * @return thread name
     */
    private String buildThreadName(StreamPriority priority, long streamId) {
        return "paqs/" + engineName + "/" + priority.name() + "/" + streamId;
    }

    /**
     * Returns the default {@link StreamExecutionBackend}: one Virtual Thread per stream via
     * {@link Thread#ofVirtual()}, preserving the VT-per-stream guarantee and the exact prior
     * spawn behaviour (refactor-neutral default).
     *
     * @return the default execution backend; never {@code null}
     */
    private static StreamExecutionBackend defaultExecutionBackend() {
        return (threadName, task) -> Thread.ofVirtual()
                .name(threadName)
                .start(task);
    }

    /**
     * Admission rollback guard for the VT spawn critical section.
     *
     * <p>Closed automatically by try-with-resources. On failure path ({@link #markSpawned()}
     * not called), closes the stream and decrements the admission counter. On success path,
     * both operations are skipped — the spawned Virtual Thread owns the stream lifecycle.
     */
    private static final class SpawnGuard implements AutoCloseable {

        private final TransportStream stream;
        private final AdmissionController admissionController;
        private boolean spawned;

        /* default */ SpawnGuard(TransportStream stream, AdmissionController admissionController) {
            this.stream = stream;
            this.admissionController = admissionController;
        }

        /* default */ void markSpawned() {
            spawned = true;
        }

        @Override
        public void close() {
            if (!spawned) {
                try {
                    stream.close();
                } finally {
                    admissionController.onStreamCompleted();
                }
            }
        }
    }
}
