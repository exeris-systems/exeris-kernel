/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.flow.model.FlowState;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes a best-effort {@code FlowProgress} event on a flow instance's terminal transition, so
 * a subscriber can observe completions and failures without polling.
 *
 * <p>Publication never alters flow execution semantics: a missing {@link EventEngine}, an
 * exhausted ordinal-registration window, or any {@code RuntimeException} raised while publishing
 * is swallowed rather than propagated to the caller.
 */
@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class FlowProgressPublisher {

    private static final String FLOW_PROGRESS_EVENT_TYPE = "FlowProgress";
    private static final int FLOW_PROGRESS_ORDINAL_UNRESOLVED = Integer.MIN_VALUE;
    private static final int FLOW_PROGRESS_ORDINAL_DISABLED = -1;
    private static final int FLOW_PROGRESS_ORDINAL_PROBE_LIMIT = 32;

    private final Object flowProgressRegistrationMonitor = new Object();
    private final AtomicInteger flowProgressOrdinal = new AtomicInteger(FLOW_PROGRESS_ORDINAL_UNRESOLVED);

    /**
     * Publishes a {@code FlowProgress} event for {@code instance}'s transition to {@code state}, or
     * does nothing if no {@link EventEngine} is bound, {@code state} is not terminal, or
     * publication fails for any reason.
     *
     * @param instance  the flow instance that transitioned
     * @param stepIndex the step index the transition occurred at
     * @param state     the state {@code instance} transitioned to; only a terminal state is
     *                  published
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    public void publishProgress(RuntimeFlowInstance instance, int stepIndex, FlowState state) {
        EventEngine eventEngine = instance.eventEngine();
        if (eventEngine == null) {
            return;
        }
        if (!state.isTerminal()) {
            return;
        }
        try {
            int ordinal = resolveFlowProgressOrdinal(eventEngine);
            if (ordinal < 0) {
                return;
            }
            FlowProgressPayload payload = new FlowProgressPayload(instance.definitionName(), stepIndex, state);
            try {
                eventEngine.bus().publish(
                        EventDescriptor.of(
                                progressEventIdHigh(instance, state),
                                progressEventIdLow(instance, stepIndex, state),
                                    instance.key().instanceIdMost(),
                                    instance.key().instanceIdLeast(),
                                ordinal,
                                EventDescriptor.FLAG_ASYNC,
                                System.currentTimeMillis()
                        ),
                        payload
                );
            } catch (RuntimeException ex) {
                payload.close();
            }
        } catch (RuntimeException ignored) {
            // Best-effort publication must never alter flow execution semantics.
        }
    }

    /**
     * Resolves the ordinal {@code FlowProgress} events publish under, registering the type on
     * first use and caching the result for the life of this publisher.
     *
     * <p>Package-private so the give-up branch can be driven directly. It needs only an
     * {@link EventEngine}, whereas {@link #publishProgress} needs a live {@code RuntimeFlowInstance}
     * — and the branch worth testing is the one that decides, not the one that publishes.
     *
     * @param eventEngine the engine whose registry the ordinal is claimed in
     * @return the claimed ordinal, or {@code -1} once publication is permanently disabled
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ int resolveFlowProgressOrdinal(EventEngine eventEngine) {
        int cached = flowProgressOrdinal.get();
        if (cached != FLOW_PROGRESS_ORDINAL_UNRESOLVED) {
            return cached;
        }
        synchronized (flowProgressRegistrationMonitor) {
            cached = flowProgressOrdinal.get();
            if (cached != FLOW_PROGRESS_ORDINAL_UNRESOLVED) {
                return cached;
            }
            EventRegistry eventRegistry = eventEngine.registry();
            EventTypeSpec existing = eventRegistry.resolve(FLOW_PROGRESS_EVENT_TYPE);
            if (existing != null) {
                flowProgressOrdinal.set(existing.ordinal());
                return existing.ordinal();
            }

            int baseOrdinal = 10_000 + Math.floorMod(FLOW_PROGRESS_EVENT_TYPE.hashCode(), 1_000_000);
            for (int offset = 0; offset < FLOW_PROGRESS_ORDINAL_PROBE_LIMIT; offset++) {
                int candidate = baseOrdinal + offset;
                try {
                    eventRegistry.register(EventTypeSpec.of(FLOW_PROGRESS_EVENT_TYPE, candidate));
                    flowProgressOrdinal.set(candidate);
                    return candidate;
                } catch (RuntimeException ignored) {
                    EventTypeSpec resolved = eventRegistry.resolve(FLOW_PROGRESS_EVENT_TYPE);
                    if (resolved != null) {
                        flowProgressOrdinal.set(resolved.ordinal());
                        return resolved.ordinal();
                    }
                }
            }

            // Emitted at the site that makes the decision, and only on the transition: the CAS-free
            // path above returns the cached sentinel, so a per-call emit would fire on every
            // terminal step for the life of the process. Without this the give-up is invisible —
            // a subscriber to FlowProgress just never hears anything, which reads the same as a
            // system where no flow terminated.
            FlowProgressDisabledEvent.emit(
                    FLOW_PROGRESS_EVENT_TYPE, baseOrdinal, FLOW_PROGRESS_ORDINAL_PROBE_LIMIT);
            flowProgressOrdinal.set(FLOW_PROGRESS_ORDINAL_DISABLED);
            return FLOW_PROGRESS_ORDINAL_DISABLED;
        }
    }

    private static long progressEventIdHigh(RuntimeFlowInstance instance, FlowState state) {
        return instance.key().instanceIdMost() ^ (long) state.code;
    }

    private static long progressEventIdLow(RuntimeFlowInstance instance, int stepIndex, FlowState state) {
        long stepBits = Integer.toUnsignedLong(stepIndex) << 32;
        long stateBits = Integer.toUnsignedLong(state.code);
        return instance.key().instanceIdLeast() ^ stepBits ^ stateBits ^ System.nanoTime();
    }
}
