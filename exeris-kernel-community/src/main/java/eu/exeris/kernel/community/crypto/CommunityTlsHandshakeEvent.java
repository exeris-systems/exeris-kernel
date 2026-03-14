/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.crypto;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event for Community-tier TLS handshake lifecycle.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted on every {@link CommunityTlsEngine#beginHandshake} call.
 * Zero overhead when JFR is not recording ({@link #isEnabled()} check).
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.crypto.CommunityTlsHandshake")
@Label("Community TLS Handshake")
@Description("Emitted on each SSL_do_handshake invocation in the Community TLS engine")
@Category({"Exeris Kernel", "Crypto"})
@StackTrace(false)
final class CommunityTlsHandshakeEvent extends Event {

    @Label("Handshake Completed")
    /* default */ boolean complete;

    @Label("OpenSSL Error Code")
    /* default */ int opensslError;

    /* default */ static void emit(boolean complete, int opensslError) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityTlsHandshakeEvent event = new CommunityTlsHandshakeEvent();
        if (event.isEnabled()) {
            event.complete = complete;
            event.opensslError = opensslError;
            event.commit();
        }
    }
}

