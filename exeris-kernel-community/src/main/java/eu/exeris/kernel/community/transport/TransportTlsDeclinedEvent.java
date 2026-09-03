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
 * A listener held valid TLS material and bound plaintext because the opt-out said so.
 *
 * <p>The other outcomes of that decision announce themselves. A listener with no material was never
 * going to serve TLS, and a half-configured one fails the boot outright. This one is the case that
 * looks like nothing: the certificate and key are present and correct, the socket comes up, and it
 * is plaintext — indistinguishable from any other plaintext socket to anything outside the process.
 *
 * <p>The knob is legitimate; terminating TLS at a sidecar is an ordinary deployment. What is not
 * acceptable is that it should be undetectable, so the decision leaves a trail.
 *
 * @since 0.12.0
 */
@Name("eu.exeris.kernel.transport.TransportTlsDeclined")
@Label("Transport TLS Declined")
@Category({"Exeris Kernel", "Transport"})
@Description("A listener configured with certificate and key bound plaintext because the TLS opt-out was set")
@StackTrace(false)
final class TransportTlsDeclinedEvent extends Event {

    @Label("Transport Mode")
    @Description("SERVER or DUAL — the client path carries no material and is not reported here")
    /* default */ String transportMode;

    @Label("Opt-out Property")
    @Description("The system property that declined it, so the operator can find what to unset")
    /* default */ String property;

    /* default */ static void emit(String transportMode, String property) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransportTlsDeclinedEvent event = new TransportTlsDeclinedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.transportMode = transportMode;
        event.property = property;
        event.commit();
    }
}
