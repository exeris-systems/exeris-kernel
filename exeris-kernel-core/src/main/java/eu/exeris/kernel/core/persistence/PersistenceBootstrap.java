/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.core.bootstrap.BootstrapProviderSelector;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Core: ServiceLoader-driven bootstrap for the Persistence subsystem.
 *
 * <h2>Responsibility (The Brain)</h2>
 * <p>This class is the <b>only</b> place in the kernel that calls
 * {@link ServiceLoader#load(Class)} for {@link PersistenceProvider}.
 * It selects the highest-priority provider, creates the engine, registers
 * pre-built interceptors, and emits provider-selection telemetry.
 * The resulting {@link PersistenceEngine} is returned to the caller ready
 * for {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE} binding.
 *
 * <h2>Priority Rule</h2>
 * <ul>
 *   <li>Enterprise: priority = 100</li>
 *   <li>Community: priority = 0</li>
 * </ul>
 * <p>If Enterprise is on the classpath, it wins. If only Community is present,
 * Community wins. If neither is found → {@link PersistenceProviderException#noProviderAvailable(String)}
 * is thrown (error code {@code EX-PERS-5007}) — kernel start aborts.
 *
 * <h2>ScopedValue Binding</h2>
 * <p>The caller (typically {@code KernelBootstrap}) wraps its subsystem startup
 * code in:
 * <pre>{@code
 * ScopedValue
 *     .where(KernelProviders.PERSISTENCE_ENGINE, engine)
 *     .run(kernel::startSubsystems);
 * }</pre>
 * {@code PersistenceBootstrap.load()} returns the {@link PersistenceEngine} ready
 * for that binding — it does not bind it directly (that is {@code KernelBootstrap}'s job).
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>This class imports only {@code exeris-kernel-spi}. It has zero knowledge of
 * HikariCP, io_uring, pgjdbc, or any Community/Enterprise implementation class.
 *
 * @since 0.5
 */
public final class PersistenceBootstrap {

    private static final String ERROR_NO_PROVIDER =
            "No PersistenceProvider found on classpath. "
            + "Add exeris-kernel-community or exeris-kernel-enterprise to your dependencies.";

    private PersistenceBootstrap() {
        // utility — no instances
    }

    /**
     * Loads the best available {@link PersistenceProvider} via {@link ServiceLoader},
     * then delegates to {@link #load(PersistenceProvider, PersistenceConfig, List)}.
     *
     * <p>Use this overload when the caller does not hold a pre-resolved provider.
     * When a provider has already been resolved (e.g. in {@code CommunityPersistenceSubsystem}),
     * prefer the three-argument overload to avoid a redundant scan.
     *
     * @param config       immutable persistence configuration; must not be {@code null}
     * @param interceptors ordered list of interceptors to register; must not be {@code null} (may be empty)
     * @return a fully initialised {@link PersistenceEngine}
     * @throws NullPointerException      if {@code config} or {@code interceptors} is {@code null}
     * @throws PersistenceProviderException with code {@code EX-PERS-5007} if no provider is available
     */
    public static PersistenceEngine load(PersistenceConfig config,
                                         List<ConnectionInterceptor> interceptors) {
        Objects.requireNonNull(config,       "config must not be null");
        Objects.requireNonNull(interceptors, "interceptors must not be null");
        // --- Phase 1: Discover all PersistenceProviders via ServiceLoader ---
        PersistenceProvider provider = BootstrapProviderSelector.loadHighestPriority(
                PersistenceProvider.class,
                Comparator.comparingInt(PersistenceProvider::priority)
                    .thenComparing(p -> p.getClass().getName()))
                .orElseThrow(() -> PersistenceProviderException.noProviderAvailable(
                        ERROR_NO_PROVIDER));
        return load(provider, config, interceptors);
    }

    /**
     * Creates the engine from an already-resolved {@link PersistenceProvider}, registers
     * interceptors, and emits provider-selection telemetry.
     *
     * <p>Prefer this overload when the caller has already performed provider selection
     * (e.g. a subsystem that also needs to bind the provider into {@link eu.exeris.kernel.spi.context.KernelProviders})
     * so that the {@link ServiceLoader} scan happens exactly once per kernel lifecycle.
     *
     * @param provider     pre-resolved provider; must not be {@code null}
     * @param config       immutable persistence configuration; must not be {@code null}
     * @param interceptors ordered list of interceptors to register; must not be {@code null} (may be empty)
     * @return a fully initialised {@link PersistenceEngine}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static PersistenceEngine load(PersistenceProvider provider,
                                         PersistenceConfig config,
                                         List<ConnectionInterceptor> interceptors) {
        Objects.requireNonNull(provider,     "provider must not be null");
        Objects.requireNonNull(config,       "config must not be null");
        Objects.requireNonNull(interceptors, "interceptors must not be null");

        // --- Phase 1.5: Validate RLS configuration before engine creation ---
        validateRlsInterceptorConfiguration(config, interceptors, provider.providerName());

        // --- Phase 2: Create the PersistenceEngine (bootstrap allocation permitted) ---
        PersistenceEngine engine = provider.createEngine(config);

        try {
            // --- Phase 3: Register interceptors (RLS, schema-switch, audit, etc.) ---
            for (ConnectionInterceptor interceptor : interceptors) {
                engine.registerInterceptor(interceptor);
            }

            // --- Phase 4: JFR-First — emit provider-selection telemetry ---
            PersistenceBootstrapSelectedEvent.emit(
                    provider.getClass().getName(),
                    provider.priority(),
                    provider.providerName(),
                    interceptors.size()
            );
        } catch (RuntimeException ex) { //NOPMD AvoidCatchingGenericException — SPI may throw any RuntimeException
            try {
                engine.close();
            } catch (RuntimeException closeEx) { //NOPMD AvoidCatchingGenericException — untrusted SPI boundary
                ex.addSuppressed(closeEx);
            }
            throw ex;
        }

        return engine;
    }

    /**
     * Convenience overload — no interceptors.
     *
     * @param config immutable persistence configuration
     * @return a fully initialised {@link PersistenceEngine}
     */
    public static PersistenceEngine load(PersistenceConfig config) {
        return load(config, List.of());
    }

    /**
     * Validates that at least one {@link ConnectionInterceptor} is registered when
     * Row-Level Security is enabled. Fails fast at bootstrap time before engine
     * creation is attempted.
     *
     * @param config       persistence configuration
     * @param interceptors registered interceptors (may be empty)
     * @param providerName selected provider display name, captured in the exception rawArgs
     * @throws PersistenceProviderException with code {@code EX-PERS-5001} if RLS is
     *         enabled and no interceptors are registered
     */
    /* default */ static void validateRlsInterceptorConfiguration(PersistenceConfig config,
                                                     List<ConnectionInterceptor> interceptors,
                                                     String providerName) {
        if (config.rlsEnabled() && interceptors.isEmpty()) {
            throw PersistenceProviderException.bootstrapFailure(
                    providerName, config.connectionUrl(),
                    new IllegalStateException("RLS enabled but no ConnectionInterceptor registered"));
        }
    }
}
