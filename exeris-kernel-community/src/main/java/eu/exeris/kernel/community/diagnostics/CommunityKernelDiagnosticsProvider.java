/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider;

/**
 * Community {@link KernelDiagnosticsProvider} (priority 0).
 *
 * <p>Returns a {@link CommunityKernelDiagnostics} that reads the in-process kernel state exposed via
 * {@code KernelProviders} {@link java.lang.ScopedValue} slots. Discovered through
 * {@link java.util.ServiceLoader}; the Enterprise overlay ({@code priority() == 100}) supersedes it on
 * a shared classpath (ADR-008).
 *
 * @since 0.9
 */
public final class CommunityKernelDiagnosticsProvider implements KernelDiagnosticsProvider {

    private static final String PROVIDER_NAME = "ExerisCommunity/KernelDiagnostics";

    @Override
    public KernelDiagnostics create() {
        return new CommunityKernelDiagnostics();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
    }
}
