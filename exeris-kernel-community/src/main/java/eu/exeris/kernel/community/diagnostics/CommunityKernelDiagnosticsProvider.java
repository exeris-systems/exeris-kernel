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
import eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider;

/**
 * Community {@link KernelDiagnosticsProvider} (priority 0).
 *
 * <p>Returns a {@link CommunityKernelDiagnostics} that reads the in-process kernel state exposed via
 * {@code KernelProviders} {@link java.lang.ScopedValue} slots. Discovered through
 * {@link java.util.ServiceLoader}; the Enterprise overlay ({@code priority() == 100}) supersedes it on
 * a shared classpath (ADR-008).
 *
 * @since 0.9.0
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
