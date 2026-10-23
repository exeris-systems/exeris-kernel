/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.persistence.RlsBypassRoleEvent;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

import com.zaxxer.hikari.pool.HikariPool;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Detects a database role that bypasses row-level security and decides between refusing and
 * reporting it (ADR-012 §4c.4).
 *
 * <p>A role that is {@code SUPERUSER} or has {@code BYPASSRLS} skips every policy, {@code FORCE}d
 * ones included, so tenant isolation would rest on application code alone. The probe reads the
 * role's own {@code pg_roles} row, which any role may do, and runs once per pool rather than per
 * request.
 *
 * <p>Detection is by {@link java.sql.DatabaseMetaData#getDatabaseProductName()} rather than by URL
 * prefix, so a wrapping driver whose URL does not start with {@code jdbc:postgresql:} is still
 * recognised. Other databases have no equivalent attribute and are skipped.
 */
final class RlsRoleCheck {

    private static final System.Logger LOG = System.getLogger(RlsRoleCheck.class.getName());
    private static final String POSTGRESQL_PRODUCT = "PostgreSQL";
    private static final String PROBE_SQL =
            "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user";

    private RlsRoleCheck() {
    }

    /**
     * The role a pool connects as and the two attributes that make it skip row-level security.
     *
     * @param roleName    the role name as the database reports it
     * @param superuser   {@code rolsuper}
     * @param bypassRls   {@code rolbypassrls}
     */
    /* default */ record RoleAttributes(String roleName, boolean superuser, boolean bypassRls) {

        /** Whether either attribute lets the role skip row-level security. */
        /* default */ boolean bypasses() {
            return superuser || bypassRls;
        }

        /** The attribute names that apply, comma-separated; empty when the role bypasses nothing. */
        /* default */ String attribute() {
            if (superuser && bypassRls) {
                return "SUPERUSER,BYPASSRLS";
            }
            if (superuser) {
                return "SUPERUSER";
            }
            return bypassRls ? "BYPASSRLS" : "";
        }
    }

    /**
     * Reads the connected role's bypass attributes.
     *
     * <p>Rolls back afterwards because the pools run with {@code autoCommit=false}; the connection
     * would otherwise return to the pool inside an open transaction.
     *
     * @param connection a live connection; not closed here
     * @return the attributes, or empty when the database is not PostgreSQL
     * @throws SQLException if the probe cannot run or the role has no {@code pg_roles} row
     */
    /* default */ static Optional<RoleAttributes> probe(Connection connection) throws SQLException {
        if (!POSTGRESQL_PRODUCT.equals(connection.getMetaData().getDatabaseProductName())) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement(PROBE_SQL);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("pg_roles has no row for the connected role");
            }
            RoleAttributes attributes = new RoleAttributes(
                    rows.getString(1), rows.getBoolean(2), rows.getBoolean(3));
            connection.rollback();
            return Optional.of(attributes);
        }
    }

    /**
     * Probes the role behind a pool and translates a probe failure into a boot refusal.
     *
     * <p>A probe that cannot answer is refused rather than assumed harmless: the consent given by
     * {@code persistence.rlsAllowBypassRole} covers a role known to bypass, not one whose
     * attributes are unknown.
     *
     * @param pool          the pool to take one connection from
     * @param providerId    provider id carried into the exception
     * @param connectionUrl the pool's URL, sanitised by the exception factory
     * @return the attributes, or empty when the database is not PostgreSQL
     * @throws PersistenceProviderException {@code EX-PERS-5001} when the probe cannot run
     */
    /* default */ static Optional<RoleAttributes> probePool(DataSource pool, String providerId, String connectionUrl) {
        try (Connection connection = pool.getConnection()) {
            return probe(connection);
        } catch (SQLException | HikariPool.PoolInitializationException failure) {
            throw PersistenceProviderException.bootstrapFailure(providerId, connectionUrl, failure);
        }
    }

    /**
     * Probes the role behind a pool and applies the operator's consent: a bypassing role is refused,
     * or, with consent, reported.
     *
     * @param pool          the pool to take one connection from
     * @param allow         {@code persistence.rlsAllowBypassRole}
     * @param providerId    provider id carried into the exception and the event
     * @param connectionUrl the pool's URL, sanitised by the exception factory
     * @throws PersistenceProviderException {@code EX-PERS-5009} for a bypassing role without consent,
     *         {@code EX-PERS-5001} when the probe cannot run
     */
    /* default */ static void verifyPool(DataSource pool, boolean allow, String providerId, String connectionUrl) {
        Optional<RoleAttributes> probed = probePool(pool, providerId, connectionUrl);
        if (probed.isEmpty() || !probed.get().bypasses()) {
            return;
        }
        refuseUnlessAllowed(probed.get(), allow, providerId);
        reportDowngrade(probed.get(), providerId);
    }

    /**
     * Refuses a bypassing role unless the operator consented.
     *
     * @param attributes the probed role
     * @param allow      {@code persistence.rlsAllowBypassRole}
     * @param providerId provider id carried into the exception
     * @throws PersistenceProviderException {@code EX-PERS-5009} when the role bypasses and
     *         {@code allow} is {@code false}
     */
    /* default */ static void refuseUnlessAllowed(RoleAttributes attributes, boolean allow, String providerId) {
        if (attributes.bypasses() && !allow) {
            throw refusal(attributes, providerId);
        }
    }

    /**
     * Builds the refusal for a bypassing role.
     *
     * @param attributes the probed role
     * @param providerId provider id carried into the exception
     * @return {@code EX-PERS-5009}
     */
    /* default */ static PersistenceProviderException refusal(RoleAttributes attributes, String providerId) {
        return PersistenceProviderException.rlsBypassRole(
                providerId, attributes.roleName(), attributes.attribute());
    }

    /**
     * Records that a bypassing role runs with the operator's consent: one error log line and one
     * {@link RlsBypassRoleEvent}.
     *
     * @param attributes the probed role
     * @param providerId provider id carried into the event
     */
    /* default */ static void reportDowngrade(RoleAttributes attributes, String providerId) {
        LOG.log(System.Logger.Level.ERROR,
                "Role ''{0}'' has {1} and bypasses row-level security; tenant isolation rests on application "
                        + "code. Allowed by persistence.rlsAllowBypassRole=true.",
                attributes.roleName(), attributes.attribute());
        RlsBypassRoleEvent.commitDowngrade(
                new RlsBypassRoleEvent.Payload(providerId, attributes.roleName(), attributes.attribute()));
    }
}
