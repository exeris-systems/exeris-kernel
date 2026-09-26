/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emitted once, during kernel startup, when {@link FlowBootstrap#loadWithProvider} selects the
 * {@link eu.exeris.kernel.spi.flow.FlowProvider} this JVM's flow subsystem binds to.
 *
 * <p>Carries the ServiceLoader-selection outcome — the winning provider's class, priority and
 * stable identifier, plus the engine name it was configured with — so an operator can confirm
 * which flow binding started without inferring it from which JAR happens to be on the classpath.
 */
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

    /**
     * Begins, populates and commits this event, unless Flight Recorder is not initialized or the
     * event type is disabled, in which case this is a no-op.
     *
     * @param providerClass fully qualified class name of the selected {@link
     *                      eu.exeris.kernel.spi.flow.FlowProvider}
     * @param priority      the provider's {@link eu.exeris.kernel.spi.flow.FlowProvider#priority()}
     * @param providerId    the provider's stable {@link
     *                      eu.exeris.kernel.spi.flow.FlowProvider#providerId()}
     * @param engineName    the configured {@link eu.exeris.kernel.spi.flow.FlowEngineConfig#engineName()}
     */
    /* default */ static void emit(String providerClass, int priority,
                                   String providerId, String engineName) {
        FlowBootstrapSelectedEvent event = BootstrapJfrEvents.beginIfEnabled(
                new FlowBootstrapSelectedEvent());
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