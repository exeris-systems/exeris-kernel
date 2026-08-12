/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.scheduling;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event recording which {@code JobSchedulerProvider} bootstrap selected (ADR-057 §1).
 *
 * <p>Which provider won a ServiceLoader race is invisible after the fact and is the first thing
 * anyone asks when a scheduler behaves unexpectedly, so it is recorded at selection rather than
 * reconstructed from behaviour. Single-phase commit on the bootstrap thread.
 *
 * @since 0.11.0
 */
@Name("eu.exeris.kernel.scheduling.SchedulingBootstrapSelected")
@Label("Scheduling Bootstrap Selected")
@Description("Records the JobSchedulerProvider chosen by ServiceLoader ranking at bootstrap")
@Category({"Exeris Kernel", "Scheduling"})
@StackTrace(false)
public final class SchedulingBootstrapSelectedEvent extends Event {

    @Label("Provider Class")
    /* default */ String providerClass;

    @Label("Provider Id")
    /* default */ String providerId;

    @Label("Priority")
    /* default */ int priority;

    @Label("Scheduler Name")
    /* default */ String schedulerName;

    /**
     * Records the selection.
     *
     * @param providerClass implementation class name of the winning provider
     * @param providerId    its stable provider id
     * @param priority      the priority it won with
     * @param schedulerName the configured scheduler name
     */
    public static void emit(String providerClass, String providerId, int priority,
                            String schedulerName) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        SchedulingBootstrapSelectedEvent event = new SchedulingBootstrapSelectedEvent();
        if (event.isEnabled()) {
            event.providerClass = providerClass;
            event.providerId = providerId;
            event.priority = priority;
            event.schedulerName = schedulerName;
            event.commit();
        }
    }
}
