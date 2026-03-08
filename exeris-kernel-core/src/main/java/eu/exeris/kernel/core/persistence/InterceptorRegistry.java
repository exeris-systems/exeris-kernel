/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Core: Ordered registry of {@link ConnectionInterceptor} instances.
 *
 * <h2>Responsibility (The Brain)</h2>
 * <p>This class is the single authoritative source of which interceptors run on
 * every connection checkout. It is populated during kernel bootstrap by the operator
 * code (or the TCK harness) and then passed to {@link PersistenceBootstrap#load}
 * which forwards each interceptor to the engine via
 * {@link PersistenceEngine#registerInterceptor}.
 *
 * <h2>Typical Registrations</h2>
 * <ul>
 *   <li>{@code RlsConnectionInterceptor} — {@code SET LOCAL exeris.tenant_id = ?}
 *       (Row-Level Security, SHARED strategy)</li>
 *   <li>{@code SchemaConnectionInterceptor} — {@code SET search_path TO [schema]}
 *       (SEPARATED_SCHEMA strategy)</li>
 *   <li>{@code AuditConnectionInterceptor} — sets audit user for DB-side triggers</li>
 * </ul>
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
 * @since 0.5.0
 */
public final class InterceptorRegistry {

    private final List<ConnectionInterceptor> interceptors = new ArrayList<>(4);
    private volatile boolean sealed;
    private List<ConnectionInterceptor> immutableSnapshot;

    /**
     * Registers a new interceptor at the end of the chain.
     *
     * @param interceptor interceptor to add; must not be {@code null}
     * @throws IllegalStateException if the registry is already sealed
     *                               (i.e., engine is live)
     */
    public void register(ConnectionInterceptor interceptor) {
        if (sealed) {
            throw new IllegalStateException(
                    "InterceptorRegistry is sealed — registration must happen during bootstrap");
        }
        if (interceptor == null) {
            throw new IllegalArgumentException("interceptor must not be null");
        }
        interceptors.add(interceptor);
    }

    /**
     * Returns an immutable snapshot of the registered interceptors.
     *
     * <p>Seals the registry — no further registrations are allowed after this call.
     * Subsequent calls return the same immutable list instance (idempotent).
     *
     * @return ordered, immutable list of interceptors
     */
    public List<ConnectionInterceptor> seal() {
        if (immutableSnapshot == null) {
            immutableSnapshot = Collections.unmodifiableList(new ArrayList<>(interceptors));
            sealed = true;
        }
        return Collections.unmodifiableList(new ArrayList<>(immutableSnapshot));
    }

    /** Returns the number of registered interceptors. */
    public int size() {
        return interceptors.size();
    }

    /** {@code true} if the registry has been sealed by a {@link #seal()} call. */
    public boolean isSealed() {
        return sealed;
    }
}

