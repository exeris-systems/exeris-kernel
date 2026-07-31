/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * JFR event for a connection that was accepted but could not be set up.
 *
 * <h2>Why this exists</h2>
 * <p>The accept loop catches {@code RuntimeException} around channel configuration, remote-address
 * resolution, stream construction and registration, and — before this event — discarded it entirely.
 * Anything that broke there produced the same externally-visible symptom as a healthy server under no
 * load: a client whose connection opened and died, and a server log with nothing in it.
 *
 * <h2>A different class from a refusal</h2>
 * <p>{@link CommunityConnectionRefusedEvent} records a <em>policy</em> decision at a known ceiling.
 * This records a <em>defect</em> — an allocator failure, a TLS engine that would not initialise, a
 * registry inconsistency. A recurring fault here is indistinguishable from an intermittent network
 * problem to anyone reading the client side, which is why it needs its own signal.
 *
 * <p>Deliberately absent from {@code TransportStats.totalRejected}: that field means work the engine
 * <em>declined</em>, and a setup that broke declined nothing. Folding the two together would leave an
 * operator reading a spike unable to tell a capacity problem from a bug — the one distinction that
 * changes what they do next.
 *
 * <p>Exception <b>class</b> only, no message — the same secret-safe shape as
 * {@link CommunityReactorDispatchFaultEvent}, since a message can carry request-derived text.
 *
 * @since 0.11.0
 */
@Name("eu.exeris.kernel.transport.CommunityAcceptFault")
@Label("Community Accept Fault")
@Description("A connection was accepted but failed during setup; the accept loop recovers and "
        + "continues, so without this event the failure leaves no trace")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityAcceptFaultEvent extends Event {

    @Label("Bind Address")
    /* default */ String bindAddress;

    @Label("Port")
    /* default */ int port;

    @Label("Fault Class")
    @Description("Fully-qualified class name of the exception; never its message")
    /* default */ String faultClass;

    @Label("Total Faults")
    @Description("Cumulative accept-setup faults on this carrier since start — one is an incident, "
            + "a slope is a defect")
    /* default */ long totalFaults;

    /* default */ static void emit(String bindAddress, int port, String faultClass, long totalFaults) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityAcceptFaultEvent event = new CommunityAcceptFaultEvent();
        if (event.isEnabled()) {
            event.bindAddress = bindAddress;
            event.port = port;
            event.faultClass = faultClass;
            event.totalFaults = totalFaults;
            event.commit();
        }
    }
}
