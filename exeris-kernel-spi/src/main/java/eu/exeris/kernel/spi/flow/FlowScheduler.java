/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;

import java.util.Optional;

/**
 * SPI: Schedules {@link FlowExecutionPlan} instances and manages PARK/WAKE lifecycle.
 *
 * <p><b>Allocation:</b> allocates — what a binding allocates here is what
 * {@link FlowEngineCapabilities#zeroGcAfterStart()} reports: a binding claiming {@code true}
 * allocates nothing on the heap once {@link FlowEngine#start()} has returned, one claiming
 * {@code false} parks and enqueues through heap structures. {@link #lookupParked} allocates on a
 * hit — {@code Optional.of(...)} plus whatever restoring the instance from the snapshot-store
 * fallback costs — while a miss returns the shared {@link Optional#empty()} singleton and
 * allocates nothing on that path.
 * <p><b>Thread confinement:</b> any thread — every method must be safe for concurrent invocation
 * from any virtual thread.
 * <p><b>Ownership:</b> the caller supplies the {@link FlowContext}; from {@link #park} the
 * scheduler retains it until a wake retrieves it, and {@link #lookupParked} hands back that
 * retained instance rather than a copy. Nothing here is closed or released by the caller.
 *
 * @implSpec A scheduler that enqueues through a lock-free ring buffer must physically separate the
 *           buffer's {@code head} and {@code tail} counters by at least 128 bytes (two 64-byte L1
 *           cache lines), so producer and consumer never contend on one cache line. The mechanism —
 *           padding fields, off-heap layout, any cache-line isolation technique — is the
 *           implementation's choice, but it must not rest on JDK-internal APIs.
 * @implNote The Community binding submits to a {@code StructuredTaskScope} per scheduled batch and
 *           holds parked flows in a {@code ConcurrentHashMap}, with no ordering guarantee. The
 *           Enterprise binding enqueues the {@link FlowContext} base address as a raw {@code long}
 *           into a lock-free MPSC ring buffer over an off-heap slab, and holds parked flows in an
 *           off-heap slot array.
 * @since 0.5
 * @see FlowContext
 * @see FlowExecutionPlan
 */
public interface FlowScheduler {

    /**
     * Admits the instance for execution, binding it to the plan it will run: from here the engine
     * owns the stepping of it.
     *
     * @param plan    the compiled execution plan; must not be {@code null}
     * @param context the runtime flow context identifying the instance; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} with
     *         {@code phase="SCHEDULE"} and {@code reasonCode="QUEUE_FULL"} if the scheduler cannot
     *         admit it, carrying the queue depth at the time of overflow
     * @implSpec A context whose state is already {@code PARKED} must be registered in the parked
     *           map without spawning step execution — a restart that re-submits recovered instances
     *           registers them, it does not replay them.
     * @implNote The Community binding submits the plan and context to a {@code StructuredTaskScope};
     *           the Enterprise binding CAS-enqueues the context base address into its ring buffer.
     */
    void schedule(FlowExecutionPlan plan, FlowContext context);

    /**
     * Suspends the instance and records it as discoverable, so that a later wake — from
     * choreography, a timer, or any other trigger — resumes it at the step it stopped on rather
     * than restarting it.
     *
     * @param context the context to park; must not be {@code null}
     * @implNote The Community binding stores the context in its parked-flows map; the Enterprise
     *           binding writes the base address into the off-heap parked-set slab.
     */
    void park(FlowContext context);

    /**
     * Resumes a parked instance, re-submitting it for execution from the step it parked on.
     *
     * @param context the context to wake; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} with
     *         {@code phase="WAKE"} and {@code reasonCode="NOT_PARKED"} if the context is not
     *         currently parked — classifiable through
     *         {@link eu.exeris.kernel.spi.exceptions.flow.FlowEngineException#isNotParked(Throwable)}
     * @implSpec An implementation may tolerate the immediate schedule → park → wake window rather
     *           than throwing, but tolerating it is not discarding the request: a wake that lands
     *           while a run still owns the instance must be honoured once the run releases it. An
     *           ordinary non-parked context must still fail clearly.
     * @apiNote Reaching this through {@code lookupParked(...).ifPresent(this::wake)} is
     *          check-then-act and cannot be made atomic from outside the engine; prefer
     *          {@link #wake(long, long)}, which resolves the instance inside it.
     * @implNote The Community binding retrieves from the parked-flows map and re-schedules; the
     *           Enterprise binding CAS-enqueues the base address back into the ring buffer.
     */
    void wake(FlowContext context);

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
     * @param instanceIdMost  most significant bits of the flow instance key
     * @param instanceIdLeast least significant bits of the flow instance key
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} with
     *         {@code phase="WAKE"} and {@code reasonCode="NOT_PARKED"} for a key the engine does
     *         not know — the same refusal {@link #wake(FlowContext)} raises
     * @implSpec An implementation must treat a live instance that has not parked yet as a wake to
     *           be deferred until it does, not as an absent one. A key the engine genuinely does
     *           not know still fails as it would through {@link #wake(FlowContext)}. The default
     *           implementation resolves through {@link #lookupParked} and delegates, keeping the
     *           two-call behaviour — including its exposure to the race above — so an
     *           implementation that does not override this is unchanged.
     * @since 0.12
     */
    default void wake(long instanceIdMost, long instanceIdLeast) {
        lookupParked(instanceIdMost, instanceIdLeast).ifPresent(this::wake);
    }

    /**
     * Resolves the context of a currently parked instance from its key, so a caller holding only an
     * instance id can inspect or wake it.
     *
     * <p>An instance that is live but has not parked yet is reported absent — deliberately, since
     * handing out a running instance as parked would let a second event schedule the same flow
     * again.
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
     * @implSpec An instance that was never scheduled must yield {@link java.util.Optional#empty()}
     *           without throwing, whether or not persistence is bound.
     *           The in-memory parked registry is the required O(1) fast path for live-runtime wake.
     *           When persistence is enabled an implementation may consult
     *           {@link eu.exeris.kernel.spi.flow.model.FlowSnapshotStore} on an in-memory miss, and
     *           must bound that fallback so repeated misses do not degenerate into unbounded
     *           repeated store probes. The default implementation always returns
     *           {@link java.util.Optional#empty()}, which is the truthful answer for a scheduler
     *           that tracks no parked instances of its own.
     * @apiNote An instance found here is not one this caller has claimed; between this call and a
     *          {@link #wake(FlowContext)} on the result another waker can take it, which is why
     *          {@link #wake(long, long)} exists.
     */
    default Optional<FlowContext> lookupParked(long instanceIdMost, long instanceIdLeast) {
        return Optional.empty();
    }
}

