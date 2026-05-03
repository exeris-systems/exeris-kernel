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
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable JDBC implementation of {@link FlowSnapshotStore} backed by the
 * {@code exeris_saga_state} table (see ADR-013, FLOW-103).
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
 * <p>This class is thread-safe by virtue of using a fresh connection per call.
 * The contract assumes {@code dataSource} is a connection pool (HikariCP in
 * Community); callers MUST NOT share a single physical connection across
 * concurrent invocations.
 *
 * <h2>Hot-path Discipline (ADR-013 §7)</h2>
 * <ul>
 *   <li>No {@code ThreadLocal} for context propagation.</li>
 *   <li>No {@code ByteBuffer.allocate(int)} on the hot path; {@code ByteBuffer.wrap(byte[])}
 *       wraps an already-allocated array.</li>
 *   <li>{@code DataSource} ownership stays with the bootstrapper that constructed this store.</li>
 * </ul>
 *
 * @since 0.7.0
 */
@SuppressWarnings({
        // Durable JDBC store inherently exposes the full FlowSnapshotStore SPI plus internal
        // helpers for the two-step UPDATE-then-INSERT pattern. Decomposition is tracked under
        // Sprint 8 SQ-006 (alongside CoreFlowRuntime) — premature splitting here would
        // fragment the OCC contract documented in ADR-013 §5.
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity",
        // `ps` / `rs` / `sp` are the de-facto JDBC convention (PreparedStatement / ResultSet /
        // stack-pointer); renaming them to longer identifiers obscures rather than clarifies.
        "PMD.ShortVariable"
})
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

    private final DataSource dataSource;
    private final String engineName;

    public JdbcFlowSnapshotStore(DataSource dataSource, String engineName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int affected = tryOptimisticUpdate(conn, snapshot);
                if (affected == 0) {
                    if (existsInTransaction(conn, snapshot.instanceIdMost(), snapshot.instanceIdLeast())) {
                        throw FlowEngineException.optimisticLockConflict(engineName, snapshot.schemaVersion());
                    }
                    insertOrRemapPkConflict(conn, snapshot);
                }
                conn.commit();
            } catch (FlowEngineException occ) {
                conn.rollback();
                throw occ;
            } catch (SQLException ex) {
                conn.rollback();
                throw new FlowEngineException("Failed to save flow snapshot", ex);
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException ex) {
            throw new FlowEngineException("Failed to acquire connection for save", ex);
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
    private void insertOrRemapPkConflict(Connection conn, FlowSnapshot snapshot) throws SQLException {
        try {
            insertSnapshot(conn, snapshot);
        } catch (SQLException pkViolation) {
            if (isIntegrityConstraintViolation(pkViolation)) {
                throw FlowEngineException.optimisticLockConflict(
                        engineName, snapshot.schemaVersion(), pkViolation);
            }
            throw pkViolation;
        }
    }

    /**
     * Returns {@code true} if the given exception's SQLState is an ANSI integrity-constraint
     * class (prefix {@code 23}) — covers Postgres {@code 23505} (unique_violation),
     * H2 {@code 23505}, and any spec-conformant driver.
     */
    private static boolean isIntegrityConstraintViolation(SQLException ex) {
        for (Throwable cur = ex; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Optional<FlowSnapshot> load(long instanceIdMost, long instanceIdLeast) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_PK)) {
            ps.setLong(1, instanceIdMost);
            ps.setLong(2, instanceIdLeast);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readSnapshot(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException ex) {
            throw new FlowEngineException("Failed to load flow snapshot", ex);
        }
    }

    @Override
    public void delete(long instanceIdMost, long instanceIdLeast) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE)) {
            ps.setLong(1, instanceIdMost);
            ps.setLong(2, instanceIdLeast);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new FlowEngineException("Failed to delete flow snapshot", ex);
        }
    }

    @Override
    public boolean exists(long instanceIdMost, long instanceIdLeast) {
        try (Connection conn = dataSource.getConnection()) {
            return existsInTransaction(conn, instanceIdMost, instanceIdLeast);
        } catch (SQLException ex) {
            throw new FlowEngineException("Failed to query snapshot existence", ex);
        }
    }

    @Override
    public List<FlowSnapshot> listParked() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_LIST_PARKED);
             ResultSet rs = ps.executeQuery()) {
            List<FlowSnapshot> parked = new ArrayList<>();
            while (rs.next()) {
                parked.add(readSnapshot(rs));
            }
            return parked;
        } catch (SQLException ex) {
            throw new FlowEngineException("Failed to enumerate parked snapshots", ex);
        }
    }

    private int tryOptimisticUpdate(Connection conn, FlowSnapshot snapshot) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_OCC)) {
            // SET clause bindings (1..8): definition_name, current_step, state,
            // last_update, timeout_at, compensation_stack, stack_pointer, opaque_state.
            bindSnapshotPayload(ps, snapshot, 1);
            // WHERE clause bindings (9..11): instance_id_most, instance_id_least, schema_version.
            ps.setLong(9, snapshot.instanceIdMost());
            ps.setLong(10, snapshot.instanceIdLeast());
            ps.setLong(11, snapshot.schemaVersion());
            return ps.executeUpdate();
        }
    }

    private void insertSnapshot(Connection conn, FlowSnapshot snapshot) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
            ps.setLong(1, snapshot.instanceIdMost());
            ps.setLong(2, snapshot.instanceIdLeast());
            // definition_name, current_step, state, last_update, timeout_at,
            // compensation_stack, stack_pointer, opaque_state -> bindings 3..10.
            bindSnapshotPayload(ps, snapshot, 3);
            // ADR-013 §5: advance the on-disk version by exactly one on every accepted
            // write — INSERT and UPDATE share this semantic, so the caller can blindly
            // bump its local schemaVersion by 1 after any successful save.
            ps.setLong(11, snapshot.schemaVersion() + 1L);
            ps.executeUpdate();
        }
    }

    /**
     * Binds the eight payload columns (definition_name through opaque_state) starting at
     * the given JDBC parameter index. Used by both INSERT (offset 3) and UPDATE (offset 1).
     */
    @SuppressWarnings("PMD.AssignmentInOperand") // idx++ is the canonical JDBC binding pattern
    private static void bindSnapshotPayload(PreparedStatement ps, FlowSnapshot snapshot,
                                            int startIndex) throws SQLException {
        int idx = startIndex;
        ps.setString(idx++, snapshot.definitionName());
        ps.setInt(idx++, snapshot.currentStep());
        ps.setString(idx++, snapshot.state().name());
        ps.setTimestamp(idx++, Timestamp.from(snapshot.lastUpdate()));
        // Instant.MAX cannot fit in TIMESTAMPTZ (4713 BC..294276 AD); encode as NULL and
        // decode back to Instant.MAX in readSnapshot. Matches RuntimeFlowInstance.fromSnapshot
        // semantics for "no timeout".
        Instant timeout = snapshot.timeout();
        if (Instant.MAX.equals(timeout)) {
            ps.setNull(idx++, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(idx++, Timestamp.from(timeout));
        }
        ps.setBytes(idx++, packCompensationStack(snapshot));
        ps.setInt(idx++, snapshot.stackPointer());
        byte[] opaque = snapshot.opaqueState();
        if (opaque.length == 0) {
            ps.setNull(idx, java.sql.Types.VARBINARY);
        } else {
            ps.setBytes(idx, opaque);
        }
    }

    private static byte[] packCompensationStack(FlowSnapshot snapshot) {
        int sp = snapshot.stackPointer();
        if (sp == 0) {
            return new byte[0];
        }
        int[] stack = snapshot.compensationStack();
        byte[] bytes = new byte[sp * 4];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int i = 0; i < sp; i++) {
            buf.putInt(stack[i]);
        }
        return bytes;
    }

    private static int[] unpackCompensationStack(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new int[0];
        }
        int count = bytes.length / 4;
        int[] result = new int[count];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int i = 0; i < count; i++) {
            result[i] = buf.getInt();
        }
        return result;
    }

    // ResultSet.getTimestamp returns java.sql.Timestamp by JDBC contract; we convert
    // to Instant on the next line, never holding the Timestamp beyond decoding.
    @SuppressWarnings("PMD.ReplaceJavaUtilDate")
    private static FlowSnapshot readSnapshot(ResultSet rs) throws SQLException {
        long instanceIdMost = rs.getLong("instance_id_most");
        long instanceIdLeast = rs.getLong("instance_id_least");
        String definitionName = rs.getString("definition_name");
        int currentStep = rs.getInt("current_step");
        FlowState state = FlowState.valueOf(rs.getString("state"));
        Instant lastUpdate = rs.getTimestamp("last_update").toInstant();
        Timestamp timeoutTs = rs.getTimestamp("timeout_at");
        Instant timeout = timeoutTs == null ? Instant.MAX : timeoutTs.toInstant();
        int[] compensationStack = unpackCompensationStack(rs.getBytes("compensation_stack"));
        int stackPointer = rs.getInt("stack_pointer");
        byte[] opaqueState = rs.getBytes("opaque_state");
        if (opaqueState == null) {
            opaqueState = new byte[0];
        }
        long schemaVersion = rs.getLong("schema_version");
        return new FlowSnapshot(
                instanceIdMost, instanceIdLeast,
                definitionName, currentStep, state,
                lastUpdate, timeout,
                compensationStack, stackPointer,
                opaqueState, schemaVersion);
    }

    private static boolean existsInTransaction(Connection conn, long instanceIdMost, long instanceIdLeast)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_EXISTS)) {
            ps.setLong(1, instanceIdMost);
            ps.setLong(2, instanceIdLeast);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
