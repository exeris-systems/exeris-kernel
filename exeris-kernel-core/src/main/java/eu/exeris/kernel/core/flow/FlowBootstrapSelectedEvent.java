/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("eu.exeris.kernel.flow.BootstrapSelected")
@Label("Flow Bootstrap - Provider Selected")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted once during kernel startup when the winning FlowProvider is chosen.")
@StackTrace(false)
final class FlowBootstrapSelectedEvent extends Event {

    @Label("Provider Class")
    @Description("Fully qualified class name of the selected FlowProvider")
    /* default */ String providerClass;

    @Label("Priority")
    @Description("ServiceLoader priority of the selected provider")
    /* default */ int priority;

    @Label("Provider ID")
    @Description("Stable provider identifier from FlowProvider.providerId()")
    /* default */ String providerId;

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    /* default */ static void emit(String providerClass, int priority,
                                   String providerId, String engineName) {
        FlowBootstrapSelectedEvent event = new FlowBootstrapSelectedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.providerClass = providerClass;
        event.priority = priority;
        event.providerId = providerId;
        event.engineName = engineName;
        event.commit();
    }
}