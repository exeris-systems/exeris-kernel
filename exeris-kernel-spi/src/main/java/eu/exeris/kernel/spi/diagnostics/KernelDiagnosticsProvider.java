/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.diagnostics;

/**
 * SPI: factory + discovery handle for {@link KernelDiagnostics}.
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. On classpath collision the highest {@link #priority()}
 * wins: Community returns {@code 0}, the Enterprise overlay returns {@code 100} (the open-core loading
 * model, ADR-008). On a {@code priority()} tie the winner is the first provider in {@code ServiceLoader}
 * iteration order — implementation-defined and not stable across runs, so providers MUST NOT rely on
 * winning a tie.
 *
 * @since 0.9.0
 */
public interface KernelDiagnosticsProvider {

    /**
     * Creates a {@link KernelDiagnostics} bound to the current in-process kernel state.
     *
     * @return non-null diagnostics view
     */
    KernelDiagnostics create();

    /**
     * Stable provider name, used in JFR events and diagnostics output
     * (e.g. {@code "ExerisCommunity/KernelDiagnostics"}).
     */
    String providerName();

    /**
     * Higher value wins; Community = 0, Enterprise = 100.
     */
    default int priority() {
        return 0;
    }
}
