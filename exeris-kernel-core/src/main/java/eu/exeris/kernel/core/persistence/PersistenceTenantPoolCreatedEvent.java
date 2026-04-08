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
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a per-tenant connection pool is created.
 *
 * <h2>Memory Tracking</h2>
 * <p>Used to correlate per-tenant pool proliferation with memory growth.
 * Peak concurrent tenant pool count should remain ≤3 under typical load.
 *
 * @since 0.6.0
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
     * Emit a tenant pool creation event.
     *
     * <p>Guards on {@link EventType#isEnabled()} to avoid
     * allocation when JFR is off.
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
