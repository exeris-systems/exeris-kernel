/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("eu.exeris.kernel.events.BootstrapSelected")
@Label("Events Bootstrap - Provider Selected")
@Category({"Exeris Kernel", "Events"})
@Description("Emitted once during kernel startup when the winning EventProvider is chosen.")
@StackTrace(false)
final class EventBootstrapSelectedEvent extends Event {

    @Label("Provider Class")
    @Description("Fully qualified class name of the selected EventProvider")
    /* default */ String providerClass;

    @Label("Priority")
    @Description("ServiceLoader priority of the selected provider")
    /* default */ int priority;

    @Label("Provider ID")
    @Description("Stable provider identifier from EventProvider.providerId()")
    /* default */ String providerId;

    @Label("Engine Name")
    @Description("Human-readable engine name from EventEngineConfig.engineName()")
    /* default */ String engineName;

    /* default */ static void emit(String providerClass, int priority,
                                   String providerId, String engineName) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        EventBootstrapSelectedEvent event = new EventBootstrapSelectedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerClass = providerClass;
        event.priority = priority;
        event.providerId = providerId;
        event.engineName = engineName;
        event.commit();
    }
}