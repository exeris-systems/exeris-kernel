/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.SecurityProvider;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

/**
 * Binds the discovered {@link SecurityProvider} into {@link KernelProviders#SECURITY_PROVIDER}.
 *
 * <h2>Why this did not exist</h2>
 * <p>{@code bootstrap.md} has always placed Security among the L1 subsystems — it appears in the
 * parallel-init description, in the {@code scope.fork(() -> security.start())} sketch, and in the
 * DAG-cycle diagnostic example. Community shipped every other subsystem in that list and not this one.
 * {@code CommunitySecurityProvider} existed and was {@code ServiceLoader}-registered, but nothing bound
 * it, so {@code CommunityHttpRequestProcessor} always saw an unbound slot, built no
 * {@code SecurityInterceptor}, and the whole Citadel path — token authentication, role-mask population,
 * {@code StorageContextBridge} derivation — was unreachable in a default boot. ADR-061 treats closing
 * that as drift repair against the bootstrap contract rather than a new decision.
 *
 * <h2>Absence is not failure</h2>
 * <p>A deployment with no {@code SecurityProvider} on the classpath is a legitimate configuration —
 * an internal service behind a mesh, say — so discovery finding nothing leaves the subsystem not
 * running rather than failing the boot. What it must never do is bind a placeholder: an unbound slot
 * is read as "no authentication available" and denies, which is the fail-closed behaviour the Citadel
 * contract depends on.
 */
final class CommunitySecuritySubsystem extends AbstractCommunitySubsystem {

    private SecurityProvider securityProvider;

    @Override
    public String name() {
        return "security";
    }

    @Override
    public List<String> dependsOn() {
        // Token buffers are LoanedBuffers, so the allocator has to exist first. Crypto is deliberately
        // absent: a provider that needs it resolves it itself, and naming it here would serialise two
        // subsystems the bootstrap contract runs in parallel.
        return List.of("memory");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        securityProvider = ServiceLoader.load(SecurityProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(SecurityProvider::priority)
                        .thenComparing(provider -> provider.getClass().getName()))
                .orElse(null);
    }

    @Override
    public void start() {
        markRunning(securityProvider != null);
    }

    @Override
    public void stop() {
        markRunning(false);
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (securityProvider == null) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(
                CommunityCarrierBindings.binding(KernelProviders.SECURITY_PROVIDER, securityProvider)
        );
    }
}
