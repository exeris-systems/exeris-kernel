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

import java.util.List;

/**
 * Shared test adapter exposing the real production topological sort
 * ({@link SubsystemTopologicalSorter#sort(List)}) to the bootstrap TCK bindings as the
 * unchecked {@code runSort} template the contract expects.
 *
 * <h2>Exception adaptation</h2>
 * <p>{@link SubsystemTopologicalSorter#sort(List)} declares the checked
 * {@link SubsystemOrchestrator.BootstrapException} (unknown / duplicate dependency) while the
 * abstract {@code runSort} template is unchecked. The unknown-dependency contract case asserts
 * {@link RuntimeException}, so the checked failure is rethrown wrapped; the cycle exception
 * ({@link eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException}, itself
 * unchecked) propagates unwrapped to satisfy the cycle-detection cases.
 *
 * @since 0.9.0
 */
final class BootstrapSortAdapter {

    private BootstrapSortAdapter() {
        // utility class
    }

    /* default */ static List<Subsystem> runSort(List<Subsystem> input) {
        try {
            return SubsystemTopologicalSorter.sort(input);
        } catch (SubsystemOrchestrator.BootstrapException ex) {
            // Unknown/duplicate dependency — surface as unchecked to satisfy the contract,
            // which asserts a RuntimeException for unresolved dependencies.
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }
}
