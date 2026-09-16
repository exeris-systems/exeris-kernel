/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Standard SQL transaction isolation levels.
 *
 * <h2>PostgreSQL Mapping</h2>
 * <ul>
 *   <li>{@link #READ_UNCOMMITTED} → PostgreSQL treats as {@code READ COMMITTED}</li>
 *   <li>{@link #READ_COMMITTED} → Default; snapshot per statement</li>
 *   <li>{@link #REPEATABLE_READ} → Snapshot at transaction start; serialization failure on write conflict</li>
 *   <li>{@link #SERIALIZABLE} → Full SSI (Serializable Snapshot Isolation); highest safety, potential retries</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum — identity-safe by definition. No migration needed for JEP 401.
 *
 * @implNote Community maps each constant onto a JDBC {@code Connection.TRANSACTION_*} constant;
 *           Enterprise emits it directly as SQL in the PG wire protocol
 *           {@code BEGIN TRANSACTION ISOLATION LEVEL ...}, with no JDBC layer in between.
 * @since 0.5
 * @see PersistenceConnection
 */
public enum TransactionIsolation {

    /**
     * Read Uncommitted — lowest isolation.
     * <p>PostgreSQL internally upgrades to {@code READ COMMITTED}.
     */
    READ_UNCOMMITTED,

    /**
     * Read Committed — default PostgreSQL isolation.
     * <p>Each statement sees a snapshot as of the start of that statement.
     */
    READ_COMMITTED,

    /**
     * Repeatable Read — snapshot at transaction start.
     * <p>Write conflicts cause {@code 40001} serialization failures.
     *
     * @apiNote A transaction at this level can fail for reasons that have nothing to do with its
     *          own correctness, so the caller MUST be able to retry it — or run it through
     *          {@link TransactionalExecutor}, which retries {@code 40001} for them.
     */
    REPEATABLE_READ,

    /**
     * Serializable — full SSI (Serializable Snapshot Isolation).
     * <p>Highest safety guarantee: write-skew anomalies are detected, at the cost of a higher
     * rate of serialization failures.
     *
     * @apiNote Retry handling is not optional at this level.
     */
    SERIALIZABLE
}
