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
 * JFR event emitted when a pooled connection is returned, carrying how long it was held.
 *
 * <h2>Why this exists</h2>
 * <p>{@link ConnectionAcquireEvent} measures the time spent <em>getting</em> a connection.
 * Nothing measured the time it was <em>kept</em>, except indirectly for connections owned by an
 * HTTP request session, whose {@code ACQUIRE}/{@code RELEASE} pair bounds the hold. That
 * asymmetry is not neutral: {@code RequestSessionLifecycleEvent} is emitted from exactly one
 * class, {@code PersistenceSessionBox}, so a caller that never binds a request session —
 * a flow step on a bare virtual thread, a scheduled job, a migration — produced an acquire
 * event and then nothing at all. Hold time for those callers was unmeasurable, and a reading
 * that "no session events appeared on flow threads" says only that the instrument does not
 * reach there.
 *
 * <p>This event sits at the pool return instead of at one caller, so every acquire has a
 * matching hold regardless of who asked. {@link #withinRequestScope} and
 * {@link #acquiredOnVirtualThread} are what let a reader apportion pool residency between
 * request-scoped and background work rather than assume it.
 *
 * <h2>Naming, precisely</h2>
 * <p>{@link #withinRequestScope} records whether a request session was <em>in scope on the
 * acquiring thread</em> — not whether that session owns the connection. A deliberate
 * {@code openPhysical()} inside a request reads {@code true}, which is correct for
 * apportionment (it is request-thread residency) and would be wrong for an ownership claim.
 * Do not read this field as ownership.
 *
 * <h2>Virtual-thread safety</h2>
 * <p>A hold spans arbitrary caller work, so the release almost always happens after at least
 * one park/remount on a virtual thread — the exact condition that makes a carrier-bound
 * {@code EventWriter} flush a stale buffer and crash the JVM. This event therefore follows
 * {@link ConnectionAcquireEvent}'s discipline exactly: it is built in a single phase at
 * release (duration carried in {@link #holdDurationNs} rather than JFR begin/end, so nothing
 * is held across the hold), and the {@code commit()} is handed to a dedicated platform thread
 * via {@link JfrCommitGate}. Neither half is sufficient alone.
 *
 * <h2>Hot-Path Guard</h2>
 * <p>{@link #commitHold} guards on {@link FlightRecorder#isInitialized()} and
 * {@link Event#isEnabled()} before allocating, so an inactive recording costs no heap.
 * {@link StackTrace @StackTrace(false)} keeps the release path off the stack-walker.
 *
 * @since 0.12.0
 */
@Name("eu.exeris.kernel.persistence.ConnectionHold")
@Label("Persistence Connection Hold")
@Description("Emitted when a pooled PersistenceConnection is returned, carrying how long it was "
        + "held and whether it was acquired inside an HTTP request scope")
@Category({"Exeris Kernel", "Persistence"})
@StackTrace(false)
public final class ConnectionHoldEvent extends Event {

    /** Provider tier identifier (e.g., {@code "postgres-community"}). */
    @Label("Provider ID")
    public String providerId;

    /** Tenant isolation key, or {@code "shared"} for the default pool. */
    @Label("Tenant Key")
    public String tenantKey;

    /** Nanoseconds between the connection being handed out and returned to the pool. */
    @Label("Hold Duration (ns)")
    public long holdDurationNs;

    /**
     * Whether a request session was in scope on the acquiring thread. Not an ownership claim —
     * see the naming note in the class Javadoc.
     */
    @Label("Within Request Scope")
    public boolean withinRequestScope;

    /** Whether the acquiring thread was virtual. Background work in this kernel runs virtual. */
    @Label("Acquired On Virtual Thread")
    public boolean acquiredOnVirtualThread;

    /**
     * Whether the connection was <em>discarded</em> rather than returned to the pool. A pool
     * eviction reaches the same {@code close()} as a healthy return, so without this the two are
     * indistinguishable — and they are not the same event. The eviction path this kernel actually
     * takes is {@code discardAfterInterceptorFailure}: a {@code ConnectionInterceptor} threw, so the
     * connection is thrown away because the RLS session keys could not be published. Reported as an
     * ordinary hold, a burst of those reads as a burst of very short healthy holds — that is, it
     * reads as the opposite of what it is.
     */
    @Label("Discarded")
    public boolean discarded;

    /**
     * Commits a connection-hold event. Must be called when the connection goes back to the pool.
     *
     * <p>Both allocation and commit happen here, so nothing is held across the hold itself —
     * see the virtual-thread safety note in the class Javadoc. Returns with zero heap allocation
     * if JFR recording is inactive.
     *
     * @param providerId              stable provider identifier
     * @param tenantKey               tenant isolation key, or {@code "shared"}
     * @param withinRequestScope      whether a request session was bound when the connection was taken
     * @param acquiredOnVirtualThread whether the acquiring thread was virtual
     * @param discarded               whether the connection was evicted rather than returned
     * @param acquiredAtNs            {@link System#nanoTime()} captured when the connection was handed out
     */
    public static void commitHold(String providerId,
                                  String tenantKey,
                                  boolean withinRequestScope,
                                  boolean acquiredOnVirtualThread,
                                  boolean discarded,
                                  long acquiredAtNs) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ConnectionHoldEvent event = new ConnectionHoldEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerId              = providerId;
        event.tenantKey               = tenantKey;
        event.holdDurationNs          = System.nanoTime() - acquiredAtNs;
        event.withinRequestScope      = withinRequestScope;
        event.acquiredOnVirtualThread = acquiredOnVirtualThread;
        event.discarded               = discarded;
        // VT-JFR safety: commit off the caller's virtual thread (see class Javadoc / JfrCommitGate).
        // Inline commit only as a fallback when no committer is installed (tests / pre-bootstrap).
        if (!JfrCommitGate.offer(event)) {
            event.commit();
        }
    }
}
