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
import org.junit.jupiter.api.DisplayName;

import java.util.List;

/**
 * Core-tier binding of {@link AbstractBootstrapOrchestratorTck} against the <b>real</b>
 * production topological sort ({@link SubsystemTopologicalSorter#sort(List)}) used by
 * {@link SubsystemOrchestrator}.
 *
 * <p>Distinct from {@code CoreBootstrapOrchestratorTckTest}, which mirrors the contract
 * with a self-contained reference Kahn BFS. This binding exercises the production sorter
 * directly so the abstract contract methods run against shipping code, not a copy.
 *
 * <h2>Exception adaptation</h2>
 * <p>{@link SubsystemTopologicalSorter#sort(List)} declares the checked
 * {@link SubsystemOrchestrator.BootstrapException} (unknown / duplicate dependency) while the
 * abstract {@link #runSort(List)} template is unchecked. The unknown-dependency contract case
 * asserts {@link RuntimeException}, so the checked failure is rethrown wrapped — the cycle
 * exception ({@link eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException},
 * itself unchecked) is allowed to propagate unwrapped to satisfy the cycle-detection cases.
 *
 * @since 0.9.0
 */
@DisplayName("Core: BootstrapOrchestrator TCK — real SubsystemTopologicalSorter")
class CoreBootstrapOrchestratorRealSortTckTest extends AbstractBootstrapOrchestratorTck {

    @Override
    protected List<Subsystem> runSort(List<Subsystem> input) {
        return BootstrapSortAdapter.runSort(input);
    }
}
