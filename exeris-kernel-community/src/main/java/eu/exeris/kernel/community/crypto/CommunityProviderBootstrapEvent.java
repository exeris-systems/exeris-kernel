/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * JFR event emitted when the Community Crypto provider finishes bootstrap.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.crypto.CommunityProviderBootstrap")
@Label("Community Crypto Provider Bootstrap")
@Description("Emitted when CommunityKernelCryptoProvider successfully creates a TlsEngine")
@Category({"Exeris Kernel", "Crypto"})
@StackTrace(false)
final class CommunityProviderBootstrapEvent extends Event {

    @Label("Provider Name")
    /* default */ String providerName;

    @Label("Bootstrap Duration (ns)")
    /* default */ long durationNs;

    /* default */ static void emit(String providerName, long durationNs) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityProviderBootstrapEvent event = new CommunityProviderBootstrapEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerName = providerName;
        event.durationNs = durationNs;
        event.commit();
    }
}

