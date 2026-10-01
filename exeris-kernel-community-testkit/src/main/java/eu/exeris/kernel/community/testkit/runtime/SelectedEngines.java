/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.runtime;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Which engines a fixture asked for, and the ones the boot actually bound.
 *
 * <p>Split out of {@link KernelBootstrapRuntimeFixture} because they are two concepts and PMD noticed
 * before a reader did: holding a kernel boot open on a thread is one job, and knowing which engines the
 * selection entitles a caller to is another. The fixture keeps the lifecycle; this keeps the slots.
 *
 * <p>Every accessor refuses a subsystem that was not selected by naming it, rather than returning
 * {@code null} — a fixture that hands back nothing is indistinguishable from one whose boot half
 * failed, and that is the confusion these messages exist to prevent.
 */
final class SelectedEngines {

    /* default */ static final String EVENTS = "events";
    /* default */ static final String FLOW = "flow";

    private final Set<String> selected;
    private final AtomicReference<EventEngine> eventEngineSlot = new AtomicReference<>();
    private final AtomicReference<FlowEngine> flowEngineSlot = new AtomicReference<>();
    private final AtomicReference<PersistenceEngine> persistenceEngineSlot = new AtomicReference<>();

    /* default */ SelectedEngines(Set<String> selected) {
        this.selected = Set.copyOf(selected);
    }

    /** Reads the bound engines out of {@link KernelProviders}; called inside the boot scope. */
    /* default */ void captureFromKernelScope() {
        persistenceEngineSlot.set(KernelProviders.persistenceEngine());
        if (selected.contains(EVENTS)) {
            eventEngineSlot.set(KernelProviders.eventEngine());
        }
        if (selected.contains(FLOW)) {
            flowEngineSlot.set(KernelProviders.flowEngine());
        }
    }

    /* default */ EventEngine eventEngine() {
        return require(eventEngineSlot.get(), EVENTS);
    }

    /* default */ FlowEngine flowEngine() {
        return require(flowEngineSlot.get(), FLOW);
    }

    /* default */ PersistenceEngine persistenceEngine() {
        PersistenceEngine current = persistenceEngineSlot.get();
        if (current == null) {
            throw new IllegalStateException(
                    "Persistence engine is not bound — every subsystem this fixture selects declares "
                            + "dependsOn(\"persistence\"), so this means the boot did not complete");
        }
        return current;
    }

    /* default */ boolean isBound() {
        return persistenceEngineSlot.get() != null;
    }

    /* default */ void release() {
        eventEngineSlot.set(null);
        flowEngineSlot.set(null);
        persistenceEngineSlot.set(null);
    }

    private <T> T require(T engine, String subsystem) {
        if (engine == null) {
            throw new IllegalStateException(
                    "Subsystem '" + subsystem + "' was not selected for this fixture; selected: "
                            + selected);
        }
        return engine;
    }
}
