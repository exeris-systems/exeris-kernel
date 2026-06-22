/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.sse;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.transport.jfr.StreamShedEvent;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.transport.StreamPriority;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Core: admits or sheds a NEW server-push (SSE) stream-open against a streaming-occupancy ceiling,
 * distinct from sub-millisecond request accounting (ADR-043 obligation 7).
 *
 * <p>A stream is admitted once at open with a fixed {@link StreamPriority} and holds its slot for the
 * stream lifetime (minutes, not ms). When the supplied shed decision yields
 * {@link ResourceArbiter.Action#SHED_LOAD}, a NEW open is rejected through the existing PAQS shed path:
 * it reuses {@code EX-NET-4006} and emits the existing {@link StreamShedEvent} (inheriting its
 * no-alert-counter, zero-alloc contract) — this ADR mints NO new HTTP-layer shed code. Already-open
 * streams are unaffected (they never re-consult admission).
 *
 * <p>The shed decision is injected as a {@code Supplier} rather than a concrete {@link ResourceArbiter}
 * so production wires {@code () -> arbiter.decide(Context.TRANSPORT_IO)} while keeping no test-only
 * construction seam in the production {@code ResourceArbiter}.
 *
 * @since 0.10.0
 */
public final class StreamAdmissionController {

    /** Fixed priority assigned to every streaming slot at open (ADR-043 obligation 7). */
    private static final StreamPriority STREAM_SLOT_PRIORITY = StreamPriority.NORMAL;

    private final Supplier<ResourceArbiter.Action> shedDecision;
    private final String engineName;

    /**
     * Creates an admission controller bound to a PAQS shed decision.
     *
     * @param shedDecision the PAQS shed decision consulted on every NEW stream-open (typically
     *                     {@code () -> arbiter.decide(Context.TRANSPORT_IO)}); must not be {@code null}
     * @param engineName   the transport engine name, recorded on the shed event; must not be {@code null}
     */
    public StreamAdmissionController(Supplier<ResourceArbiter.Action> shedDecision, String engineName) {
        this.shedDecision = Objects.requireNonNull(shedDecision, "shedDecision must not be null");
        this.engineName = Objects.requireNonNull(engineName, "engineName must not be null");
    }

    /**
     * Admits a NEW stream-open, or throws when the PAQS shed decision sheds load.
     *
     * @param streamId      the opening stream's identifier (for the shed event)
     * @param activeStreams the current active streaming-slot occupancy (for the shed event)
     * @throws TransportException carrying {@code EX-NET-4006} when the open is shed
     */
    public void admit(long streamId, int activeStreams) {
        ResourceArbiter.Action action = shedDecision.get();
        if (action == ResourceArbiter.Action.SHED_LOAD) {
            StreamShedEvent.emit(
                    streamId, STREAM_SLOT_PRIORITY.name(), action.name(), engineName, activeStreams);
            throw TransportException.streamShed(engineName, streamId);
        }
    }
}
