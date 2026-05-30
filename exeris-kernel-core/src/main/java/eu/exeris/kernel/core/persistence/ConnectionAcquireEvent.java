/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.core.telemetry.JfrCommitGate;

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
 * <h2>Usage — single-phase commit</h2>
 * <pre>{@code
 * long startNs = System.nanoTime();
 * PersistenceConnection conn = engine.openConnection(ctx);   // ← blocking checkout (may park a VT)
 * ConnectionAcquireEvent.commitAcquire(providerId, tenantKey, fromPool, startNs);
 * }</pre>
 *
 * <h2>Virtual-thread safety — single-phase <em>and</em> off-thread commit</h2>
 * <p>The pool checkout this event measures is a <em>blocking</em> operation. On a
 * virtual thread a blocking park unmounts the carrier, and the thread may remount
 * on a different carrier when the connection is handed out. JFR's {@code EventWriter}
 * is carrier-bound, so a {@code commit()} on the remounted carrier can flush a stale
 * {@code JfrBuffer}, crashing the JVM in {@code JfrStorage::flush_regular_buffer}
 * (observed on JDK 26 GA, build 26+35).
 * <p>Single-phase construction (the event is built entirely <em>after</em> the checkout
 * returns, latency carried in {@link #acquireLatencyNs} rather than JFR begin/end) is
 * <em>necessary but not sufficient</em>: the staleness is established by the earlier
 * park/remount, not by a begin/commit straddle, so this event crashed in production
 * despite being single-phase. The actual {@code commit()} is therefore handed to a
 * dedicated platform thread via {@link JfrCommitGate} / {@code JfrEventCommitter};
 * the field-populated event is constructed on the caller (safe — pure heap writes) and
 * committed off the request virtual thread.
 *
 * <h2>Hot-Path Guard</h2>
 * <p>{@link #commitAcquire} guards on {@link FlightRecorder#isInitialized()} and
 * {@link Event#isEnabled()} before allocating — when JFR recording is inactive it
 * returns with zero heap allocation.
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
     * Commits a connection-acquire event with the measured acquire metadata.
     *
     * <p>Must be called <em>after</em> the pool checkout returns. The event is both
     * allocated and committed here so it is never held across the blocking checkout
     * — see the virtual-thread safety note in the class Javadoc.
     * Returns with zero heap allocation if JFR recording is inactive (cold path).
     *
     * @param providerId  stable provider identifier
     * @param tenantKey   tenant isolation key, or {@code "shared"}
     * @param fromPool    {@code true} if connection was recycled from the pool
     * @param startNs     {@link System#nanoTime()} captured before the checkout call
     */
    public static void commitAcquire(String providerId,
                                     String tenantKey,
                                     boolean fromPool,
                                     long startNs) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ConnectionAcquireEvent event = new ConnectionAcquireEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerId       = providerId;
        event.tenantKey        = tenantKey;
        event.fromPool         = fromPool;
        event.acquireLatencyNs = System.nanoTime() - startNs;
        // VT-JFR safety: commit off the request virtual thread (see class Javadoc / JfrCommitGate).
        // Inline commit only as a fallback when no committer is installed (tests / pre-bootstrap).
        if (!JfrCommitGate.offer(event)) {
            event.commit();
        }
    }
}
