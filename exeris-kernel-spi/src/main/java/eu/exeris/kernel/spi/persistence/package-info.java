/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Exeris Kernel SPI – Persistence contracts (L0).
 *
 * <h2>SPI-First Architecture</h2>
 * <p>This package defines all persistence abstractions consumed by the kernel bootstrap
 * and business logic. No implementation-specific class ({@code io_uring}, {@code HikariCP},
 * {@code PgConnection}) is referenced here — only pure contracts.
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.persistence.PersistenceProvider} — {@code ServiceLoader} entry point</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.PersistenceEngine} — connection factory + pool management</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.PersistenceConnection} — single connection lifecycle</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.PersistenceStatement} — zero-allocation typed binder
 *       (replaces {@code Object... params} autoboxing — ADR-010 L0 fix)</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.QueryResult} — tier-aware result iteration (flyweight)</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.RowCursor} — zero-copy row cursor</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.TransactionIsolation} — SQL isolation levels</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.PersistenceConfig} — immutable config with opaque properties</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.EngineStats} — pool metrics snapshot</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.ConnectionInterceptor} — plug-and-play isolation hook
 *       (RLS injection, schema switching); registered by Core, invoked on every connection checkout</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.BaseRepository} — generic aggregate-root CRUD contract
 *       ({@code <T, K>} where {@code K} is the identifier/key type)</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.EventStore} — Transactional Outbox contract;
 *       at-least-once domain event delivery co-committed with the aggregate transaction</li>
 * </ul>
 *
 * <h2>Tier Separation (The Wall)</h2>
 * <ul>
 *   <li><b>Community</b>: JDBC-based, {@code Arena.ofConfined()} temporary allocations,
 *       HikariCP connection pooling. Lives in {@code exeris-kernel-community}.</li>
 *   <li><b>Enterprise</b>: PG Native wire protocol, io_uring transport, zero-copy
 *       {@link eu.exeris.kernel.spi.persistence.RowCursor} over {@code LoanedBuffer}.
 *       Lives in {@code exeris-kernel-enterprise}.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>All providers are loaded via {@link java.util.ServiceLoader}. The kernel bootstrapper
 * selects the highest-{@link eu.exeris.kernel.spi.persistence.PersistenceProvider#priority()}
 * implementation and binds it into {@code KernelProviders.PERSISTENCE_PROVIDER}.
 *
 * <h2>Zero-Copy Contract (Enterprise)</h2>
 * <p>In the Enterprise tier, {@link eu.exeris.kernel.spi.persistence.QueryResult#next()}
 * MUST NOT allocate heap objects. The {@link eu.exeris.kernel.spi.persistence.RowCursor}
 * returned by {@link eu.exeris.kernel.spi.persistence.QueryResult#row()} is a flyweight
 * that reads directly from off-heap {@link eu.exeris.kernel.spi.memory.LoanedBuffer} segments.
 *
 * <h2>Zero-Allocation Query Binding</h2>
 * <p>The {@link eu.exeris.kernel.spi.persistence.PersistenceStatement} interface provides
 * typed primitive binders ({@code bindInt}, {@code bindLong}, {@code bindDouble}) that
 * write directly to off-heap memory in Enterprise tier, avoiding the autoboxing and
 * {@code Object[]} allocation of the legacy {@code Object... params} pattern.
 *
 * @see eu.exeris.kernel.spi.persistence.PersistenceProvider
 * @see eu.exeris.kernel.spi.persistence.PersistenceStatement
 * @see eu.exeris.kernel.spi.persistence.QueryResult
 * @since 0.5
 */
package eu.exeris.kernel.spi.persistence;
