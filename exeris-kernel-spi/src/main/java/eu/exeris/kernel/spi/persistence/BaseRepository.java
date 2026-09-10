/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @param <T> the aggregate root type (MUST be an immutable record or deeply immutable class)
 * @param <K> the aggregate root's identifier (key) type — e.g., {@link java.util.UUID} or {@code long}.
 *            MUST be a value-safe type: no identity operations ({@code ==}, {@code synchronized}).
 * @implSpec Implementations SHOULD use flat, immutable entity records for {@code <T>} so that C2
 *           escape analysis can scalarise them on the hot path.
 * @since 0.5
 * @see EventStore
 * @see PersistenceConnection
 */
@SuppressWarnings("PMD.ShortVariable")
// id: domain identifier parameter — universally understood DDD term, not a meaningless abbreviation
public interface BaseRepository<T, K> {

    /**
     * Finds an entity by its identifier, within whatever isolation the ambient
     * {@link eu.exeris.kernel.spi.security.StorageContext} imposes.
     *
     * @param id the aggregate root identifier; never {@code null}
     * @return the entity, or {@link Optional#empty()} when no row is visible under the current
     *         isolation — which is not the same as "no such row exists globally"
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on query
     *         failure
     */
    Optional<T> findById(K id);

    /**
     * Persists a new entity or updates an existing one (upsert semantics).
     *
     * @param entity the entity to persist; never {@code null}
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on write
     *         failure or constraint violation
     * @implSpec Implementations SHOULD use a database-native upsert
     *           ({@code INSERT ... ON CONFLICT DO UPDATE}) rather than a read followed by a
     *           write, which costs a round trip and races another writer.
     */
    void save(T entity);

    /**
     * Deletes the entity with the given identifier, doing nothing when no such entity exists.
     *
     * @param id the aggregate root identifier; never {@code null}
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on delete
     *         failure
     */
    void deleteById(K id);

    /**
     * Reports whether an entity with the given identifier is visible.
     *
     * @param id the identifier to check; never {@code null}
     * @return {@code true} when a row with that identifier is visible under the current isolation
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on query
     *         failure
     * @implSpec Implementations SHOULD issue {@code SELECT 1 FROM ... WHERE id = $1} rather than
     *           fetching and discarding the full row.
     */
    boolean existsById(K id);
}
