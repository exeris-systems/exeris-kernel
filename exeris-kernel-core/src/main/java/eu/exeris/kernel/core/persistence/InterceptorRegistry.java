/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Core: Ordered registry of {@link ConnectionInterceptor} instances.
 *
 * <h2>Responsibility (The Brain)</h2>
 * <p>An optional ordering-and-sealing helper for the interceptor list a bootstrap assembles
 * for {@link PersistenceBootstrap#load}, which forwards each interceptor to the engine via
 * {@link PersistenceEngine#registerInterceptor}. Using it is not required — a bootstrap MAY
 * build the {@code List<ConnectionInterceptor>} directly instead, as the Community subsystem
 * does; this class earns its place for a bootstrap that composes interceptors from more than
 * one source and needs a fixed registration order plus a guarantee that nothing more is added
 * once the engine goes live.
 *
 * <h2>Typical Registrations</h2>
 * <p>The reference Community interceptor is a single {@code RlsConnectionInterceptor} that
 * covers all three isolation strategies and, on every one of them, publishes the session keys
 * PostgreSQL RLS policies read via a parameterised {@code set_config} statement: SHARED issues
 * only that statement; SEPARATED_SCHEMA additionally issues {@code SET search_path TO [schema]}
 * first; DEDICATED issues no routing SQL — its pool selection is handled by the engine — but
 * still publishes the session keys, since a strategy whose own isolation does not depend on a
 * given key still hands the next request on that connection whatever the previous one
 * published. Nothing here requires one interceptor per strategy, and nothing prevents an
 * operator from registering several — they run in the order {@link #seal()} captured them.
 *
 * <h2>Thread Safety</h2>
 * <p>Registration is not thread-safe — do it once during bootstrap, before the engine
 * is bound into {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE}.
 * After binding, the registry is effectively immutable (no new registrations allowed).
 *
 * <h2>The Wall (Open-Core)</h2>
 * <p>Imports only {@code exeris-kernel-spi}. Zero knowledge of HikariCP, pgjdbc,
 * io_uring, or any Community/Enterprise implementation.
 *
 * @since 0.5
 */
public final class InterceptorRegistry {

    private final List<ConnectionInterceptor> mutable = new ArrayList<>(4);
    private List<ConnectionInterceptor> sealed;

    /**
     * Creates an empty, unsealed registry.
     *
     * <p>Interceptors run in the order they are added. The sealed view is built on the first read, and
     * an add after that point is refused.
     */
    public InterceptorRegistry() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Registers a new interceptor at the end of the chain.
     *
     * @param interceptor interceptor to add; must not be {@code null}
     * @throws IllegalStateException if the registry is already sealed by an earlier
     *                               call to {@link #seal()}
     * @throws IllegalArgumentException if {@code interceptor} is {@code null}
     */
    public void register(ConnectionInterceptor interceptor) {
        if (sealed != null) {
            throw new IllegalStateException(
                    "InterceptorRegistry is sealed — registration must happen during bootstrap");
        }
        if (interceptor == null) {
            throw new IllegalArgumentException("interceptor must not be null");
        }
        mutable.add(interceptor);
    }

    /**
     * Returns an immutable snapshot of the registered interceptors.
     *
     * <p>Seals the registry on the first call — no further registrations are allowed
     * after that. Every call, including calls after the first, returns an unmodifiable
     * list backed by {@link List#copyOf}; because that list is already immutable,
     * later calls may return the same instance as an earlier one, but the returned
     * list can never be used to add, remove or reorder this registry's interceptors.
     * Intended for bootstrap use only — not a hot-path operation.
     *
     * @return ordered, immutable list of the registered interceptors
     */
    public List<ConnectionInterceptor> seal() {
        if (sealed == null) {
            sealed = List.copyOf(mutable);
        }
        return List.copyOf(sealed);
    }

    /**
     * Returns the number of registered interceptors.
     *
     * @return the count of interceptors in the sealed snapshot if {@link #seal()} has been
     *         called, otherwise the count registered so far
     */
    public int size() {
        return sealed != null ? sealed.size() : mutable.size();
    }

    /**
     * Reports whether this registry has been sealed.
     *
     * @return {@code true} if {@link #seal()} has been called at least once, meaning further
     *         {@link #register} calls will fail
     */
    public boolean isSealed() {
        return sealed != null;
    }
}
