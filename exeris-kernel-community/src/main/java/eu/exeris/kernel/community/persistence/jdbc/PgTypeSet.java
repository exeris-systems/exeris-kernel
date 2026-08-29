/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * The set of column types whose {@code getString} rendering this driver contracts (ADR-080 §2).
 *
 * <h2>Why a name set and not a type code</h2>
 * <p>Measured against PostgreSQL 17 through pgjdbc, not reconstructed: {@code bool} — which the set
 * contains — and {@code bit} — which it does not — are both reported as {@link java.sql.Types#BIT},
 * so a JDBC type code cannot separate them. A native {@code enum} is reported as
 * {@link java.sql.Types#VARCHAR} under the application's own type name ({@code mood}, say), so no
 * code and no OID range catches it either, while a name set does: {@code mood} is simply absent.
 * That is ADR-080's "per declared type, never per OID range", and it is the rule because the
 * alternatives were measured and found unable to express it.
 *
 * <h2>Why this is scoped to one engine</h2>
 * <p>ADR-080 §2 contracts <em>the server's</em> {@code <type>_out} rendering over a set measured on
 * PostgreSQL. That contract is not engine-portable, and the failure is not hypothetical: H2 in
 * PostgreSQL compatibility mode renders {@code bool} as {@code TRUE} where PostgreSQL renders
 * {@code t}, and names its types in SQL-standard spellings ({@code CHARACTER VARYING}) that share
 * no vocabulary with the measured set. So the guarantee — and with it the refusal that gives the
 * guarantee its edge — applies where the set was measured. Elsewhere {@code getString} stays the
 * JDBC pass-through it has always been, and the subsystem doc says so rather than implying a
 * promise the driver cannot keep.
 *
 * @since 0.12.0
 */
final class PgTypeSet {

    /**
     * Tier A and Tier B of {@code docs/rowcursor-type-set.md}, in the names pgjdbc reports.
     *
     * <p>Adding a name here is a change to ADR-080's set, not a bug fix: each entry claims the
     * driver renders that type exactly as the server does, and the type-set TCK is what proves it.
     */
    private static final Set<String> RENDERED = Set.of(
            // Tier A — numeric
            "int2", "int4", "int8", "oid", "numeric", "float4", "float8",
            // Tier A — boolean and character
            "bool", "text", "varchar", "bpchar",
            // Tier A — binary, identifier, structured
            "bytea", "uuid", "json", "jsonb",
            // Tier A — temporal
            "date", "time", "timestamp",
            // Tier B — gated, but rendered
            "timestamptz", "timetz", "interval", "name", "xml", "char");

    private static final String CONTRACTED_ENGINE = "postgresql";

    private PgTypeSet() {
        // Static type-set lookup — never instantiated.
    }

    /**
     * Whether {@code getString} contracts a rendering for the given declared type.
     *
     * @param declaredTypeName the driver's name for the column type; may be {@code null}
     * @return {@code true} when the type is in Tier A or Tier B of the measured set
     */
    /* default */ static boolean renders(String declaredTypeName) {
        return declaredTypeName != null
                && RENDERED.contains(declaredTypeName.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this result set's engine is the one ADR-080 §2 measured.
     *
     * <p>A driver that cannot answer is treated as not contracted: an engine we cannot identify is
     * one whose rendering we have not measured, and refusing every column on it would break a
     * working deployment to enforce a guarantee that was never made about it.
     *
     * @param resultSet the result set whose connection is inspected
     * @return {@code true} for PostgreSQL
     * @throws SQLException if the driver fails while reporting its own identity
     */
    /* default */ static boolean isContractedEngine(ResultSet resultSet) throws SQLException {
        if (resultSet.getStatement() == null || resultSet.getStatement().getConnection() == null) {
            return false;
        }
        String product = resultSet.getStatement().getConnection().getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(Locale.ROOT).contains(CONTRACTED_ENGINE);
    }
}
