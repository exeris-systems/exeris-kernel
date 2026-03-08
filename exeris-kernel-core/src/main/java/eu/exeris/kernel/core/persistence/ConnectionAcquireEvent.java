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
 * JFR event emitted each time a connection is acquired from the persistence pool.
 *
 * <h2>Hot-Path Guard</h2>
 * <p>The emit method guards on {@link FlightRecorder#isInitialized()} before
 * allocating the event object — when JFR recording is inactive the cost is a
 * single branch miss with zero heap allocation.
 * The event is intentionally {@link StackTrace @StackTrace(false)} to eliminate
 * stack-walk overhead on the connection-acquire hot path.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.persistence.ConnectionAcquire")
@Label("Persistence Connection Acquire")
@Description("Emitted when a PersistenceConnection is checked out from the connection pool")
@Category({"Exeris Kernel", "Persistence"})
@StackTrace(false)
public final class ConnectionAcquireEvent extends Event {

    /** Provider tier identifier (e.g., {@code "postgres-community"}). */
    @Label("Provider ID")
    public String providerId;

    /** Tenant isolation key, or {@code "shared"} for the default pool. */
    @Label("Tenant Key")
    public String tenantKey;

    /** Whether the connection was served from pool cache ({@code true}) or newly created. */
    @Label("From Pool")
    public boolean fromPool;

    /**
     * Emits the event if JFR recording is active. No-op otherwise.
     *
     * @param providerId  stable provider identifier
     * @param tenantKey   tenant isolation key, or {@code "shared"}
     * @param fromPool    {@code true} if connection was recycled from pool
     */
    public static void emit(String providerId, String tenantKey, boolean fromPool) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ConnectionAcquireEvent event = new ConnectionAcquireEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.providerId = providerId;
        event.tenantKey  = tenantKey;
        event.fromPool   = fromPool;
        event.commit();
    }
}
