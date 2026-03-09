/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Core: ServiceLoader-driven bootstrap for the Persistence subsystem.
 *
 * <h2>Responsibility (The Brain)</h2>
 * <p>This class is the <b>only</b> place in the kernel that calls
 * {@link ServiceLoader#load(Class)} for {@link PersistenceProvider}.
 * It selects the highest-priority provider, creates the engine, registers
 * pre-built interceptors, and emits JFR bootstrap events.
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
 * @since 0.5.0
 */
public final class PersistenceBootstrap {

    private static final String ERROR_NO_PROVIDER =
            "No PersistenceProvider found on classpath. "
            + "Add exeris-kernel-community or exeris-kernel-enterprise to your dependencies.";

    private PersistenceBootstrap() {
        // utility — no instances
    }

    /**
     * Loads the best available {@link PersistenceProvider}, creates the engine,
     * registers the supplied interceptors, and returns the ready {@link PersistenceEngine}.
     *
     * <p>This method performs a fresh {@link ServiceLoader} scan and engine construction
     * on every invocation. It is intended to be called exactly once per kernel lifecycle,
     * during bootstrap. Multiple calls will produce independent engine instances and
     * re-register interceptors — the caller is responsible for lifecycle control.
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
        PersistenceProvider provider = ServiceLoader.load(PersistenceProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(PersistenceProvider::priority)
                        .thenComparing(p -> p.getClass().getName()))
                .orElseThrow(() -> PersistenceProviderException.noProviderAvailable(
                        ERROR_NO_PROVIDER));

        // --- Phase 2: Create the PersistenceEngine (bootstrap allocation permitted) ---
        PersistenceEngine engine = provider.createEngine(config);

        try {
            // --- Phase 3: Register interceptors (RLS, schema-switch, audit, etc.) ---
            for (ConnectionInterceptor interceptor : interceptors) {
                engine.registerInterceptor(interceptor);
            }

            // --- Phase 4: JFR-First — emit bootstrap events ---
            PersistenceEngineBootstrapEvent.emit(
                    engine.capabilities().providerId(),
                    provider.getClass().getName(),
                    config.maxPoolSize(),
                    config.rlsEnabled(),
                    config.perTenantPooling(),
                    config.useTls(),
                    engine.capabilities().transportName()
            );
            PersistenceBootstrapSelectedEvent.emit(
                    provider.getClass().getName(),
                    provider.priority(),
                    engine.capabilities().transportName(),
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
}
