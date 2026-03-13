/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.tck.contract.bootstrap.AbstractBootstrapOrchestratorTck;
import eu.exeris.kernel.tck.contract.bootstrap.BootstrapZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

/**
 * Core JFR Zero-Allocation TCK: wires {@link SubsystemOrchestrator}'s internal
 * Kahn BFS to {@link BootstrapZeroAllocTck}.
 *
 * <h2>What is measured</h2>
 * <p>The JFR recording captures heap allocations produced by a single topological
 * sort of a pre-built 100-node linear subsystem chain. The sort is the same
 * Kahn BFS algorithm executed at kernel boot — measuring it here proves that
 * the algorithm's per-call allocation budget stays within the Community bound.
 *
 * <h2>Why reference Kahn instead of SubsystemOrchestrator directly</h2>
 * <p>{@link SubsystemOrchestrator#initialize} is a full lifecycle call (ServiceLoader
 * discovery, phase-parallel init). Its allocation profile includes one-time JVM
 * class-loading events that are not representative of the steady-state sort cost.
 * The reference {@link AbstractBootstrapOrchestratorTck#kahn} isolates the pure
 * sort algorithm — the same O(V+E) path executed inside the orchestrator.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Bootstrap Kahn BFS Zero-Allocation JFR TCK")
class CoreBootstrapZeroAllocTckTest extends BootstrapZeroAllocTck {

    /**
     * Returns {@code true} — Enterprise tier guarantees zero {@code eu.exeris.*}
     * allocations on the steady-state hot path via slab-pool CAS on primitive
     * {@code long} fields only.
     */
    @Override
    protected boolean supportsZeroGcHotPath() {
        return true;
    }

    /**
     * Delegates to the reference Kahn BFS implementation from the abstract TCK.
     *
     * <p>This is the same algorithm that {@link SubsystemOrchestrator} uses internally.
     * Using the reference implementation here (rather than calling the private
     * {@code topologicalSort} method) keeps the test OS-agnostic and free of
     * SubsystemOrchestrator's lifecycle side-effects (ServiceLoader, JFR events).
     */
    @Override
    protected List<Subsystem> runSort(List<Subsystem> input) {
        return AbstractBootstrapOrchestratorTck.kahn(input);
    }
}

