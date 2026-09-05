/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * JFR event emitted by {@link PersistenceBootstrap} when a provider is selected.
 *
 * <h2>JFR-First Contract</h2>
 * <p>The bootstrapper MUST emit this event so that JFR recordings contain a clear
 * record of which provider (Community/Enterprise) was activated and at what priority.
 * SRE tooling uses this to confirm Enterprise was chosen over Community in production.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.persistence.BootstrapSelected")
@Label("Persistence Bootstrap — Provider Selected")
@Category({"Exeris Kernel", "Persistence"})
@Description("Emitted once during kernel startup when the winning PersistenceProvider is chosen.")
@StackTrace(false)
final class PersistenceBootstrapSelectedEvent extends Event {

    @Label("Provider Class")
    @Description("Fully qualified class name of the selected PersistenceProvider")
    /* default */ String providerClass;

    @Label("Priority")
    @Description("ServiceLoader priority of the selected provider (100=Enterprise, 0=Community)")
    /* default */ int priority;

    @Label("Provider Name")
    @Description("Human-readable name of the selected PersistenceProvider")
    /* default */ String providerName;

    @Label("Interceptor Count")
    @Description("Number of ConnectionInterceptors registered at startup")
    /* default */ int interceptorCount;

    /**
     * Emits the bootstrap selected event.
     *
     * @param providerClass    FQN of the selected {@link eu.exeris.kernel.spi.persistence.PersistenceProvider}
     * @param priority         provider priority
     * @param providerName     human-readable provider name reported by the selected provider
     * @param interceptorCount number of registered interceptors
     */
    /* default */ static void emit(String providerClass, int priority,
                     String providerName, int interceptorCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        PersistenceBootstrapSelectedEvent event = new PersistenceBootstrapSelectedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.providerClass     = providerClass;
        event.priority          = priority;
        event.providerName      = providerName;
        event.interceptorCount  = interceptorCount;
        event.commit();
    }
}
