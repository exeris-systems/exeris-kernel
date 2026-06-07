/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.tck.contract.diagnostics.AbstractKernelDiagnosticsTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community binding of {@link AbstractKernelDiagnosticsTck} — runs the SPI contract against
 * {@link CommunityKernelDiagnosticsProvider} (ADR-033 Obligation 9).
 */
@DisplayName("CommunityKernelDiagnostics — KernelDiagnostics TCK")
class CommunityKernelDiagnosticsTckTest extends AbstractKernelDiagnosticsTck {

    @Override
    protected KernelDiagnostics diagnostics() {
        return new CommunityKernelDiagnosticsProvider().create();
    }
}
