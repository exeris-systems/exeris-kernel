/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable JDBC implementation of {@link FlowSnapshotStore} backed by the
 * {@code exeris_saga_state} table (see ADR-013, FLOW-103).
 *
 * <h2>SPI-Routed Connection Acquisition (ADR-022)</h2>
 * <p>This implementation acquires connections through the {@link PersistenceEngine}
 * SPI rather than a raw {@code javax.sql.DataSource}. The engine owns request-scoped
 * session reuse, RLS interceptors, and tenant-pool resolution; the snapshot store
 * itself is a pure SPI consumer.
 *
 * <h2>Optimistic Concurrency (ADR-013 §5)</h2>
 * <p>{@link #save(FlowSnapshot)} attempts an UPDATE guarded by the incoming
 * {@link FlowSnapshot#schemaVersion()}. On affected-rows = 0 the implementation
 * distinguishes "row absent" (→ INSERT) from "row present with different version"
 * (→ {@link FlowEngineException#optimisticLockConflict(String, long)} carrying
 * {@code phase=OPTIMISTIC_LOCK_CONFLICT}, {@code reasonCode=STALE_VERSION}).
 *
 * <h2>Cross-Database Portability</h2>
 * <p>The implementation does not rely on Postgres-specific constructs
 * ({@code ON CONFLICT ... DO UPDATE}, {@code INT[]}). The two-step UPDATE/INSERT
 * pattern works on any JDBC backend that supports composite primary keys and
 * transactional isolation. {@code compensation_stack} is packed into {@code BYTEA}
 * (4 bytes per int, big-endian, length {@code stackPointer * 4}) for the same
 * reason — H2 does not support native {@code INT[]} columns.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe by virtue of using a fresh SPI connection per call.
 * The contract assumes {@code engine.openConnection()} returns either a fresh
 * physical connection or a request-scoped session-box wrapper; callers MUST NOT
 * share a single physical connection across concurrent invocations.
 *
 * <h2>Hot-path Discipline (ADR-013 §7)</h2>
 * <ul>
 *   <li>No {@code ThreadLocal} for context propagation.</li>
 *   <li>No {@code ByteBuffer.allocate(int)} on the hot path; {@code ByteBuffer.wrap(byte[])}
 *       wraps an already-allocated array.</li>
 *   <li>{@link PersistenceEngine} ownership stays with the bootstrapper that
 *       constructed this store.</li>
 * </ul>
 *
 * <h2>HikariCP Statement-Cache Requirement (DOC-090, v0.8 Sprint 5)</h2>
 * <p>This store relies on the underlying {@link PersistenceEngine}'s JDBC pool
 * having driver-side prepared-statement caching enabled. The two-step UPSERT
 * re-prepares the same {@code SQL_UPDATE_OCC} + {@code SQL_INSERT} statements
 * on every saga write; without statement caching each save pays the SQL parse
 * cost twice (UPDATE then INSERT on first-writer) and PostgreSQL never
 * promotes them to server-side prepared form. The Community Hikari binding
 * sets these as opt-out defaults in
 * {@code CommunityHikariSupport.applyDataSourceProperties}:
 * <ul>
 *   <li>{@code cachePrepStmts=true}  — enable the cache (HARD requirement).</li>
 *   <li>{@code prepStmtCacheSize=250} — entries; covers OCC + outbox + RLS paths.</li>
 *   <li>{@code prepStmtCacheSqlLimit=2048} — per-statement SQL length cap.</li>
 * </ul>
 * Operators can override via {@code PersistenceConfig.properties()} (e.g., dial
 * sizes down for memory-constrained deployments) but must keep
 * {@code cachePrepStmts=true} — turning it off changes the OCC contract's
 * performance envelope dramatically. See {@code docs/subsystems/flow.md}
 * Persistence section.
 *
 * @since 0.7.0
 */
// QA-017 extracted JdbcFlowSnapshotCodec (binding + BYTEA compensation-stack pack/unpack +
// row decoder). Residual store retains the FlowSnapshotStore SPI surface, the two-step
// UPDATE-then-INSERT OCC orchestration (ADR-013 §5), the integrity-violation race remapping,
// and the JFR-091 generic-failure diagnostics (extractSqlState + emit FlowSnapshotSaveFailedEvent).
// Splitting further would fragment the transactional boundary the OCC contract hangs on.
//
// TooManyMethods retained: JFR-091 added FlowSnapshotSaveFailedEvent + extractSqlState helper,
// pushing the method count back over the PMD threshold that QA-017 had closed. Acceptable cost
// for the operator-facing diagnostic surface.
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
public final class JdbcFlowSnapshotStore implements FlowSnapshotStore {

    private static final String SQL_INSERT =
            "INSERT INTO exeris_saga_state ("
                    + "instance_id_most, instance_id_least, definition_name, current_step, "
                    + "state, last_update, timeout_at, compensation_stack, stack_pointer, "
                    + "opaque_state, schema_version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_UPDATE_OCC =
            "UPDATE exeris_saga_state SET "
                    + "definition_name = ?, current_step = ?, state = ?, "
                    + "last_update = ?, timeout_at = ?, compensation_stack = ?, "
                    + "stack_pointer = ?, opaque_state = ?, "
                    + "schema_version = schema_version + 1 "
                    + "WHERE instance_id_most = ? AND instance_id_least = ? "
                    + "AND schema_version = ?";

    private static final String SQL_SELECT_BY_PK =
            "SELECT instance_id_most, instance_id_least, definition_name, current_step, "
                    + "state, last_update, timeout_at, compensation_stack, stack_pointer, "
                    + "opaque_state, schema_version "
                    + "FROM exeris_saga_state "
                    + "WHERE instance_id_most = ? AND instance_id_least = ?";

    private static final String SQL_DELETE =
            "DELETE FROM exeris_saga_state "
                    + "WHERE instance_id_most = ? AND instance_id_least = ?";

    private static final String SQL_EXISTS =
            "SELECT 1 FROM exeris_saga_state "
                    + "WHERE instance_id_most = ? AND instance_id_least = ?";

    private static final String SQL_LIST_PARKED =
            "SELECT instance_id_most, instance_id_least, definition_name, current_step, "
                    + "state, last_update, timeout_at, compensation_stack, stack_pointer, "
                    + "opaque_state, schema_version "
                    + "FROM exeris_saga_state "
                    + "WHERE state = '" + /* FlowState.PARKED.name() */ "PARKED" + "'";

    // SQLSTATE class 23 covers ANSI integrity-constraint violations (unique, FK, not-null,
    // check, exclusion). Matches Postgres 23505 (unique_violation), H2 23505, and any
    // spec-conformant driver — used to disambiguate concurrent-INSERT race-loser from
    // genuine error during the two-step UPDATE-then-INSERT pattern (ADR-013 §5 TOCTOU
    // resolution).
    private static final String SQLSTATE_CLASS_INTEGRITY = "23";

    /**
     * Affected-row count returned by {@code PersistenceStatement.executeUpdate()} when
     * the optimistic UPDATE-CAS did not match any row — either the row is absent or its
     * {@code schema_version} no longer matches the incoming snapshot. The save path
     * distinguishes the two cases via {@link #existsInTransaction}; see ADR-013 §5.
     */
    private static final long NO_ROWS_AFFECTED = 0L;

    private final PersistenceEngine engine;
    private final String engineName;

    public JdbcFlowSnapshotStore(PersistenceEngine engine, String engineName) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.engineName = Objects.requireNonNull(engineName, "engineName");
    }

    // The two-step UPSERT (UPDATE-CAS → distinguish absent vs stale → INSERT) plus
    // commit/rollback ladder yields ~7 decision points; splitting further fragments
    // the transactional boundary that ADR-013 §5 hangs the OCC contract on. The class-level
    // `PMD.CyclomaticComplexity` suppression covers this method.
    //
    // `catch (FlowEngineException occ)` is intentional: the OCC failure raised by
    // `optimisticLockConflict` MUST trigger rollback and re-throw without wrapping.
    // This is transactional control flow, not error-control-flow misuse.
    @Override
    @SuppressWarnings("PMD.ExceptionAsFlowControl")
    public void save(FlowSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try (PersistenceConnection conn = engine.openConnection()) {
            conn.beginTransaction();
            try {
                long affected = tryOptimisticUpdate(conn, snapshot);
                if (affected == NO_ROWS_AFFECTED) {
                    if (existsInTransaction(conn, snapshot.instanceIdMost(), snapshot.instanceIdLeast())) {
                        OptimisticLockConflictEvent.emit(
                                engineName,
                                OptimisticLockConflictEvent.PHASE_UPDATE_STALE,
                                snapshot.schemaVersion());
                        throw FlowEngineException.optimisticLockConflict(engineName, snapshot.schemaVersion());
                    }
                    insertOrRemapPkConflict(conn, snapshot);
                }
                conn.commit();
            } catch (FlowEngineException occ) {
                conn.rollback();
                throw occ;
            } catch (PersistenceProviderException ppe) {
                conn.rollback();
                // JFR-091: post-mortem trail for non-OCC save failures (driver timeout,
                // deadlock, connection lost, schema drift, etc.). OCC race losers already
                // emit OptimisticLockConflictEvent on the FlowEngineException catch above.
                FlowSnapshotSaveFailedEvent.emit(
                        engineName,
                        extractSqlState(ppe),
                        ppe.getClass().getName(),
                        String.valueOf(ppe.getMessage()));
                throw new FlowEngineException("Failed to save flow snapshot", ppe);
            }
        }
    }

    /**
     * Performs the INSERT and remaps a concurrent-INSERT race on the composite PK to
     * {@code OPTIMISTIC_LOCK_CONFLICT}. Between the {@code existsInTransaction} probe
     * and this call, a concurrent first-writer may commit a row for the same instance;
     * the resulting integrity-constraint violation (SQLState class {@code 23}) is the
     * indistinguishable race-loser signal, so it surfaces under the same OCC contract
     * documented in ADR-013 §5 — the loser of an INSERT race observes the same failure
     * mode as the loser of a stale-version UPDATE race.
     */
    private void insertOrRemapPkConflict(PersistenceConnection conn, FlowSnapshot snapshot) {
        try {
            insertSnapshot(conn, snapshot);
        } catch (PersistenceProviderException ppe) {
            if (isIntegrityConstraintViolation(ppe)) {
                OptimisticLockConflictEvent.emit(
                        engineName,
                        OptimisticLockConflictEvent.PHASE_INSERT_TOCTOU,
                        snapshot.schemaVersion());
                throw FlowEngineException.optimisticLockConflict(
                        engineName, snapshot.schemaVersion(), ppe);
            }
            throw ppe;
        }
    }

    /**
     * Returns {@code true} if the given exception (or any cause in its chain) carries a
     * SQLState in the ANSI integrity-constraint class (prefix {@code 23}). Walks the
     * cause chain because the SPI wraps the original {@code SQLException} inside a
     * {@code PersistenceProviderException}.
     */
    private static boolean isIntegrityConstraintViolation(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith(SQLSTATE_CLASS_INTEGRITY)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extracts the first non-null SQLSTATE found while walking the cause chain.
     * Used by {@link FlowSnapshotSaveFailedEvent} (JFR-091) for operator-facing
     * diagnostics. Returns {@link FlowSnapshotSaveFailedEvent#SQLSTATE_UNKNOWN}
     * when no {@link SQLException} carries a SQLSTATE — keeps the JFR field
     * present (never null) for analyser tooling.
     */
    private static String extractSqlState(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && !state.isBlank()) {
                    return state;
                }
            }
        }
        return FlowSnapshotSaveFailedEvent.SQLSTATE_UNKNOWN;
    }

    @Override
    public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
        try (PersistenceConnection conn = engine.openConnection();
             PersistenceStatement stmt = conn.prepare(SQL_SELECT_BY_PK)) {
            stmt.bindLong(0, instanceIdMost);
            stmt.bindLong(1, instanceIdLeast);
            try (QueryResult result = stmt.executeQuery()) {
                if (result.next()) {
                    return Optional.of(JdbcFlowSnapshotCodec.readSnapshot(result.row()));
                }
                return Optional.empty();
            }
        } catch (PersistenceProviderException ppe) {
            throw new FlowEngineException("Failed to load flow snapshot", ppe);
        }
    }

    @Override
    public void delete(long instanceIdMost, long instanceIdLeast) {
        try (PersistenceConnection conn = engine.openConnection();
             PersistenceStatement stmt = conn.prepare(SQL_DELETE)) {
            stmt.bindLong(0, instanceIdMost);
            stmt.bindLong(1, instanceIdLeast);
            stmt.executeUpdate();
        } catch (PersistenceProviderException ppe) {
            throw new FlowEngineException("Failed to delete flow snapshot", ppe);
        }
    }

    @Override
    public boolean exists(long instanceIdMost, long instanceIdLeast) {
        try (PersistenceConnection conn = engine.openConnection()) {
            return existsInTransaction(conn, instanceIdMost, instanceIdLeast);
        } catch (PersistenceProviderException ppe) {
            throw new FlowEngineException("Failed to query snapshot existence", ppe);
        }
    }

    @Override
    public List<FlowSnapshot> listParked() {
        try (PersistenceConnection conn = engine.openConnection();
             PersistenceStatement stmt = conn.prepare(SQL_LIST_PARKED);
             QueryResult result = stmt.executeQuery()) {
            List<FlowSnapshot> parked = new ArrayList<>();
            while (result.next()) {
                parked.add(JdbcFlowSnapshotCodec.readSnapshot(result.row()));
            }
            return parked;
        } catch (PersistenceProviderException ppe) {
            throw new FlowEngineException("Failed to enumerate parked snapshots", ppe);
        }
    }

    private long tryOptimisticUpdate(PersistenceConnection conn, FlowSnapshot snapshot) {
        try (PersistenceStatement stmt = conn.prepare(SQL_UPDATE_OCC)) {
            // SET clause bindings (0..7): definition_name, current_step, state,
            // last_update, timeout_at, compensation_stack, stack_pointer, opaque_state.
            JdbcFlowSnapshotCodec.bindSnapshotPayload(stmt, snapshot, 0);
            // WHERE clause bindings (8..10): instance_id_most, instance_id_least, schema_version.
            stmt.bindLong(8, snapshot.instanceIdMost());
            stmt.bindLong(9, snapshot.instanceIdLeast());
            stmt.bindLong(10, snapshot.schemaVersion());
            return stmt.executeUpdate();
        }
    }

    private void insertSnapshot(PersistenceConnection conn, FlowSnapshot snapshot) {
        try (PersistenceStatement stmt = conn.prepare(SQL_INSERT)) {
            stmt.bindLong(0, snapshot.instanceIdMost());
            stmt.bindLong(1, snapshot.instanceIdLeast());
            // definition_name, current_step, state, last_update, timeout_at,
            // compensation_stack, stack_pointer, opaque_state -> bindings 2..9.
            JdbcFlowSnapshotCodec.bindSnapshotPayload(stmt, snapshot, 2);
            // ADR-013 §5: advance the on-disk version by exactly one on every accepted
            // write — INSERT and UPDATE share this semantic, so the caller can blindly
            // bump its local schemaVersion by 1 after any successful save.
            stmt.bindLong(10, snapshot.schemaVersion() + 1L);
            stmt.executeUpdate();
        }
    }

    private static boolean existsInTransaction(PersistenceConnection conn,
                                               long instanceIdMost, long instanceIdLeast) {
        try (PersistenceStatement stmt = conn.prepare(SQL_EXISTS)) {
            stmt.bindLong(0, instanceIdMost);
            stmt.bindLong(1, instanceIdLeast);
            try (QueryResult result = stmt.executeQuery()) {
                return result.next();
            }
        }
    }
}
