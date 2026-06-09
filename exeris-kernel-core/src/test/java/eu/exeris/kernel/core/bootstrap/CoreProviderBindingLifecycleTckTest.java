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
import eu.exeris.kernel.tck.contract.bootstrap.AbstractProviderBindingLifecycleTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

/**
 * Core-tier binding of {@link AbstractProviderBindingLifecycleTck}.
 *
 * <p>The provider-binding-lifecycle contract is implementation-blind — its two cases use
 * SPI-only {@link Subsystem} stubs and {@link ScopedValue.Carrier} composition. The base
 * extends {@link eu.exeris.kernel.tck.contract.bootstrap.AbstractBootstrapOrchestratorTck},
 * so the inherited topological-sort cases are run here against the real production
 * {@link SubsystemTopologicalSorter#sort(List)} as well.
 *
 * @since 0.9.0
 */
@DisplayName("Core: ProviderBindingLifecycle TCK — state symmetry + real sort")
class CoreProviderBindingLifecycleTckTest extends AbstractProviderBindingLifecycleTck {

    @Override
    protected List<Subsystem> runSort(List<Subsystem> input) {
        try {
            return SubsystemTopologicalSorter.sort(input);
        } catch (SubsystemOrchestrator.BootstrapException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }
}
