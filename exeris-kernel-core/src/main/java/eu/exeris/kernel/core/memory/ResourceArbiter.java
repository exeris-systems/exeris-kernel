/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p>To avoid evaluating the {@link WatermarkManager} logic on every single request,
 * the arbiter caches the last decision locally for {@value #DECISION_CACHE_TTL_NS}
 * nanoseconds. The cache is represented as two per-context pairs of fields atomically
 * updated via {@link VarHandle} setRelease:
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
 * <p>{@link #decide(Context)} is O(1) on a cache hit: one {@code System.nanoTime()} call
 * plus two VarHandle acquire reads (cached decision timestamp and action ordinal), no locks,
 * no allocations. On a cache miss it calls {@link #reEvaluate}, which reads the current
 * {@link WatermarkLevel} from {@link WatermarkManager}, updates the cache via VarHandle
 * {@code setRelease}, and emits a {@link ResourceArbiterDecisionEvent} — allocating a JFR
 * event object whenever {@code FlightRecorder} is initialised, independent of whether
 * recording is enabled.
 *
 * <h2>JFR-First</h2>
 * <p>Each cache-miss (re-evaluation) emits a {@link ResourceArbiterDecisionEvent} with the
 * real utilization percentage sourced from {@link WatermarkManager#currentUtilizationPct()}.
 * Cache hits are silent — zero JFR overhead per request on the steady state.
 *
 * <h2>Per-Tenant SLA Override (ScalingContext)</h2>
 * <p>The overloaded {@link #decide(Context, ScalingContext)} method bypasses the fixed
 * {@link WatermarkLevel} thresholds and applies tenant-specific SLA thresholds from the
 * {@link ScalingContext} the caller supplies explicitly. The raw utilization ratio
 * is derived from {@link WatermarkManager#currentUtilizationPct()} — zero allocation.
 *
 * @since 0.5
 * @see WatermarkManager
 * @see WatermarkLevel
 * @see ScalingContext
 * @see ResourceArbiterDecisionEvent
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
     * <p>Each action carries a general backpressure meaning; the transport layer's actual
     * admission decision additionally weighs the stream's business priority — see
     * {@code AdmissionController}'s decision matrix for the mapping this kernel applies
     * (for example, a {@code CRITICAL}-priority stream is still admitted under {@link #REJECT}).
     * <table>
     *   <caption>Load-shedding action to protocol-level meaning</caption>
     *   <tr><th>Action</th><th>Protocol-level meaning</th><th>Semantics</th></tr>
     *   <tr><td>ALLOW</td><td>request proceeds</td><td>Full capacity — accept request</td></tr>
     *   <tr><td>THROTTLE</td><td>soft backpressure</td><td>Queue is high — lower priorities shed</td></tr>
     *   <tr><td>REJECT</td><td>hard backpressure</td><td>Memory critical — only top priority admitted</td></tr>
     *   <tr><td>SHED_LOAD</td><td>{@code H3_EXCESSIVE_LOAD}</td><td>OOM risk — shed all new traffic</td></tr>
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
         *
         * @return the context name (e.g. {@code "TRANSPORT_IO"}); never {@code null}
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
     * @throws IllegalArgumentException if {@code watermarkManager} is {@code null}
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
     * @throws IllegalArgumentException if {@code watermarkManager} is {@code null}
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
        int lastOrdinal = isTransport
                ? (int) TRANSPORT_ACTION_ORDINAL.getAcquire(this)
                : (int) LOGIC_ACTION_ORDINAL.getAcquire(this);
        long lastDecisionNs = isTransport
                ? (long) TRANSPORT_DECISION_NS.getAcquire(this)
                : (long) LOGIC_DECISION_NS.getAcquire(this);
        boolean cacheValid = lastOrdinal != CACHE_UNINITIALIZED
                && (nowNs - lastDecisionNs) < DECISION_CACHE_TTL_NS;
        if (cacheValid) {
            return ACTIONS[lastOrdinal];
        }

        return reEvaluate(context, nowNs);
    }

    /**
     * Returns the load-shedding {@link Action} applying per-tenant SLA thresholds from
     * the supplied {@link ScalingContext} instead of the fixed {@link WatermarkLevel} table.
     *
     * <p>Use this overload when the call site has already resolved a tenant-specific
     * {@link ScalingContext} for the request and passes it explicitly. The raw utilization
     * ratio is read from {@link WatermarkManager#currentUtilizationPct()} — O(1), zero
     * allocation.
     *
     * <p>Unlike the single-argument {@link #decide(Context)}, this overload does NOT use
     * the decision cache — each call performs a fresh threshold evaluation to respect the
     * per-tenant thresholds. This is intentional: SLA overrides are expected to be rare,
     * and the call site is responsible for caching if needed.
     *
     * @param context        the arbitration context; must not be {@code null}
     * @param scalingContext the tenant-specific SLA thresholds; must not be {@code null}
     * @return the action to take; never {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public Action decide(Context context, ScalingContext scalingContext) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (scalingContext == null) {
            throw new IllegalArgumentException("scalingContext must not be null");
        }
        long nowNs = System.nanoTime();
        if (nowNs - startNs < GRACE_PERIOD_NS) {
            return Action.ALLOW;
        }
        int utilizationPct = watermarkManager.currentUtilizationPct();
        Action action = ResourceArbiterPolicy.actionForScalingContext(scalingContext, utilizationPct);
        ResourceArbiterDecisionTelemetry.emit(action, context, utilizationPct, nowNs);
        return action;
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
     * <p>The setRelease write on the cache fields is a "best effort" store — if two threads
     * race here, both compute the same action (watermark level is the same) so correctness
     * is preserved regardless of who writes last.
     */
    private Action reEvaluate(Context context, long nowNs) {
        WatermarkLevel level = watermarkManager.currentLevel();
        Action action = ResourceArbiterPolicy.actionForLevel(context, level);

        int newOrdinal = action.ordinal();

        if (context == Context.TRANSPORT_IO) {
            TRANSPORT_DECISION_NS.setRelease(this, nowNs);
            TRANSPORT_ACTION_ORDINAL.setRelease(this, newOrdinal);
        } else {
            LOGIC_DECISION_NS.setRelease(this, nowNs);
            LOGIC_ACTION_ORDINAL.setRelease(this, newOrdinal);
        }

        int utilizationPct = watermarkManager.currentUtilizationPct();
        ResourceArbiterDecisionTelemetry.emit(action, context, utilizationPct, nowNs);

        return action;
    }
}
