/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <h2>Tier Behaviour</h2>
 * <ul>
 *   <li><b>Community:</b> Maps to JDBC {@code Connection.TRANSACTION_*} constants.</li>
 *   <li><b>Enterprise:</b> Emitted directly as SQL in the PG wire protocol
 *       {@code BEGIN TRANSACTION ISOLATION LEVEL ...} — zero JDBC overhead.</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum — identity-safe by definition. No migration needed for JEP 401.
 *
 * @since 0.5.0
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
     * Callers MUST handle retry logic.
     */
    REPEATABLE_READ,

    /**
     * Serializable — full SSI (Serializable Snapshot Isolation).
     * <p>Highest safety guarantee. Write skew anomalies are detected.
     * Higher chance of serialization failures — retry logic is mandatory.
     */
    SERIALIZABLE
}
