/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.ResourceArbiter.Action;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConfig;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Core: Protocol-agnostic admission controller for incoming {@link eu.exeris.kernel.spi.transport.TransportStream}s.
 *
 * <h2>Architectural Role (The Brain)</h2>
 * <p>The {@code AdmissionController} is a stateless O(1) decision gate that
 * bridges the {@link ResourceArbiter} (memory pressure) with the
 * {@link eu.exeris.kernel.spi.transport.StreamPriority} (business context).
 * It answers: <em>"Given current memory pressure and stream priority, should this stream
 * be admitted, throttled, or shed?"</em>
 *
 * <h2>Decision Matrix</h2>
 * <pre>
 *   ResourceArbiter.Action → StreamPriority   → Decision
 *   ────────────────────────────────────────────────────────
 *   ALLOW                 → any               → ADMIT
 *   THROTTLE              → CRITICAL, HIGH     → ADMIT
 *   THROTTLE              → NORMAL, LOW, TELEM → SHED
 *   REJECT                → CRITICAL           → ADMIT
 *   REJECT                → HIGH, NORMAL, ...  → SHED
 *   SHED_LOAD             → any               → SHED
 * </pre>
 *
 * <h2>Performance Contract</h2>
 * <p>Hot path: one {@link ResourceArbiter#decide} call (O(1), 1-ms cached) plus an
 * amortised O(1) CAS loop for capacity reservation. No locks, no allocations.
 *
 * <h2>Active Stream Counter (VarHandle CAS)</h2>
 * <p>The {@code activeStreamCount} field tracks the current number of in-flight
 * Virtual Thread-per-stream handlers. The slot is atomically reserved inside
 * {@link #admit(StreamPriority)} and released on stream completion
 * (called by {@link PaqsScheduler} upon VT exit).
 * This count feeds the JFR {@link eu.exeris.kernel.core.transport.jfr.StreamShedEvent}
 * for observability.
 *
 * @since 0.5
 * @see PaqsScheduler
 * @see ResourceArbiter
 * @see StreamPriority
 */
public final class AdmissionController {

    private static final VarHandle ACTIVE_COUNT;

    static {
        try {
            ACTIVE_COUNT = MethodHandles.lookup()
                    .findVarHandle(AdmissionController.class, "activeStreamCount", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /* default */ volatile int activeStreamCount;

    private final ResourceArbiter arbiter;
    private final int maxActiveStreams;
    private final int capacityCeiling;

    /**
     * Creates a new {@code AdmissionController} with the default stream ceiling.
     *
     * @param arbiter the memory-pressure arbiter; must not be {@code null}
     * @see TransportConfig#DEFAULT_MAX_ACTIVE_STREAMS
     */
    public AdmissionController(ResourceArbiter arbiter) {
        this(arbiter, TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
    }

    /**
     * Creates a new {@code AdmissionController} enforcing the given stream ceiling.
     *
     * @param arbiter          the memory-pressure arbiter; must not be {@code null}
     * @param maxActiveStreams concurrently admitted streams to allow, or
     *                         {@link TransportConfig#UNBOUNDED_ACTIVE_STREAMS} for no ceiling.
     *                         Refused here as well as at config construction: a controller built
     *                         directly by a driver is the same operator error one layer down.
     * @since 0.12
     */
    public AdmissionController(ResourceArbiter arbiter, int maxActiveStreams) {
        if (arbiter == null) {
            throw new IllegalArgumentException("arbiter must not be null");
        }
        if (maxActiveStreams < 1 && maxActiveStreams != TransportConfig.UNBOUNDED_ACTIVE_STREAMS) {
            throw new IllegalArgumentException(
                    "maxActiveStreams must be >= 1, or "
                            + TransportConfig.UNBOUNDED_ACTIVE_STREAMS
                            + " for no ceiling, got: " + maxActiveStreams);
        }
        this.arbiter = arbiter;
        this.maxActiveStreams = maxActiveStreams;
        // The sentinel is normalised once, here, rather than branched on per admission. The counter
        // is an int, so "no ceiling" is Integer.MAX_VALUE and not a special case the hot loop has to
        // recognise — a comparison against the raw -1 would shed every stream instead of none.
        this.capacityCeiling = maxActiveStreams == TransportConfig.UNBOUNDED_ACTIVE_STREAMS
                ? Integer.MAX_VALUE
                : maxActiveStreams;
    }

    // =========================================================================
    // Core API
    // =========================================================================

    /**
     * Returns the {@link Decision} for a new incoming stream with the given priority.
     *
     * <p>This is the <b>hot path</b> — called for every inbound stream before a
     * Virtual Thread is spawned. It must be allocation-free.
     *
     * <p>For admitting decisions this method also atomically reserves one slot in the
     * active stream counter, enforcing {@link #maxActiveStreams()} as a hard cap under
     * concurrency. If capacity is exhausted at reservation time the decision is
     * overridden to {@link Decision#SHED_CAPACITY}, regardless of memory pressure.
     *
     * @param priority the stream's business priority; must not be {@code null}
     * @return admission decision; never {@code null}
     */
    public Decision admit(StreamPriority priority) {
        StreamPriority effective = (priority == null) ? StreamPriority.NORMAL : priority;

        Action arbiterAction = arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO);
        Decision decision = mapToDecision(arbiterAction, effective);

        if (!decision.isAdmit()) {
            return decision;
        }

        for (;;) {
            int current = (int) ACTIVE_COUNT.getAcquire(this);
            if (current >= capacityCeiling) {
                return Decision.SHED_CAPACITY;
            }
            if (ACTIVE_COUNT.compareAndSet(this, current, current + 1)) {
                return decision;
            }
        }
    }


    /**
     * Atomically decrements the active stream counter.
     *
     * <p>Called by {@link PaqsScheduler} in the Virtual Thread's finally block, ensuring
     * the counter reflects streams that have fully completed (including exceptional exits).
     */
    public void onStreamCompleted() {
        ACTIVE_COUNT.getAndAdd(this, -1);
    }

    /**
     * Returns a point-in-time snapshot of the currently active stream count.
     *
     * <p>O(1) — single VarHandle acquire read.
     *
     * @return number of concurrently active streams
     */
    public int activeStreamCount() {
        return (int) ACTIVE_COUNT.getAcquire(this);
    }

    /**
     * Returns the configured ceiling as given, sentinel and all.
     *
     * <p>Reports {@link TransportConfig#UNBOUNDED_ACTIVE_STREAMS} rather than the
     * {@code Integer.MAX_VALUE} the hot path compares against, so a diagnostic reading this shows
     * what the operator configured and not what the loop was rewritten to.
     *
     * @return the configured maximum, or {@link TransportConfig#UNBOUNDED_ACTIVE_STREAMS}
     * @since 0.12
     */
    public int maxActiveStreams() {
        return maxActiveStreams;
    }

    // =========================================================================
    // Decision enum
    // =========================================================================

    /**
     * The admission decision returned by {@link #admit(StreamPriority)}.
     *
     * <h2>Valhalla Readiness</h2>
     * <p>Enum constants are JVM singletons. C2 JIT will constant-fold comparisons.
     */
    public enum Decision {
        /**
         * The stream is accepted; spawn a Virtual Thread.
         */
        ADMIT,
        /**
         * The stream is shed because memory pressure is too high for this priority.
         */
        SHED_MEMORY,
        /**
         * The stream is shed because the active stream count limit is reached.
         */
        SHED_CAPACITY;

        /**
         * Returns {@code true} if the decision allows spawning a Virtual Thread.
         *
         * @return {@code true} for {@link #ADMIT}
         */
        public boolean isAdmit() {
            return this == ADMIT;
        }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Maps a {@link ResourceArbiter.Action} and {@link StreamPriority} to an {@link Decision}.
     *
     * <p>O(1) — enum ordinal switch. C2 JIT generates a jump table over ordinals.
     *
     * @param action   the arbiter action for the TRANSPORT_IO context
     * @param priority the stream's business priority
     * @return the admission decision
     */
    private static Decision mapToDecision(Action action, StreamPriority priority) {
        return switch (action) {
            case ALLOW -> Decision.ADMIT;
            case THROTTLE -> switch (priority) {
                case CRITICAL, HIGH -> Decision.ADMIT;
                default -> Decision.SHED_MEMORY;
            };
            case REJECT -> priority == StreamPriority.CRITICAL
                    ? Decision.ADMIT
                    : Decision.SHED_MEMORY;
            case SHED_LOAD -> Decision.SHED_MEMORY;
        };
    }
}
