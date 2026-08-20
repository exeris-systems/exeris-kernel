/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;

import java.util.Optional;

/**
 * SPI: Schedules {@link FlowExecutionPlan} instances and manages PARK/WAKE lifecycle.
 *
 * <h2>Tier Contract</h2>
 * <ul>
 *   <li><b>Community</b>: submits to a {@code StructuredTaskScope} per scheduled batch.
 *       Parked flows stored in a {@code ConcurrentHashMap}. No ordering guarantees.</li>
 *   <li><b>Enterprise</b>: enqueues the {@link FlowContext} base address (a raw {@code long})
 *       into a lock-free MPSC ring buffer backed by an off-heap slab.
 *       <br>
 *       <b>False-sharing prevention:</b> the ring buffer MUST physically separate the
 *       {@code head} and {@code tail} counter fields by at least 128 bytes (2 × 64-byte
 *       L1 cache lines) so that producer and consumer threads never contend on the same
 *       cache line. The specific mechanism (padding fields, off-heap layout, or any
 *       cache-line isolation technique) is left to the implementation and MUST NOT rely
 *       on JDK-internal APIs.
 *       <br>
 *       Parked flows stored in an off-heap slot array — zero heap allocation.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods MUST be safe for concurrent invocation from any virtual thread.
 *
 * @since 0.5.0
 * @see FlowContext
 * @see FlowExecutionPlan
 */
public interface FlowScheduler {

    /**
     * Schedules the given flow for execution.
     *
     * <p>Community: submits the plan+context pair to a {@code StructuredTaskScope}.
     * Enterprise: CAS-enqueues the context base address into the lock-free ring buffer.
     *
     * @param plan    the compiled execution plan; must not be {@code null}
     * @param context the runtime flow context identifying the instance; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if the scheduler
     *         queue is full (Enterprise: ring buffer at capacity)
     */
    void schedule(FlowExecutionPlan plan, FlowContext context);

    /**
     * Parks the given flow instance, suspending execution until {@link #wake(FlowContext)}.
     *
     * <p>Community: stores the context in the internal parked-flows map.
     * Enterprise: writes the context base address into the off-heap parked-set slab.
     *
     * @param context the context to park; must not be {@code null}
     */
    void park(FlowContext context);

    /**
     * Wakes a previously parked flow, re-submitting it for execution.
     *
     * <p>Community: retrieves from the parked-flows map and re-schedules.
     * Enterprise: CAS-enqueues the base address back into the lock-free ring buffer.
     * Implementations may tolerate the immediate schedule → park → wake race window,
     * but an ordinary non-parked context should still fail clearly.
     *
     * @param context the context to wake; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if the context is
     *         not currently parked
     */
    void wake(FlowContext context);

    /**
     * Looks up a currently parked flow instance by its UUID components.
     *
     * <p>The in-memory parked registry is the required O(1) fast path
     * for live-runtime wake. When persistence is enabled, implementations
     * may consult {@link eu.exeris.kernel.spi.flow.model.FlowSnapshotStore}
     * only on an in-memory miss, and that fallback path should be bounded
     * so repeated misses do not degenerate into unbounded repeated store probes.
     * The default implementation always returns {@link java.util.Optional#empty()}.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @return an {@link Optional} containing the {@link FlowContext} if a parked instance
     *         with the given UUID is currently known to this scheduler; empty otherwise
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException with
     *         {@code phase=SCHEMA_MISMATCH} ({@code EX-FLOW-7002}) if a snapshot is found on the
     *         fallback path but its persisted resume step no longer indexes a step in the active
     *         flow definition (the definition changed under a parked saga). This is fail-closed by
     *         design — resuming against an incompatible plan is a data-corruption-class outcome — so
     *         a snapshot-fallback hit can surface this rather than {@link java.util.Optional#empty()};
     *         the in-memory fast path never throws it.
     */
    /**
     * Wakes the instance identified by these ids, whatever the engine currently holds for it.
     *
     * <p>The key-addressed sibling of {@link #wake(FlowContext)}, added because the two-call form
     * a choreography bridge had to use - {@code lookupParked(...).ifPresent(this::wake)} - is
     * check-then-act and cannot be made atomic from outside the engine. A callback can land while
     * the instance is still inside the step that is about to park, and the lookup reports it
     * absent; dropping the wake there strands the saga, because a choreography wake is one event
     * per business trigger and nothing re-sends it. Resolving by key inside the engine removes
     * both the race and the second durable-store probe the two-call form pays on a miss.
     *
     * <p>An implementation MUST treat a live instance that has not parked yet as a wake to be
     * deferred until it does, not as an absent one. A key the engine genuinely does not know
     * still fails as it would through {@link #wake(FlowContext)}.
     *
     * <p>The default keeps the pre-0.12 two-call behaviour, so an implementation that does not
     * override it is unchanged - including its exposure to the race above.
     *
     * @param instanceIdMost  most significant bits of the flow instance key
     * @param instanceIdLeast least significant bits of the flow instance key
     * @since 0.12.0
     */
    default void wake(long instanceIdMost, long instanceIdLeast) {
        lookupParked(instanceIdMost, instanceIdLeast).ifPresent(this::wake);
    }

    default Optional<FlowContext> lookupParked(long instanceIdMost, long instanceIdLeast) {
        return Optional.empty();
    }
}

