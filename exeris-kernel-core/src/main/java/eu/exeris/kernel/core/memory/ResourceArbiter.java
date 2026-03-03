/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Core: Memory-pressure governor that makes "Stop/Go" load-shedding decisions
 * based on the current {@link WatermarkLevel} without knowing the underlying
 * allocation driver.
 *
 * <h2>Architectural Role (The Brain)</h2>
 * <p>The {@code ResourceArbiter} is the Core's "circuit breaker". It decouples the
 * <em>decision to shed load</em> from the <em>physical allocation of bytes</em>.
 * Transport and logic layers consult the arbiter on every inbound request; the
 * arbiter answers with an {@link Action} derived purely from memory pressure signals.
 *
 * <h2>Decision Cache (VarHandle — 1 ms TTL)</h2>
 * <p>To avoid calling {@link WatermarkManager#currentLevel()} on every single request
 * (which itself is O(1) but still a volatile read), the arbiter caches the last decision
 * for {@value #DECISION_CACHE_TTL_NS} nanoseconds. The cache is represented as two
 * per-context pairs of fields atomically updated via {@link VarHandle} setRelease:
 * <ul>
 *   <li>{@code transportDecisionNs} / {@code transportActionOrdinal} — TRANSPORT_IO context</li>
 *   <li>{@code logicDecisionNs} / {@code logicActionOrdinal} — KERNEL_LOGIC context</li>
 * </ul>
 *
 * <h2>Startup Grace Period (30 s)</h2>
 * <p>During the first {@value #GRACE_PERIOD_NS} nanoseconds after construction, the
 * arbiter always returns {@link Action#ALLOW}. This prevents spurious load-shedding
 * during kernel bootstrap when memory pools are still warming up.
 *
 * <h2>Context Awareness</h2>
 * <p>Two named contexts are supported:
 * <ul>
 *   <li>{@link Context#TRANSPORT_IO} — inbound network connections and streams</li>
 *   <li>{@link Context#KERNEL_LOGIC} — internal kernel work (projection, saga, graph)</li>
 * </ul>
 * {@code KERNEL_LOGIC} uses a stricter threshold: it sheds at {@link WatermarkLevel#CRITICAL}
 * rather than waiting for {@link WatermarkLevel#SHEDDING}. This preserves transport
 * throughput at the cost of background logic work.
 *
 * <h2>Performance Contract</h2>
 * <p>{@link #decide(Context)} is O(1): one {@code System.nanoTime()} call + one
 * VarHandle acquire read + one conditional VarHandle CAS. No locks, no allocations.
 *
 * <h2>JFR-First</h2>
 * <p>Each cache-miss (re-evaluation) emits a {@link ResourceArbiterDecisionEvent}.
 * Cache hits are silent — zero JFR overhead per request on the steady state.
 *
 * @see WatermarkManager
 * @see WatermarkLevel
 * @see ResourceArbiterDecisionEvent
 * @since 0.5.0
 */
public final class ResourceArbiter {

    /**
     * Decision cache TTL: 1 millisecond in nanoseconds.
     */
    private static final long DECISION_CACHE_TTL_NS = 1_000_000L;

    /**
     * Startup grace period: 30 seconds in nanoseconds.
     */
    private static final long GRACE_PERIOD_NS = 30_000_000_000L;

    /**
     * Sentinel ordinal indicating the cache is uninitialized.
     */
    private static final int CACHE_UNINITIALIZED = -1;

    /**
     * Pre-computed {@link Action} values array — avoids {@code Action.values()} allocation
     * on every cache-hit (each {@code values()} call returns a new defensive copy).
     * Static final so C2 JIT constant-folds the array reference on the hot path.
     */
    private static final Action[] ACTIONS = Action.values();

    private static final VarHandle TRANSPORT_DECISION_NS;
    private static final VarHandle TRANSPORT_ACTION_ORDINAL;
    private static final VarHandle LOGIC_DECISION_NS;
    private static final VarHandle LOGIC_ACTION_ORDINAL;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            TRANSPORT_DECISION_NS = lookup.findVarHandle(
                    ResourceArbiter.class, "transportDecisionNs", long.class);
            TRANSPORT_ACTION_ORDINAL = lookup.findVarHandle(
                    ResourceArbiter.class, "transportActionOrdinal", int.class);
            LOGIC_DECISION_NS = lookup.findVarHandle(
                    ResourceArbiter.class, "logicDecisionNs", long.class);
            LOGIC_ACTION_ORDINAL = lookup.findVarHandle(
                    ResourceArbiter.class, "logicActionOrdinal", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /* default */ volatile long transportDecisionNs;
    /* default */ volatile int transportActionOrdinal = CACHE_UNINITIALIZED;
    /* default */ volatile long logicDecisionNs;
    /* default */ volatile int logicActionOrdinal = CACHE_UNINITIALIZED;

    private final WatermarkManager watermarkManager;
    private final long startNs;

    // =========================================================================
    // Action — the load-shedding vocabulary
    // =========================================================================

    /**
     * The four possible responses from the arbiter.
     *
     * <h2>Transport Layer Mapping</h2>
     * <table>
     *   <tr><th>Action</th><th>HTTP/3 Response</th><th>Semantics</th></tr>
     *   <tr><td>ALLOW</td><td>200 / stream proceed</td><td>Full capacity — accept request</td></tr>
     *   <tr><td>THROTTLE</td><td>503 Retry-After: 100ms</td><td>Soft backpressure — queue is high</td></tr>
     *   <tr><td>REJECT</td><td>503 immediately</td><td>Hard backpressure — memory critical</td></tr>
     *   <tr><td>SHED_LOAD</td><td>H3_EXCESSIVE_LOAD (0x107)</td><td>OOM risk — shed all new traffic</td></tr>
     * </table>
     *
     * <p>Valhalla-ready: enum constants are JVM singletons — zero heap allocation on comparison,
     * safe for identity checks ({@code ==}) by the transport layer.
     */
    public enum Action {
        /**
         * Full capacity — accept the request.
         */
        ALLOW,
        /**
         * Memory pressure rising — soften intake, trigger proactive backpressure.
         */
        THROTTLE,
        /**
         * Memory critical — reject non-essential requests.
         */
        REJECT,
        /**
         * Memory exhaustion imminent — shed all new load (H3_EXCESSIVE_LOAD).
         */
        SHED_LOAD
    }

    // =========================================================================
    // Context — arbitration scope
    // =========================================================================

    /**
     * Arbitration context — defines which threshold table to apply.
     *
     * <h2>Valhalla Readiness</h2>
     * <p>Enum constants are JVM singletons. The name strings are {@code final} constants
     * computed once at class load. Zero allocation on {@link #contextName()} call.
     */
    public enum Context {
        /**
         * Inbound network connections and streams.
         * Sheds load only at {@link WatermarkLevel#SHEDDING}.
         */
        TRANSPORT_IO("TRANSPORT_IO"),

        /**
         * Internal kernel work: projections, sagas, graph queries.
         * Sheds load earlier — at {@link WatermarkLevel#CRITICAL}.
         * Preserves transport throughput by sacrificing background logic under pressure.
         */
        KERNEL_LOGIC("KERNEL_LOGIC");

        private final String name;

        Context(String name) {
            this.name = name;
        }

        /**
         * Returns the stable context name (never allocates — field read).
         */
        public String contextName() {
            return name;
        }
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Production constructor — grace period starts at current wall time.
     *
     * @param watermarkManager the pressure monitor; must not be {@code null}
     */
    public ResourceArbiter(WatermarkManager watermarkManager) {
        this(watermarkManager, System.nanoTime());
    }

    /**
     * Package-private testing constructor — allows injecting an artificial {@code startNs}
     * to simulate an already-expired (or future) grace period without sleeping.
     *
     * @param watermarkManager the pressure monitor; must not be {@code null}
     * @param startNs          nanosecond timestamp to treat as construction time
     */
    /* default */ ResourceArbiter(WatermarkManager watermarkManager, long startNs) {
        if (watermarkManager == null) {
            throw new IllegalArgumentException("watermarkManager must not be null");
        }
        this.watermarkManager = watermarkManager;
        this.startNs = startNs;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the load-shedding {@link Action} for the given arbitration context.
     *
     * <p>This is the <b>hot path</b> — called on every inbound request.
     * Implementation guarantees:
     * <ul>
     *   <li>O(1): one {@code System.nanoTime()} + one VarHandle acquire read.</li>
     *   <li>Zero heap allocation.</li>
     *   <li>JFR event emitted only on cache miss (every ~1 ms), not on every call.</li>
     * </ul>
     *
     * @param context the arbitration context (TRANSPORT_IO or KERNEL_LOGIC); must not be {@code null}
     * @return the action to take; never {@code null}
     * @throws IllegalArgumentException if {@code context} is {@code null}
     */
    public Action decide(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        long nowNs = System.nanoTime();

        if (nowNs - startNs < GRACE_PERIOD_NS) {
            return Action.ALLOW;
        }

        boolean isTransport = context == Context.TRANSPORT_IO;
        long lastDecisionNs = isTransport
                ? (long) TRANSPORT_DECISION_NS.getAcquire(this)
                : (long) LOGIC_DECISION_NS.getAcquire(this);
        int lastOrdinal = isTransport
                ? (int) TRANSPORT_ACTION_ORDINAL.getAcquire(this)
                : (int) LOGIC_ACTION_ORDINAL.getAcquire(this);
        boolean cacheValid = lastOrdinal != CACHE_UNINITIALIZED
                && (nowNs - lastDecisionNs) < DECISION_CACHE_TTL_NS;
        if (cacheValid) {
            return ACTIONS[lastOrdinal];
        }

        return reEvaluate(context, nowNs);
    }

    /**
     * Returns {@code true} if the kernel is still within the startup grace period.
     *
     * @return {@code true} during the first 30 seconds after construction
     */
    public boolean isInGracePeriod() {
        return (System.nanoTime() - startNs) < GRACE_PERIOD_NS;
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Re-evaluates the action from the current {@link WatermarkLevel}, updates the
     * decision cache, and emits a {@link ResourceArbiterDecisionEvent}.
     *
     * <p>The CAS on {@code cachedDecisionNs} is a "best effort" write — if two threads
     * race here, both compute the same action (watermark level is the same) so correctness
     * is preserved regardless of who wins the CAS.
     */
    private Action reEvaluate(Context context, long nowNs) {
        WatermarkLevel level = watermarkManager.currentLevel();
        Action action = mapLevelToAction(context, level);

        int newOrdinal = action.ordinal();

        if (context == Context.TRANSPORT_IO) {
            TRANSPORT_ACTION_ORDINAL.set(this, newOrdinal);
            TRANSPORT_DECISION_NS.setRelease(this, nowNs);
        } else {
            LOGIC_ACTION_ORDINAL.set(this, newOrdinal);
            LOGIC_DECISION_NS.setRelease(this, nowNs);
        }

        int utilizationPct = -1;
        ResourceArbiterDecisionEvent.emit(action, context.contextName(), utilizationPct, nowNs);

        return action;
    }

    /**
     * Maps a {@link WatermarkLevel} to an {@link Action} for the given context.
     *
     * <p>O(1) — enum ordinal switch. C2 JIT will compile this to a jump table.
     *
     * @param context the arbitration context
     * @param level   the current watermark level
     * @return the appropriate action
     */
    private static Action mapLevelToAction(Context context, WatermarkLevel level) {
        return switch (level) {
            case NORMAL -> Action.ALLOW;
            case WARNING -> Action.THROTTLE;
            case CRITICAL -> context == Context.KERNEL_LOGIC
                    ? Action.SHED_LOAD
                    : Action.REJECT;
            case SHEDDING -> Action.SHED_LOAD;
        };
    }
}
