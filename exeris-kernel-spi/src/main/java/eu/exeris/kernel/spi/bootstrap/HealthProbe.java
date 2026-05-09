/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.bootstrap;

/**
 * Read-only contract for kernel readiness and liveness probes.
 *
 * <h2>Why an SPI interface</h2>
 * <p>HTTP health endpoints, sidecar reporters, and Enterprise observability adapters
 * all need to consume the kernel's readiness/liveness state without coupling to the
 * Core orchestration class that owns the state. {@code HealthProbe} provides the
 * minimal read-only surface; the Core orchestrator's monitor implements it.
 *
 * <h2>Probe semantics (Kubernetes-aligned)</h2>
 * <ul>
 *   <li><b>Liveness</b> answers <em>"is the kernel alive — should the container keep running?"</em>
 *       — {@code healthy=false} signals the runtime should be replaced.</li>
 *   <li><b>Readiness</b> answers <em>"should traffic be routed to this instance?"</em>
 *       — {@code healthy=false} signals the load balancer should drain this instance
 *       (e.g., during startup, shutdown, or while a required subsystem is degraded).</li>
 * </ul>
 *
 * <h2>The Wall</h2>
 * <p>This interface carries no Core types and no transport types — a probe consumer
 * may run inside a sidecar process, an out-of-band metric exporter, or an HTTP
 * handler with equal ease.
 *
 * @since 0.7.0
 */
public interface HealthProbe {

    /**
     * Returns the current readiness probe snapshot.
     *
     * @return non-null snapshot
     */
    ProbeSnapshot readiness();

    /**
     * Returns the current liveness probe snapshot.
     *
     * @return non-null snapshot
     */
    ProbeSnapshot liveness();

    /**
     * Immutable point-in-time snapshot of a probe outcome.
     *
     * <p>Carries a textual {@code status} suitable for human diagnostics
     * (e.g., {@code "READY"}, {@code "STARTING"}, {@code "FAILED"}, {@code "UP"},
     * {@code "DOWN"}) and a machine-checkable {@code healthy} flag that maps
     * directly to HTTP {@code 200} / {@code 503} responses.
     *
     * @param status  textual status; never {@code null}
     * @param healthy {@code true} if the probe is currently passing
     */
    record ProbeSnapshot(String status, boolean healthy) {
    }
}
