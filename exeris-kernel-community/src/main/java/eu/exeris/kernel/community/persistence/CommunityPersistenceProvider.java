/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.community.persistence.codec.CommunityRawJsonbCodec;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.persistence.codec.EntityDecoder;
import eu.exeris.kernel.spi.persistence.codec.EntityEncoder;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Community: JDBC-based {@link PersistenceProvider} using HikariCP connection pooling.
 *
 * <h2>Open-Core Positioning</h2>
 * <p>This is the <b>free-tier</b> persistence provider. It supports standard
 * PostgreSQL/MySQL/SQLite workloads over JDBC with HikariCP connection pooling. 
 * It also provides a raw JSONB codec for direct memory segment manipulation.
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
 * @since 0.5
 */
public final class CommunityPersistenceProvider implements PersistenceProvider {

    private static final String PROVIDER_NAME = "ExerisCommunity/JDBC+HikariCP";
    private static final CommunityRawJsonbCodec RAW_JSONB_CODEC = new CommunityRawJsonbCodec();

    /**
     * Constructs the provider that {@link java.util.ServiceLoader} instantiates to resolve the
     * Community {@link PersistenceProvider}, per this module's registration under
     * {@code META-INF/services/eu.exeris.kernel.spi.persistence.PersistenceProvider}.
     */
    public CommunityPersistenceProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override
    public String providerId() {
        return CommunityPersistenceConstants.PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
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

    @Override
    public Optional<EntityEncoder<MemorySegment>> rawEntityEncoder() {
        return Optional.of(RAW_JSONB_CODEC);
    }

    @Override
    public Optional<EntityDecoder<MemorySegment>> rawEntityDecoder() {
        return Optional.of(RAW_JSONB_CODEC);
    }

    /**
     * Community binding for the SPI persistence entity encoder contract.
     *
     * @return the shared raw-JSONB codec instance
     */
    public EntityEncoder<MemorySegment> entityEncoder() {
        return RAW_JSONB_CODEC;
    }

    /**
     * Community binding for the SPI persistence entity decoder contract.
     *
     * @return the shared raw-JSONB codec instance
     */
    public EntityDecoder<MemorySegment> entityDecoder() {
        return RAW_JSONB_CODEC;
    }
}

