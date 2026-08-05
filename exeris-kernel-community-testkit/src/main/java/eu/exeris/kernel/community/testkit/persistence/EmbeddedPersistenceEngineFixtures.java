/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit.persistence;

import java.util.Objects;

/**
 * Factory for {@link EmbeddedPersistenceEngineFixture} instances.
 *
 * <h2>What the consumer must supply</h2>
 * <p>Two things, neither of which the testkit can provide for you:
 * <ul>
 *   <li>a {@code PersistenceProvider} on the test classpath — {@code exeris-kernel-community} supplies
 *       one via {@code ServiceLoader}; the testkit deliberately does not depend on it, so that a
 *       consumer running an Enterprise or custom provider gets that one instead;</li>
 *   <li>a JDBC driver for the URL in use — {@code com.h2database:h2} for {@link #inMemoryH2()}.</li>
 * </ul>
 * The testkit declares neither. It references no driver class, only a URL string, and a test library
 * has no business putting a database on the classpath of everything that depends on it.
 *
 * @since 0.11.0
 */
public final class EmbeddedPersistenceEngineFixtures {

    /**
     * H2 in PostgreSQL-compatibility mode. {@code DB_CLOSE_DELAY=-1} keeps the in-memory database
     * alive between pooled connections — without it the schema vanishes when the pool briefly drops to
     * zero connections, and the failure looks like a migration that never ran.
     */
    private static final String H2_URL_TEMPLATE =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private EmbeddedPersistenceEngineFixtures() {
    }

    /**
     * A fixture on a fresh in-memory H2 database, migrations applied.
     *
     * <p>The database name is unique per call, so two fixtures never share state — including under
     * parallel test execution, where {@code FixtureBootLock} keeps concurrent starts from reading each
     * other's JDBC URL.
     *
     * @return an unstarted fixture
     */
    public static EmbeddedPersistenceEngineFixture inMemoryH2() {
        return new KernelBootstrapPersistenceEngineFixture(
                String.format(H2_URL_TEMPLATE, uniqueDatabaseName()), true);
    }

    /**
     * A fixture against a JDBC URL the caller supplies — Postgres in a container, say.
     *
     * @param jdbcUrl       the JDBC URL to boot against
     * @param runMigrations whether the engine should apply its own DDL. Pass {@code false} only when
     *                      the schema is already installed; the engine defaults to <em>not</em>
     *                      migrating, which is the step most easily missed when standing it up by hand
     * @return an unstarted fixture
     */
    public static EmbeddedPersistenceEngineFixture forJdbcUrl(String jdbcUrl, boolean runMigrations) {
        return new KernelBootstrapPersistenceEngineFixture(
                Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null"), runMigrations);
    }

    private static String uniqueDatabaseName() {
        return "exeris_testkit_" + Long.toUnsignedString(System.nanoTime(), 36);
    }
}
