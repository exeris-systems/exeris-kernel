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
 * JFR event for an unrecoverable per-stream fault in the reactor select loop.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted once per dying connection, from the {@code NativeTcpReactor} select-loop
 * {@code catch (RuntimeException)} branch that triggers {@link NativeTcpCarrier#closeKeyStream}
 * abortive teardown. This is a failure-detection point (per {@code docs/subsystems/transport.md}
 * JFR-first guidance): a recurring fault here is the signature of a reactor spin, which is otherwise
 * invisible to JFR exception sampling when the cause is a zero-alloc, stack-trace-less sentinel
 * (e.g. unwrap-on-closed {@code TlsDecryptException}). The payload is the exception <em>class</em>
 * name only — no message — so it carries no request data.
 *
 * <p>Single-phase {@code commit()} on the reactor's platform thread; zero overhead when JFR is not
 * recording ({@link #isEnabled()} check). Not on the read/write ingress hot path.
 *
 * @since 0.9.0
 */
@Name("eu.exeris.kernel.transport.CommunityReactorDispatchFault")
@Label("Community Reactor Dispatch Fault")
@Description("Unrecoverable per-stream fault caught in the reactor select loop, triggering abortive teardown")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityReactorDispatchFaultEvent extends Event {

    @Label("Stream Id")
    /* default */ long streamId;

    @Label("Reactor Index")
    /* default */ int reactorIndex;

    @Label("Fault Class")
    /* default */ String faultClass;

    /* default */ static void emit(long streamId, int reactorIndex, String faultClass) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityReactorDispatchFaultEvent event = new CommunityReactorDispatchFaultEvent();
        if (event.isEnabled()) {
            event.streamId = streamId;
            event.reactorIndex = reactorIndex;
            event.faultClass = faultClass;
            event.commit();
        }
    }
}
