/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.transport.jfr.StreamShedEvent;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * Core: Transport-edge load shedder — closes incoming streams that have been rejected
 * by the {@link AdmissionController} and emits the corresponding JFR signal.
 *
 * <h2>Architectural Role (The Brain)</h2>
 * <p>This class is the "bouncer" at the transport gate. When {@link AdmissionController#admit}
 * returns a non-ADMIT decision, the scheduler delegates to this class, which:
 * <ol>
 *   <li>Closes the stream (releases all native resources via the SPI contract).</li>
 *   <li>Emits a {@link StreamShedEvent} for JFR observability.</li>
 *   <li>Increments the shed counter for diagnostics ({@link #shedCount()}).</li>
 * </ol>
 *
 * <h2>Protocol Blindness</h2>
 * <p>This class operates exclusively on the {@link TransportStream} SPI interface.
 * It does not know whether the underlying stream is TCP, QUIC, or io_uring-backed.
 * The stream's {@link TransportStream#close()} call propagates the rejection to the
 * wire layer (for QUIC: STOP_SENDING frame; for TCP: FIN/RST).
 *
 * <h2>Zero-Allocation Hot Path</h2>
 * <p>The shed counter is a raw {@code long} field updated via {@link VarHandle}
 * {@code getAndAdd}. No {@code AtomicLong} object header — the VarHandle is a JVM constant.
 *
 * @see AdmissionController
 * @see PaqsScheduler
 * @since 0.5
 */
public final class StreamLoadShedder {

    private static final VarHandle SHED_COUNT;

    static {
        try {
            SHED_COUNT = MethodHandles.lookup()
                    .findVarHandle(StreamLoadShedder.class, "shedCountField", long.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /* default */ volatile long shedCountField;

    private final String engineName;

    /**
     * Creates a {@code StreamLoadShedder} associated with the given transport engine.
     *
     * @param engineName the engine name for JFR event fields; must not be {@code null} or blank
     */
    public StreamLoadShedder(String engineName) {
        if (engineName == null || engineName.isBlank()) {
            throw new IllegalArgumentException("engineName must not be null or blank");
        }
        this.engineName = engineName;
    }

    // =========================================================================
    // Core API
    // =========================================================================

    /**
     * Sheds an incoming stream that was rejected by the admission controller.
     *
     * <p>Closes the stream (wire-layer rejection) and emits {@link StreamShedEvent}.
     * This call is O(1) — the only work done is one {@code close()} on the stream SPI
     * plus a conditional JFR event emission. The {@code close()} call is idempotent by
     * the SPI contract, so double-calling is safe.
     *
     * @param stream            the incoming stream to reject; must not be {@code null}
     * @param priority          the stream's priority for JFR telemetry
     * @param decision          the rejection reason
     * @param activeStreamCount current active stream count at shed time (for JFR)
     */
    public void shed(TransportStream stream,
                     StreamPriority priority,
                     AdmissionController.Decision decision,
                     int activeStreamCount) {
        Objects.requireNonNull(stream, "stream must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        StreamPriority effectivePriority = (priority == null) ? StreamPriority.NORMAL : priority;

        SHED_COUNT.getAndAdd(this, 1L);

        long capturedStreamId = stream.streamId();
        try {
            stream.close();
        } catch (RuntimeException _) { //NOPMD AvoidCatchingGenericException
            // best-effort close on carrier-thread hot path
        } finally {
            StreamShedEvent.emit(
                    capturedStreamId,
                    effectivePriority.name(),
                    decision.name(),
                    engineName,
                    activeStreamCount
            );
        }
    }

    /**
     * Returns the total number of streams shed since this instance was created.
     *
     * <p>O(1) — single VarHandle acquire read.
     *
     * @return monotonically increasing shed count
     */
    public long shedCount() {
        return (long) SHED_COUNT.getAcquire(this);
    }
}
