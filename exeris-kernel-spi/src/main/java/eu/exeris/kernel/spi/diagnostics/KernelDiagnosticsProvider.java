/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.diagnostics;

/**
 * SPI: factory + discovery handle for {@link KernelDiagnostics}.
 *
 * <p>Implementations are loaded via {@link java.util.ServiceLoader}. On a classpath collision the
 * highest {@link #priority()} wins: Community returns {@code 0}, the Enterprise overlay returns
 * {@code 100} (the open-core loading model, ADR-008).
 *
 * @implSpec On a {@link #priority()} tie the winner is the first provider in
 *           {@link java.util.ServiceLoader} iteration order — implementation-defined and not stable
 *           across runs — so an implementation must not rely on winning a tie (ADR-033 Obligation 3).
 *           {@link #providerName()} and {@link #create()} both return a non-null value.
 * @since 0.9
 */
public interface KernelDiagnosticsProvider {

    /**
     * Creates a {@link KernelDiagnostics} bound to the current in-process kernel state.
     *
     * @return a non-null view over this runtime; every call on it captures the state anew, so one
     *         instance stays usable as the kernel's composition changes
     */
    KernelDiagnostics create();

    /**
     * Stable provider name, used in JFR events and diagnostics output
     * (e.g. {@code "ExerisCommunity/KernelDiagnostics"}).
     *
     * @return the provider's stable, non-null identifier, conventionally
     *         {@code "<Vendor>/KernelDiagnostics"}; it identifies which tier answered an introspection
     *         call, so it does not vary between runs of the same build
     */
    String providerName();

    /**
     * Discovery rank of this provider: where more than one is on the classpath, the highest value wins.
     *
     * <p>Community returns {@code 0} and the Enterprise overlay {@code 100} (ADR-008). No other values
     * are reserved; a third-party provider may use any value but is not expected to outrank the
     * Enterprise overlay (ADR-033 Obligation 3).
     *
     * @return this provider's discovery rank; {@code 0} unless overridden, which is the Community rank
     */
    default int priority() {
        return 0;
    }
}
