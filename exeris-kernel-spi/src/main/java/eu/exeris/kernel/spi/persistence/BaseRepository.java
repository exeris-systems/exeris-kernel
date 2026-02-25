/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

import java.util.Optional;

/**
 * SPI: Generic repository contract for aggregate root persistence.
 *
 * <h2>Design Intent</h2>
 * <p>Provides a minimal, imperative CRUD surface for L2/L3 business logic layers.
 * Isolation (RLS, schema routing) is handled transparently by the Core layer
 * via {@link ConnectionInterceptor} — no business code needs to be aware of
 * the active {@link eu.exeris.kernel.spi.security.StorageContext}.
 *
 * <h2>Loom-First Contract</h2>
 * <p>All methods are blocking. Virtual Threads make blocking "cheap" — there
 * is no reactive wrapper, no {@code Mono<T>}, no {@code CompletableFuture<T>}.
 * Each call corresponds to exactly one synchronous SQL round-trip.
 *
 * <h2>Transaction Boundaries</h2>
 * <p>Repositories do NOT manage transactions. Transaction demarcation is the
 * responsibility of the service layer (or the {@code @Transactional} interceptor
 * in the Core module). Within a transaction, the same {@link PersistenceConnection}
 * is propagated via {@link eu.exeris.kernel.spi.context.KernelProviders}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Implementations SHOULD use flat, immutable entity records for {@code <T>}
 * so C2 JIT escape analysis can scalarise them on the hot path.
 *
 * @param <T> the aggregate root type (MUST be an immutable record or deeply immutable class)
 * @param <K> the aggregate root's identifier (key) type — e.g., {@link java.util.UUID} or {@code long}.
 *            MUST be a value-safe type: no identity operations ({@code ==}, {@code synchronized}).
 * @since 0.5.0
 * @see EventStore
 * @see PersistenceConnection
 */
@SuppressWarnings("PMD.ShortVariable")
// id: domain identifier parameter — universally understood DDD term, not a meaningless abbreviation
public interface BaseRepository<T, K> {

    /**
     * Finds an entity by its identifier.
     *
     * @param id the aggregate root identifier; never {@code null}
     * @return the entity, or {@link Optional#empty()} if not found
     * @throws PersistenceProviderException on query failure
     */
    Optional<T> findById(K id);

    /**
     * Persists a new entity or updates an existing one (upsert semantics).
     *
     * <p>Implementations SHOULD use database-native upsert ({@code INSERT ... ON CONFLICT DO UPDATE})
     * to avoid a separate read before write.
     *
     * @param entity the entity to persist; never {@code null}
     * @throws PersistenceProviderException on write failure or constraint violation
     */
    void save(T entity);

    /**
     * Deletes the entity with the given identifier.
     *
     * <p>No-op if the entity does not exist (idempotent by contract).
     *
     * @param id the aggregate root identifier; never {@code null}
     * @throws PersistenceProviderException on delete failure
     */
    void deleteById(K id);

    /**
     * Returns {@code true} if an entity with the given identifier exists.
     *
     * <p><b>Performance note:</b> Implementations SHOULD use
     * {@code SELECT 1 FROM ... WHERE id = $1} rather than fetching the full row.
     *
     * @param id the identifier to check; never {@code null}
     * @return {@code true} if found
     * @throws PersistenceProviderException on query failure
     */
    boolean existsById(K id);
}


