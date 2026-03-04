/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.transport.TransportScopes;
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
 * {@link Thread#ofVirtual()}. The thread is <em>unstructured</em> in isolation (it is
 * not part of a parent {@link java.util.concurrent.StructuredTaskScope}) because streams
 * are fire-and-forget from the carrier's perspective. The {@link AdmissionController}'s
 * {@code activeStreamCount} acts as the bounded resource governor that prevents unbounded VT
 * proliferation.
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
public final class PaqsScheduler {

    private static final System.Logger LOG = System.getLogger(PaqsScheduler.class.getName());

    private final AdmissionController admissionController;
    private final StreamLoadShedder loadShedder;
    private final StreamHandler handler;
    private final Function<TransportStream, StreamPriority> priorityExtractor;
    private final String engineName;

    /**
     * Creates a fully configured PAQS Scheduler.
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
        Objects.requireNonNull(admissionController, "admissionController must not be null");
        Objects.requireNonNull(loadShedder, "loadShedder must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(priorityExtractor, "priorityExtractor must not be null");
        if (engineName == null || engineName.isBlank()) {
            throw new IllegalArgumentException("engineName must not be null or blank");
        }
        this.admissionController = admissionController;
        this.loadShedder = loadShedder;
        this.handler = handler;
        this.priorityExtractor = priorityExtractor;
        this.engineName = engineName;
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

        StreamPriority priority = priorityExtractor.apply(stream);
        if (priority == null) {
            priority = StreamPriority.NORMAL;
        }

        AdmissionController.Decision decision = admissionController.admit(priority);

        if (!decision.isAdmit()) {
            loadShedder.shed(stream, priority, decision, admissionController.activeStreamCount());
            return;
        }

        admissionController.onStreamAdmitted();
        spawnStreamThread(stream, priority);
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

        Thread.ofVirtual()
                .name(threadName)
                .start(() -> runStream(stream, priority, streamId, priorityName));
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
    private void runStream(TransportStream stream, StreamPriority priority, long streamId, String priorityName) {
        long startNs = System.nanoTime();
        String outcome = StreamLifecycleEvent.OUTCOME_COMPLETE;

        try {
            ScopedValue.where(TransportScopes.STREAM_PRIORITY, priority)
                    .where(TransportScopes.STREAM_ID, streamId)
                    .where(TransportScopes.ENGINE_NAME, engineName)
                    .run(() -> handler.handle(stream));
        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — VT stream boundary isolation
            outcome = StreamLifecycleEvent.OUTCOME_ERROR;
            if (LOG.isLoggable(System.Logger.Level.WARNING)) {
                LOG.log(System.Logger.Level.WARNING, "Stream handler failed: streamId={0}", streamId);
            }
            stream.close();
        } finally {
            admissionController.onStreamCompleted();
            StreamLifecycleEvent.emit(streamId, priorityName, outcome, System.nanoTime() - startNs);
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
}
