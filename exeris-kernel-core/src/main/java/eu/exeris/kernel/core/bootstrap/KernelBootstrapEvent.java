/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when the kernel bootstrap sequence completes.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every critical lifecycle event — including bootstrap — MUST emit a JFR event.
 * This is the primary observability hook for production deployments.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.bootstrap.KernelBootstrap")
@Label("Kernel Bootstrap Complete")
@Description("Emitted when KernelBootstrap has resolved all providers and is ready to start subsystems")
@Category({"Exeris Kernel", "Bootstrap"})
@StackTrace(false)
final class KernelBootstrapEvent extends Event {

    /* default */ @Label("Memory Provider")   String  memoryProvider;
    /* default */ @Label("Crypto Provider")   String  cryptoProvider;
    /* default */ @Label("Telemetry Provider") String telemetryProvider;
    /* default */ @Label("QUIC Enabled")      boolean quicEnabled;


    /* default */ static void emit(String memory, String crypto, String telemetry, boolean quic) {
        KernelBootstrapEvent event = new KernelBootstrapEvent();
        if (event.isEnabled()) {
            event.memoryProvider    = memory;
            event.cryptoProvider    = crypto;
            event.telemetryProvider = telemetry;
            event.quicEnabled       = quic;
            event.commit();
        }
    }
}
