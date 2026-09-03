/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.bootstrap;

/**
 * Ordered bootstrap phases that define when a subsystem initializes relative to others.
 *
 * <h2>The Holy Order</h2>
 * <p>A subsystem in phase N may only start after <em>all</em> subsystems in phase N-1
 * have reached the {@code RUNNING} state (i.e., their {@link Subsystem#start()} has
 * returned cleanly). Subsystems within the same phase are grouped into dependency-safe
 * rounds by their {@link Subsystem#dependsOn()} graph. Whether a round's members run
 * concurrently is an implementation choice and not part of this contract — the Core
 * orchestrator starts them in order on the booting thread (ADR-066), because a subsystem's
 * {@code start()} must observe the {@code ScopedValue} bindings the application established
 * around {@code boot()}, and those cannot be carried onto another thread.
 *
 * <pre>
 * FOUNDATION (L0) — sequential:  memory → crypto → telemetry
 * SERVICES   (L1/L2) — parallel: security | persistence | transport | graph
 * RUNTIME    (L3/L4) — parallel: events | flow
 * </pre>
 *
 * <h2>Mapping to Architecture Layers</h2>
 * <ul>
 *   <li>{@link #FOUNDATION} → L0 (Memory, Crypto, Telemetry).</li>
 *   <li>{@link #SERVICES}   → L1–L2 (Security, Persistence, Transport, Graph).</li>
 *   <li>{@link #RUNTIME}    → L3–L4 (Events, Flow / Sagas).</li>
 * </ul>
 *
 * @since 0.5.0
 */
public enum BootstrapPhase {

    /**
     * Foundation subsystems — must be fully {@code RUNNING} before any other phase starts.
     *
     * <p>Initialized <b>sequentially</b> in topological order within this phase.
     * Failure of any {@code FOUNDATION} subsystem always triggers {@code FAIL_FAST}
     * regardless of the configured failure policy.
     *
     * <p>Typical members: {@code memory}, {@code crypto}, {@code telemetry}.
     */
    FOUNDATION(0),

    /**
     * Service subsystems — started after all {@link #FOUNDATION} subsystems have reached the
     * {@code RUNNING} state, in dependency-safe rounds within the phase.
     *
     * <p>Typical members: {@code security}, {@code persistence}, {@code transport},
     * {@code graph}.
     */
    SERVICES(1),

    /**
     * Runtime subsystems — started after all {@link #SERVICES} subsystems have reached the
     * {@code RUNNING} state, in dependency-safe rounds within the phase.
     *
     * <p>Typical members: {@code events}, {@code flow}.
     */
    RUNTIME(2);

    // -------------------------------------------------------------------------

    /** Numeric order; lower value = earlier phase. */
    private final int order;

    BootstrapPhase(int order) {
        this.order = order;
    }

    /**
     * Returns the numeric order of this phase (FOUNDATION=0, SERVICES=1, RUNTIME=2).
     *
     * @return phase order ≥ 0
     */
    public int order() {
        return order;
    }

    /**
     * Returns {@code true} if this phase must complete before {@code other} begins.
     *
     * @param other phase to compare against
     * @return {@code true} if {@code this.order < other.order}
     */
    public boolean isBefore(BootstrapPhase other) {
        return this.order < other.order;
    }
}

