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
 * <h2>Usage — two-phase API</h2>
 * <pre>{@code
 * long startNs = System.nanoTime();
 * ConnectionAcquireEvent evt = ConnectionAcquireEvent.beginAcquire();
 * PersistenceConnection conn = engine.openConnection(ctx);   // ← measured region
 * ConnectionAcquireEvent.endAcquire(evt, providerId, tenantKey, fromPool, startNs);
 * }</pre>
 *
 * <h2>Hot-Path Guard</h2>
 * <p>Both phases guard on {@link FlightRecorder#isInitialized()} and
 * {@link Event#isEnabled()} before allocating — when JFR recording is inactive
 * the cost is a single branch miss with zero heap allocation.
 * {@link StackTrace @StackTrace(false)} eliminates stack-walk overhead on the
 * connection-acquire hot path.
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

    /** Nanoseconds elapsed waiting for the connection to be handed out. */
    @Label("Acquire Latency (ns)")
    public long acquireLatencyNs;

    /**
     * Begins measuring a connection-acquire event.
     *
     * <p>Must be called <em>before</em> the blocking pool checkout.
     * Returns {@code null} if JFR recording is inactive (zero allocation on cold path).
     *
     * @return active event instance, or {@code null} if JFR is inactive
     */
    public static ConnectionAcquireEvent beginAcquire() {
        if (!FlightRecorder.isInitialized()) {
            return null;
        }
        ConnectionAcquireEvent event = new ConnectionAcquireEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }

    /**
     * Completes and commits the event with acquire metadata.
     *
     * <p>Must be called <em>after</em> the pool checkout returns.
     * No-op if {@code event} is {@code null} (JFR was inactive at begin time).
     *
     * @param event       event started by {@link #beginAcquire()}, or {@code null}
     * @param providerId  stable provider identifier
     * @param tenantKey   tenant isolation key, or {@code "shared"}
     * @param fromPool    {@code true} if connection was recycled from the pool
     * @param startNs     {@link System#nanoTime()} captured before the checkout call
     */
    public static void endAcquire(ConnectionAcquireEvent event,
                                   String providerId,
                                   String tenantKey,
                                   boolean fromPool,
                                   long startNs) {
        if (event == null) {
            return;
        }
        event.providerId       = providerId;
        event.tenantKey        = tenantKey;
        event.fromPool         = fromPool;
        event.acquireLatencyNs = System.nanoTime() - startNs;
        event.commit();
    }
}
