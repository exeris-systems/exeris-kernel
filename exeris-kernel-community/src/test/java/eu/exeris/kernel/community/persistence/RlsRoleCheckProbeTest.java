/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The probe and the pool verdict of {@link RlsRoleCheck} against a scripted JDBC connection, so the
 * branches the PostgreSQL integration test reaches are also pinned in the default build.
 */
@DisplayName("RlsRoleCheck — probe and pool verdict against a scripted connection")
class RlsRoleCheckProbeTest {

    private static final String PROVIDER = "ExerisCommunity/JDBC";
    private static final String URL = "jdbc:postgresql://db/app";

    @Test
    @DisplayName("a database that is not PostgreSQL is not probed")
    void otherDatabaseIsNotProbed() throws SQLException {
        Script script = new Script("H2", null);

        assertThat(RlsRoleCheck.probe(script.connection())).isEmpty();
        assertThat(script.statements.get()).as("no query is prepared").isZero();
    }

    @Test
    @DisplayName("PostgreSQL answers the role's attributes, and the transaction is rolled back")
    void postgresAnswersAttributesAndRollsBack() throws SQLException {
        Script script = new Script("PostgreSQL", new Row("app_owner", false, true));

        Optional<RlsRoleCheck.RoleAttributes> probed = RlsRoleCheck.probe(script.connection());

        assertThat(probed).contains(new RlsRoleCheck.RoleAttributes("app_owner", false, true));
        assertThat(script.rollbacks.get()).as("the probe leaves no open transaction").isEqualTo(1);
        assertThat(script.closed.get()).as("statement and result set are closed").isEqualTo(2);
    }

    @Test
    @DisplayName("a role with no pg_roles row is a probe failure")
    void missingRowIsAFailure() {
        Script script = new Script("PostgreSQL", null);

        assertThatThrownBy(() -> RlsRoleCheck.probe(script.connection()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("pg_roles");
    }

    @Test
    @DisplayName("a probe that cannot run refuses with EX-PERS-5001 whatever the consent")
    void failedProbeRefusesWithBootstrapFailure() {
        DataSource failing = dataSource(() -> {
            throw new SQLException("connection refused");
        });

        assertThatThrownBy(() -> RlsRoleCheck.verifyPool(failing, true, PROVIDER, URL))
                .isInstanceOfSatisfying(PersistenceProviderException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5001));
    }

    @Test
    @DisplayName("a pool whose role bypasses RLS is refused without consent")
    void bypassingPoolIsRefusedWithoutConsent() {
        Script script = new Script("PostgreSQL", new Row("postgres", true, true));

        assertThatThrownBy(() -> RlsRoleCheck.verifyPool(dataSource(script::connection), false, PROVIDER, URL))
                .isInstanceOfSatisfying(PersistenceProviderException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5009);
                    assertThat(refusal.rawArgs()).containsExactly(PROVIDER, "postgres", "SUPERUSER,BYPASSRLS");
                });
    }

    @Test
    @DisplayName("a pool whose role bypasses RLS runs with consent")
    void bypassingPoolRunsWithConsent() {
        Script script = new Script("PostgreSQL", new Row("postgres", false, true));

        assertThatCode(() -> RlsRoleCheck.verifyPool(dataSource(script::connection), true, PROVIDER, URL))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a pool whose role bypasses nothing passes without consent")
    void ordinaryPoolPasses() {
        Script script = new Script("PostgreSQL", new Row("app", false, false));

        assertThatCode(() -> RlsRoleCheck.verifyPool(dataSource(script::connection), false, PROVIDER, URL))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ scripted JDBC

    private record Row(String role, boolean superuser, boolean bypassRls) {
    }

    @FunctionalInterface
    private interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private static DataSource dataSource(ConnectionSource source) {
        return (DataSource) Proxy.newProxyInstance(RlsRoleCheckProbeTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return source.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A connection reporting one product name whose probe query returns {@code row}, or no row. */
    private static final class Script {
        private final String product;
        private final Row row;
        private final AtomicInteger statements = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();

        private Script(String product, Row row) {
            this.product = product;
            this.row = row;
        }

        private Connection connection() {
            DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (name, args) -> {
                if ("getDatabaseProductName".equals(name)) {
                    return product;
                }
                throw new UnsupportedOperationException(name);
            });
            return proxy(Connection.class, (name, args) -> switch (name) {
                case "getMetaData" -> metaData;
                case "prepareStatement" -> {
                    statements.incrementAndGet();
                    yield statement();
                }
                case "rollback" -> {
                    rollbacks.incrementAndGet();
                    yield null;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(name);
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (name, args) -> switch (name) {
                case "executeQuery" -> resultSet();
                case "close" -> {
                    closed.incrementAndGet();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(name);
            });
        }

        private ResultSet resultSet() {
            AtomicInteger cursor = new AtomicInteger();
            return proxy(ResultSet.class, (name, args) -> switch (name) {
                case "next" -> row != null && cursor.getAndIncrement() == 0;
                case "getString" -> row.role();
                case "getBoolean" -> (Integer) args[0] == 2 ? row.superuser() : row.bypassRls();
                case "close" -> {
                    closed.incrementAndGet();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(name);
            });
        }
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args) throws SQLException;
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(RlsRoleCheckProbeTest.class.getClassLoader(),
                new Class<?>[] {type}, (proxy, method, args) -> answer.answer(method.getName(), args)));
    }
}
