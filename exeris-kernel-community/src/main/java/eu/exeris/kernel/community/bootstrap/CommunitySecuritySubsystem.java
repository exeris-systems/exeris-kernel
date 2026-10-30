/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import eu.exeris.kernel.spi.time.TimeKernelProviders;

import java.util.List;
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
 *
 * <h2>Verification keys come from configuration</h2>
 * <p>The {@code ServiceLoader} instance of {@link CommunitySecurityProvider} holds no verification
 * key. When it is the selected provider and {@link CommunityJwksSettings} reads a JWKS endpoint, issuer
 * and audience, this subsystem binds instead a provider over that endpoint, built at {@code start()}
 * ({@link DeferredSecurityProvider}) and closed at {@code stop()}. A configuration with one of those
 * keys missing fails the boot. The keys are read only for the Community provider: a provider that
 * outranks it takes its keys from wherever it chooses.
 */
// CloseResource: the ServiceLoader instance holds no resource to close; it serves the deferred provider
// as the source of the identity the two share.
@SuppressWarnings("PMD.CloseResource")
final class CommunitySecuritySubsystem extends AbstractCommunitySubsystem {

    private static final String NAME = "security";
    // SubsystemException carries a static detail.
    private static final String JWKS_UNBUILDABLE =
            "cannot build the token verification key set for " + CommunityJwksSettings.JWKS_URI_KEY;

    private SecurityProvider provider;
    private DeferredSecurityProvider jwksProvider;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> dependsOn() {
        // Token buffers are LoanedBuffers, so the allocator has to exist first. Crypto is deliberately
        // absent: a provider that needs it resolves it itself, and naming it here would serialise two
        // subsystems the bootstrap contract runs in parallel. The JWKS engine is built at start(),
        // inside the kernel scope, where every initialised provider is bound.
        return List.of("memory");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        SecurityProvider discovered = CommunityProviderDiscovery.highestPriority(
                SecurityProvider.class, SecurityProvider::priority);
        provider = discovered;
        if (discovered instanceof CommunitySecurityProvider community && KernelProviders.CURRENT_CONFIG.isBound()) {
            CommunityJwksSettings.read(KernelProviders.CURRENT_CONFIG.get()).ifPresent(settings -> {
                jwksProvider = new DeferredSecurityProvider(community, () ->
                        CommunitySecurityProvider.overJwksEndpoint(settings.jwksUri(),
                                KeyRotationPolicy.defaults(),
                                TimeKernelProviders.timeSource().asClock(),
                                settings.issuer(), settings.audience()));
                provider = jwksProvider;
            });
        }
    }

    // AvoidCatchingGenericException: whatever refuses the key set — a URI the endpoint parser rejects,
    // an https endpoint with no engine that verifies its server — fails start() as this subsystem,
    // with the refusal kept as the cause.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Override
    public void start() {
        if (jwksProvider != null) {
            try {
                jwksProvider.start();
            } catch (RuntimeException ex) {
                throw new SubsystemException(NAME, SubsystemException.Phase.START, JWKS_UNBUILDABLE, ex);
            }
        }
        if (provider != null) {
            // Same reason as every other Community subsystem: the hot-path JFR event classes
            // initialise on the starting thread, never later on a virtual thread where a <clinit>
            // pins its carrier. Behind the guard, because a subsystem that found no provider will
            // emit none of them and should not pay their class load at boot.
            CommunityJfrEventCatalogue.warmHotPath(name());
        }
        markRunning(provider != null);
    }

    @Override
    public void stop() {
        markRunning(false);
        if (jwksProvider != null) {
            jwksProvider.close();
        }
    }

    /**
     * Binds the provider, or leaves the slot untouched when none was found.
     *
     * <p>Absence is a configuration, not a failure. Leaving the slot unbound is what makes the readers
     * downstream fail closed — binding a placeholder would turn "no provider" into "a provider that
     * says yes to nothing in particular", which is far harder to diagnose.
     *
     * @return the carrier enricher for this subsystem
     */
    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (provider == null) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(
                CommunityCarrierBindings.binding(KernelProviders.SECURITY_PROVIDER, provider));
    }
}
