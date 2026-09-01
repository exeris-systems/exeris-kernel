/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when the acceptor retries after {@code accept()} itself failed.
 *
 * <p>Distinct from {@code CommunityAcceptFault}, and the difference is which socket broke. A fault
 * is one accepted connection that could not be set up — the listener is healthy and the next
 * connection is unaffected. This is the <em>listener</em> failing to produce a connection at all,
 * which is how descriptor exhaustion surfaces, and it is a whole-process condition rather than a
 * per-connection one.
 *
 * <p>Carries the streak and the pause rather than only the fact: one retry is noise, and a streak
 * that keeps climbing while the pause sits at its ceiling is a process that is not recovering. That
 * distinction is the reason to record it, and it cannot be made from a single event.
 *
 * <p>Exception <b>class</b> only, never the message — matching {@code CommunityAcceptFault} and
 * {@code CommunityReactorDispatchFault}, because a message can carry text derived from a peer.
 *
 * @since 0.12.0
 */
@Name("eu.exeris.kernel.transport.CommunityAcceptRetry")
@Label("Community Accept Retry")
@Description("The listener failed to accept and the acceptor backed off instead of stopping")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
public final class CommunityAcceptRetryEvent extends Event {

    @Label("Bind Address")
    /* default */ String bindAddress;

    @Label("Port")
    /* default */ int port;

    @Label("Failure Class")
    /* default */ String failureClass;

    @Label("Consecutive Failures")
    /* default */ int consecutiveFailures;

    @Label("Backoff Millis")
    /* default */ long backoffMillis;

    /**
     * Records one retry.
     *
     * @param bindAddress         the listener's bind address
     * @param port                the listener's port
     * @param failureClass        class name of the {@code IOException} accept threw
     * @param consecutiveFailures how many accepts have failed in a row without one succeeding
     * @param backoffMillis       how long the acceptor is about to pause
     */
    public static void emit(String bindAddress, int port, String failureClass,
                            int consecutiveFailures, long backoffMillis) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityAcceptRetryEvent event = new CommunityAcceptRetryEvent();
        if (event.isEnabled()) {
            event.bindAddress = bindAddress;
            event.port = port;
            event.failureClass = failureClass;
            event.consecutiveFailures = consecutiveFailures;
            event.backoffMillis = backoffMillis;
            event.commit();
        }
    }
}
