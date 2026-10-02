/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
        return BootstrapSortAdapter.runSort(input);
    }
}
