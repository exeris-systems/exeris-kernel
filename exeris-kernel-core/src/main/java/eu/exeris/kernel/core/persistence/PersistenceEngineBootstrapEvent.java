/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a {@link eu.exeris.kernel.spi.persistence.PersistenceEngine}
 * completes bootstrap and is ready to accept connections.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every {@link eu.exeris.kernel.spi.persistence.PersistenceEngine} implementation —
 * Community and Enterprise alike — emits this event once bootstrap completes; production SRE
 * tooling relies on it to verify that the persistence layer started cleanly and within
 * expected latency bounds.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.persistence.EngineBootstrap")
@Label("Persistence Engine Bootstrap")
@Description("Emitted when a PersistenceEngine has completed initialisation and is ready to serve connections")
@Category({"Exeris Kernel", "Persistence"})
@StackTrace(false)
public final class PersistenceEngineBootstrapEvent extends Event {

    /** Stable provider identifier (e.g., {@code "postgres-community"}, {@code "postgres-enterprise"}). */
    @Label("Provider ID")
    public String providerId;

    /** Human-readable provider name (e.g., {@code "ExerisCommunity/JDBC+HikariCP"}). */
    @Label("Provider Name")
    public String providerName;

    /** Maximum connection pool size as configured. */
    @Label("Max Pool Size")
    public int maxPoolSize;

    /** Whether RLS (Row-Level Security) is enabled. */
    @Label("RLS Enabled")
    public boolean rlsEnabled;

    /** Whether per-tenant connection pooling is active. */
    @Label("Per-Tenant Pooling")
    public boolean perTenantPooling;

    /** Whether TLS is enabled for database connections. */
    @Label("TLS Enabled")
    public boolean tlsEnabled;

    /** Transport layer in use (e.g., {@code "BlockingTCP"}, {@code "NativeAsync"}). */
    @Label("Transport")
    public String transport;

    /**
     * Emits the event if JFR recording is active. No-op otherwise.
     *
     * @param providerId      stable provider identifier
     * @param providerName    human-readable name
     * @param maxPoolSize     configured pool ceiling
     * @param rlsEnabled      RLS flag
     * @param perTenantPooling per-tenant pooling flag
     * @param tlsEnabled      TLS flag
     * @param transport       transport layer name
     */
    public static void emit(String providerId,
                            String providerName,
                            int maxPoolSize,
                            boolean rlsEnabled,
                            boolean perTenantPooling,
                            boolean tlsEnabled,
                            String transport) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        PersistenceEngineBootstrapEvent event = new PersistenceEngineBootstrapEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.providerId       = providerId;
        event.providerName     = providerName;
        event.maxPoolSize      = maxPoolSize;
        event.rlsEnabled       = rlsEnabled;
        event.perTenantPooling = perTenantPooling;
        event.tlsEnabled       = tlsEnabled;
        event.transport        = transport;
        event.commit();
    }
}

