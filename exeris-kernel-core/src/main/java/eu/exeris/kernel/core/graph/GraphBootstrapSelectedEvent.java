/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted by {@link GraphBootstrap} when a provider is selected.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Emitted once at kernel startup so JFR recordings contain a clear record of
 * which graph provider (Community/Enterprise) was activated and at what priority.
 * SRE tooling uses this to confirm Enterprise was chosen over Community in production.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.graph.BootstrapSelected")
@Label("Graph Bootstrap — Provider Selected")
@Category({"Exeris Kernel", "Graph"})
@Description("Emitted once during kernel startup when the winning GraphProvider is chosen.")
@StackTrace(false)
final class GraphBootstrapSelectedEvent extends Event {

    @Label("Provider Class")
    @Description("Fully qualified class name of the selected GraphProvider")
    /* default */ String providerClass;

    @Label("Priority")
    @Description("ServiceLoader priority of the selected provider (100=Enterprise, 0=Community)")
    /* default */ int priority;

    @Label("Provider ID")
    @Description("Stable provider identifier from GraphProvider.providerId()")
    /* default */ String providerId;

    @Label("Engine Name")
    @Description("Human-readable engine name from GraphEngine.engineName()")
    /* default */ String engineName;

    /* default */ static void emit(String providerClass, int priority,
                                   String providerId, String engineName) {
        GraphBootstrapSelectedEvent event = BootstrapJfrEvents.beginIfEnabled(
                new GraphBootstrapSelectedEvent());
        if (event == null) {
            return;
        }
        event.providerClass = providerClass;
        event.priority      = priority;
        event.providerId    = providerId;
        event.engineName    = engineName;
        event.commit();
    }
}

