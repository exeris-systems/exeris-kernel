/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Community-internal SQL migration bootstrap helper. Extracted from
 * {@link CommunityPersistenceEngine} in QA-010 (v0.8 Sprint 1) to reduce the engine's
 * responsibility surface — the engine owns connection lifecycle and admission control;
 * this helper owns reading SQL resources from the classpath, ordering them, and
 * splitting each into statements. Deciding which migrations still need applying, and
 * applying each in its own transaction, is {@link SchemaMigrationApplier}'s job
 * (ADR-073).
 *
 * <h2>Workflow</h2>
 * <p>{@link #runIfEnabled(boolean, DataSource, List, String, String)} is a no-op when
 * the run-migrations flag is {@code false}; otherwise it acquires a single connection
 * from the supplied pool, orders {@code resources} by migration version — not
 * {@link Comparator#naturalOrder() natural string order} of the resource path, see
 * {@link #MIGRATION_ORDER} — and hands the connection and ordered list to
 * {@link SchemaMigrationApplier#applyPending}, which applies every migration the
 * ledger does not already carry, each in its own transaction.
 *
 * <h2>SQL splitting</h2>
 * <p>Statement boundaries are detected on top-level {@code ;} characters outside
 * single-quoted string literals. Single-line comments ({@code --}) are stripped before
 * the trimmed candidate is emitted. The semantics match the legacy in-engine logic
 * verbatim — see {@code CommunityPersistenceEngineMigrationTest} for the locked-in
 * behaviour against the {@code V0.5.0__create_outbox.sql} and
 * {@code V0.7.0__create_saga_state.sql} migration scripts.
 *
 * <h2>Error handling</h2>
 * <p>Any {@link SQLException}, {@link IllegalStateException} (missing resource), or
 * {@link UncheckedIOException} (read failure) surfaces as
 * {@link PersistenceProviderException#bootstrapFailure(String, String, Throwable)}
 * carrying the provider identity and JDBC URL so operators can trace the failure to a
 * specific bootstrap.
 *
 * @since 0.8
 */
// The class total used to need a CyclomaticComplexity suppression, dominated by the SQL splitter
// (`splitSqlStatements` + `stripLineComments` + `addStatement`) — a single-quote-aware per-character
// scanner that cannot be decomposed without fragmenting the state machine. Moving the apply loop to
// `SchemaMigrationApplier` (ADR-073) dropped the total under the threshold and the suppression with
// it; PMD's own `UnnecessaryWarningSuppression` is what caught that it had become dead.
final class CommunityPersistenceMigrationRunner {

    /**
     * Orders migrations by their {@code V<major>.<minor>.<patch>} version, numerically — not by
     * {@link Comparator#naturalOrder() natural string order} of the resource path, under which
     * {@code V0.10.0} sorts before {@code V0.5.0} because {@code '1' < '5'} and a migration that
     * depends on an earlier one's table could run first.
     *
     * <p>Anything that does not parse as a version sorts last, by path, rather than throwing: a
     * migration runner is not the right place to fail a boot over a file name.
     */
    /* default */ static final Comparator<String> MIGRATION_ORDER =
            Comparator.comparing(CommunityPersistenceMigrationRunner::versionKey)
                    .thenComparing(Comparator.naturalOrder());

    /* default */ static final Pattern VERSION_PATTERN = Pattern.compile("V(\\d+)\\.(\\d+)\\.(\\d+)__");

    private CommunityPersistenceMigrationRunner() {
        // utility — no instances
    }

    /**
     * Extracts a numerically comparable key from a migration resource path.
     *
     * @param resourcePath the classpath resource path
     * @return a comparable version key; {@link Long#MAX_VALUE} when the path carries no version
     */
    private static Long versionKey(String resourcePath) {
        Matcher matcher = VERSION_PATTERN.matcher(resourcePath);
        if (!matcher.find()) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(matcher.group(1)) * 1_000_000L
                + Long.parseLong(matcher.group(2)) * 1_000L
                + Long.parseLong(matcher.group(3));
    }

    /**
     * Runs every migration in {@code resources} (ordered by semantic version) against one
     * connection acquired from {@code dataSource}, applying each migration the ledger does not
     * already carry in its own transaction ({@link SchemaMigrationApplier}, ADR-073). Returns
     * immediately when {@code enabled} is {@code false}.
     *
     * @param enabled        whether migrations should run (resolved from config)
     * @param dataSource     pooled JDBC datasource; one connection is acquired and released
     * @param resources      classpath migration resource paths (e.g. {@code db/migration/V…sql})
     * @param providerId     persistence-provider identifier used in bootstrap-failure reporting
     * @param connectionUrl  JDBC URL used in bootstrap-failure reporting
     * @throws PersistenceProviderException ({@code EX-PERS-5001}) when a resource cannot be
     *         read or any statement fails to execute; the underlying cause is preserved
     */
    /* default */ static void runIfEnabled(boolean enabled,
                                           DataSource dataSource,
                             List<String> resources,
                             String providerId,
                             String connectionUrl) {
        if (!enabled) {
            return;
        }
        List<String> sorted = new ArrayList<>(resources);
        sorted.sort(MIGRATION_ORDER);
        try (Connection connection = dataSource.getConnection()) {
            SchemaHistoryLedger.ensureTable(connection);
            SchemaMigrationApplier.applyPending(connection, sorted);
        } catch (SQLException | IllegalStateException | UncheckedIOException ex) {
            throw PersistenceProviderException.bootstrapFailure(providerId, connectionUrl, ex);
        }
    }

    /* default */ static void executeStatements(Connection connection, String migrationSql) throws SQLException {
        for (String statementSql : splitSqlStatements(migrationSql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    @SuppressWarnings("PMD.LawOfDemeter")
    /* default */ static String readMigrationResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing SQL migration resource: " + resourcePath);
            }
            byte[] bytes = inputStream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ioEx) {
            throw new UncheckedIOException("Failed to read SQL migration resource: " + resourcePath, ioEx);
        }
    }

    private static List<String> splitSqlStatements(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        StringBuilder current = new StringBuilder(sql.length());
        List<String> statements = new ArrayList<>();
        boolean inSingleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char currentChar = sql.charAt(i);
            if (currentChar == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
            }
            if (currentChar == ';' && !inSingleQuote) {
                addStatement(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(currentChar);
        }
        addStatement(statements, current);
        return Collections.unmodifiableList(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String candidate = stripLineComments(current.toString()).trim();
        if (!candidate.isEmpty()) {
            statements.add(candidate);
        }
    }

    private static String stripLineComments(String sql) {
        String[] lines = sql.split("\\R");
        StringBuilder builder = new StringBuilder(sql.length());
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }
}
