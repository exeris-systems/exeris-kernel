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

    /**
     * Constructs the provider that {@link java.util.ServiceLoader} instantiates to resolve the
     * Community {@link KernelDiagnosticsProvider}, per this module's registration under
     * {@code META-INF/services/eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider}.
     */
    public CommunityKernelDiagnosticsProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Returns a new {@link CommunityKernelDiagnostics} bound to the current in-process kernel
     * state.
     *
     * @return a new diagnostics view
     */
    @Override
    public KernelDiagnostics create() {
        return new CommunityKernelDiagnostics();
    }

    /**
     * Returns this provider's display name, {@code "ExerisCommunity/KernelDiagnostics"}.
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Returns {@code 0}, this provider's fixed selection priority.
     */
    @Override
    public int priority() {
        return 0;
    }
}
