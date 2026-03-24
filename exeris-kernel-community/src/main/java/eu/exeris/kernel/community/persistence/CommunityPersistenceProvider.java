/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;

/**
 * Community: JDBC-based {@link PersistenceProvider} using HikariCP connection pooling.
 *
 * <h2>Open-Core Positioning</h2>
 * <p>This is the <b>free-tier</b> persistence provider. It supports standard
 * PostgreSQL/MySQL/SQLite workloads via JDBC, but:
 * <ul>
 *   <li>No PG Native wire protocol — uses JDBC text/binary format via standard driver</li>
 *   <li>No io_uring transport — uses blocking TCP (Virtual Thread friendly)</li>
 *   <li>No zero-copy {@code RowCursor} — wraps JDBC {@code ResultSet} (heap allocations per row)</li>
 *   <li>No off-heap auth — uses JDK {@code javax.crypto} for SCRAM</li>
 *   <li>No {@code GlobalMemoryArbiter} partitions — temporary {@code Arena.ofConfined()} per query</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Registered via {@code META-INF/services/eu.exeris.kernel.spi.persistence.PersistenceProvider}.
 * Returns {@link #priority()} = 0; Enterprise provider (priority 100) wins when both present.
 *
 * <h2>Dependencies (SPI-only)</h2>
 * <ul>
 *   <li>{@code KernelProviders.MEMORY_ALLOCATOR} — for temporary buffer allocation</li>
 *   <li>{@code KernelProviders.TELEMETRY_PROVIDER} — for health-check and pool metric events</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class CommunityPersistenceProvider implements PersistenceProvider {

    private static final String PROVIDER_ID   = "postgres-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/JDBC+HikariCP";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0; // Enterprise wins with 100
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // bootstrap factory wraps any init failure
    public PersistenceEngine createEngine(PersistenceConfig config) {
        try {
            return new CommunityPersistenceEngine(config);
        } catch (RuntimeException cause) {
            throw PersistenceProviderException.bootstrapFailure(
                    PROVIDER_NAME, config.connectionUrl(), cause);
        }
    }
}

