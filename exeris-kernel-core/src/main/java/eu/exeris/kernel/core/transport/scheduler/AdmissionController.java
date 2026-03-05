/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.ResourceArbiter.Action;
import eu.exeris.kernel.spi.transport.StreamPriority;

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
 * <p>O(1): one VarHandle acquire read (active stream count) + one {@link ResourceArbiter#decide}
 * call (which is itself O(1) with a 1-ms cached decision). No locks, no allocations.
 *
 * <h2>Active Stream Counter (VarHandle CAS)</h2>
 * <p>The {@code activeStreamCount} field tracks the current number of in-flight
 * Virtual Thread-per-stream handlers. The counter is incremented atomically on admit
 * and decremented on stream completion (called by {@link PaqsScheduler} upon VT exit).
 * This count feeds the JFR {@link eu.exeris.kernel.core.transport.jfr.StreamShedEvent}
 * for observability.
 *
 * @see PaqsScheduler
 * @see ResourceArbiter
 * @see StreamPriority
 * @since 0.5.0
 */
public final class AdmissionController {

    /**
     * Hard limit on concurrently active streams per engine instance.
     * Set to {@value} to match the Performance Contract: 5 000 queue saturation threshold.
     * Exceeding this limit triggers SHED regardless of memory pressure.
     */
    private static final int MAX_ACTIVE_STREAMS = 5_000;

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

    /**
     * Creates a new {@code AdmissionController} backed by the given arbiter.
     *
     * @param arbiter the memory-pressure arbiter; must not be {@code null}
     */
    public AdmissionController(ResourceArbiter arbiter) {
        if (arbiter == null) {
            throw new IllegalArgumentException("arbiter must not be null");
        }
        this.arbiter = arbiter;
    }

    // =========================================================================
    // Core API
    // =========================================================================

    /**
     * Returns the {@link Decision} for a new incoming stream with the given priority.
     *
     * <p>This is the <b>hot path</b> — called for every inbound stream before a
     * Virtual Thread is spawned. It must be O(1) and allocation-free.
     *
     * @param priority the stream's business priority; must not be {@code null}
     * @return admission decision; never {@code null}
     */
    public Decision admit(StreamPriority priority) {
        StreamPriority effective = (priority == null) ? StreamPriority.NORMAL : priority;
        int current = (int) ACTIVE_COUNT.getAcquire(this);
        if (current >= MAX_ACTIVE_STREAMS) {
            return Decision.SHED_CAPACITY;
        }

        Action arbiterAction = arbiter.decide(ResourceArbiter.Context.TRANSPORT_IO);
        return mapToDecision(arbiterAction, effective);
    }

    /**
     * Atomically increments the active stream counter.
     *
     * <p>Called by {@link PaqsScheduler} immediately after an {@link Decision#isAdmit()} decision
     * and before spawning the Virtual Thread — ensuring the counter is accurate when the
     * next admission check runs.
     */
    public void onStreamAdmitted() {
        ACTIVE_COUNT.getAndAdd(this, 1);
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
