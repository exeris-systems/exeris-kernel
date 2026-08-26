/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
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
        EventBootstrapSelectedEvent event = BootstrapJfrEvents.beginIfEnabled(
                new EventBootstrapSelectedEvent());
        if (event == null) {
            return;
        }
        event.providerClass = providerClass;
        event.priority = priority;
        event.providerId = providerId;
        event.engineName = engineName;
        event.commit();
    }
}