/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
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
 * @since 0.5
 */
@Name("eu.exeris.kernel.persistence.TenantPoolReclaimed")
@Label("Tenant Pool Reclaimed")
@Description("Emitted when a per-tenant JDBC connection pool is reclaimed and closed")
@Category({"Exeris Kernel", "Persistence", "Memory"})
@StackTrace(false)
public final class PersistenceTenantPoolReclaimedEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(PersistenceTenantPoolReclaimedEvent.class);

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
     * Commits a tenant-pool-reclaimed event, or does nothing if the event type is disabled.
     *
     * <p>Guards on {@link EventType#isEnabled()} to avoid
     * allocation when JFR is off.
     *
     * @param providerId         stable provider identifier, e.g. {@code "postgres-community"}
     * @param tenantKey          tenant key of the pool that was reclaimed
     * @param reason             reclamation reason, e.g. {@code "idle_timeout"} or {@code "manual"}
     * @param idleDurationMs     how long the pool sat idle before reclamation, in milliseconds
     * @param remainingPoolCount total per-tenant pools remaining after this reclamation pass
     */
    public static void emit(String providerId, String tenantKey, String reason,
                           long idleDurationMs, int remainingPoolCount) {
        if (!EVENT_TYPE.isEnabled()) {
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
