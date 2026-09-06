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
 * JFR event emitted when a per-tenant connection pool is created.
 *
 * <h2>Memory Tracking</h2>
 * <p>Used to correlate per-tenant pool proliferation with memory growth. The number of
 * concurrent per-tenant pools is bounded by {@code PersistenceConfig#maxTenantPools()}; a
 * creation rate that keeps climbing toward that ceiling without matching
 * {@link PersistenceTenantPoolReclaimedEvent} activity is the proliferation this event exists
 * to surface.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.persistence.TenantPoolCreated")
@Label("Tenant Pool Created")
@Description("Emitted when a per-tenant JDBC connection pool is created")
@Category({"Exeris Kernel", "Persistence", "Memory"})
@StackTrace(false)
public final class PersistenceTenantPoolCreatedEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(PersistenceTenantPoolCreatedEvent.class);

    /** Provider tier identifier (e.g., {@code "postgres-community"}). */
    @Label("Provider ID")
    public String providerId;

    /** Tenant key or schema name used as pool identifier. */
    @Label("Tenant Key")
    public String tenantKey;

    /** Maximum connections in this per-tenant pool. */
    @Label("Max Connections")
    public int maxConnections;

    /** Minimum idle connections maintained for this tenant. */
    @Label("Min Idle")
    public int minIdle;

    /** Total per-tenant pools active at time of creation. */
    @Label("Pool Count")
    public int currentPoolCount;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public PersistenceTenantPoolCreatedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Commits a tenant-pool-created event, or does nothing if the event type is disabled.
     *
     * <p>Guards on {@link EventType#isEnabled()} to avoid
     * allocation when JFR is off.
     *
     * @param providerId       stable provider identifier, e.g. {@code "postgres-community"}
     * @param tenantKey        tenant key or schema name used as the pool identifier
     * @param maxConnections   maximum connections configured for the new pool
     * @param minIdle          minimum idle connections configured for the new pool
     * @param currentPoolCount total per-tenant pools active immediately after this pool's creation
     */
    public static void emit(String providerId, String tenantKey, int maxConnections,
                           int minIdle, int currentPoolCount) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        PersistenceTenantPoolCreatedEvent event = new PersistenceTenantPoolCreatedEvent();
        event.providerId = providerId;
        event.tenantKey = tenantKey;
        event.maxConnections = maxConnections;
        event.minIdle = minIdle;
        event.currentPoolCount = currentPoolCount;
        event.commit();
    }
}
