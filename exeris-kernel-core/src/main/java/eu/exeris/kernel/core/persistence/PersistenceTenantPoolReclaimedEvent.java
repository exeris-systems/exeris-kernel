/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a per-tenant connection pool is reclaimed (closed).
 *
 * <h2>Memory Tracking</h2>
 * <p>Tracks lifecycle of per-tenant pools and idle time before reclamation.
 * Tuning the idle-TTL and reclamation cadence impacts memory footprint stability.
 *
 * @since 0.6.0
 */
@Name("eu.exeris.kernel.persistence.TenantPoolReclaimed")
@Label("Tenant Pool Reclaimed")
@Description("Emitted when a per-tenant JDBC connection pool is reclaimed and closed")
@Category({"Exeris Kernel", "Persistence", "Memory"})
@StackTrace(false)
public final class PersistenceTenantPoolReclaimedEvent extends Event {

    /** Provider tier identifier (e.g., {@code "postgres-community"}). */
    @Label("Provider ID")
    public String providerId;

    /** Tenant key that was reclaimed. */
    @Label("Tenant Key")
    public String tenantKey;

    /** Reason for reclamation (e.g., {@code "idle_timeout"}, {@code "manual"}). */
    @Label("Reason")
    public String reason;

    /** Idle duration (ms) before reclamation. */
    @Label("Idle Duration (ms)")
    public long idleDurationMs;

    /** Remaining per-tenant pools after reclamation. */
    @Label("Remaining Pools")
    public int remainingPoolCount;

    /**
     * Emit a tenant pool reclamation event.
     *
     * <p>Guards on {@link FlightRecorder#isInitialized()} to avoid
     * allocation when JFR is off.
     */
    public static void emit(String providerId, String tenantKey, String reason,
                           long idleDurationMs, int remainingPoolCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        PersistenceTenantPoolReclaimedEvent event = new PersistenceTenantPoolReclaimedEvent();
        event.providerId = providerId;
        event.tenantKey = tenantKey;
        event.reason = reason;
        event.idleDurationMs = idleDurationMs;
        event.remainingPoolCount = remainingPoolCount;
        event.commit();
    }
}
