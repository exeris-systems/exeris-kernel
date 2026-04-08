/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.flow.model.FlowState;

import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class FlowProgressPublisher {

    private static final String FLOW_PROGRESS_EVENT_TYPE = "FlowProgress";
    private static final int FLOW_PROGRESS_ORDINAL_UNRESOLVED = Integer.MIN_VALUE;
    private static final int FLOW_PROGRESS_ORDINAL_DISABLED = -1;
    private static final int FLOW_PROGRESS_ORDINAL_PROBE_LIMIT = 32;

    private final Object flowProgressRegistrationMonitor = new Object();
    private final AtomicInteger flowProgressOrdinal = new AtomicInteger(FLOW_PROGRESS_ORDINAL_UNRESOLVED);

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
                            new FlowProgressPayload(instance.definitionName(), stepIndex, state)
            );
        } catch (RuntimeException ignored) {
            // Best-effort publication must never alter flow execution semantics.
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private int resolveFlowProgressOrdinal(EventEngine eventEngine) {
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
