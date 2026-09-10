/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.runtime;

import java.util.Objects;
import java.util.Set;

/**
 * Factory for {@link EmbeddedKernelFixture} instances.
 *
 * <h2>What the consumer must supply</h2>
 * <p>Two things, neither of which the testkit can provide:
 * <ul>
 *   <li>the providers on the test classpath — {@code exeris-kernel-community} supplies an
 *       {@code EventProvider}, a {@code FlowProvider} and a {@code PersistenceProvider} via
 *       {@code ServiceLoader}; the testkit deliberately depends on none of them, so a consumer running
 *       an Enterprise or custom provider gets that one instead;</li>
 *   <li>a JDBC driver for the URL in use — {@code com.h2database:h2} for the {@code …OnH2} factories.</li>
 * </ul>
 * The testkit declares neither. It references no driver class and no Community type, only subsystem
 * names and a URL string.
 *
 * @since 0.12
 */
public final class EmbeddedKernelFixtures {

    /**
     * H2 in PostgreSQL-compatibility mode. {@code DB_CLOSE_DELAY=-1} keeps the in-memory database alive
     * between pooled connections — without it the schema vanishes when the pool briefly drops to zero,
     * and the failure looks like a migration that never ran.
     */
    private static final String H2_URL_TEMPLATE = "jdbc:h2:mem:%s;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private EmbeddedKernelFixtures() {
    }

    /**
     * The events subsystem on a fresh in-memory H2 database, migrations applied.
     *
     * <p>Pulls {@code persistence} and {@code memory} through dependency closure, so the outbox and
     * event-log tables the engine writes are present and readable through
     * {@link EmbeddedKernelFixture#persistenceEngine()}.
     *
     * @return an unstarted fixture
     */
    public static EmbeddedKernelFixture eventsOnH2() {
        return onH2(Set.of(SelectedEngines.EVENTS));
    }

    /**
     * The flow subsystem on a fresh in-memory H2 database, migrations applied.
     *
     * <p>Pulls {@code persistence} through dependency closure. Saga state lands in the engine's own
     * {@code saga_state} table, which is what makes step ordering, compensation and the optimistic-lock
     * conflict on a stale write assertable rather than mocked.
     *
     * @return an unstarted fixture
     */
    public static EmbeddedKernelFixture flowOnH2() {
        return onH2(Set.of(SelectedEngines.FLOW));
    }

    /**
     * Both subsystems in one runtime, on a fresh in-memory H2 database.
     *
     * <p>The combination is the point rather than a convenience: a saga that emits an event needs both
     * engines out of the <em>same</em> boot, which is how it is wired in production. Two single-subsystem
     * fixtures would be two kernels sharing nothing.
     *
     * @return an unstarted fixture
     */
    public static EmbeddedKernelFixture eventsAndFlowOnH2() {
        return onH2(Set.of(SelectedEngines.EVENTS, SelectedEngines.FLOW));
    }

    /**
     * A fixture over a chosen subsystem set and a JDBC URL the caller supplies — Postgres in a
     * container, say.
     *
     * @param subsystems    subsystem names to select; dependency closure is the orchestrator's job, so
     *                      naming {@code flow} alone is enough to get {@code persistence}
     * @param jdbcUrl       the JDBC URL to boot against
     * @param runMigrations whether the engine should apply its own DDL. Pass {@code false} only when the
     *                      schema is already installed; the engine defaults to <em>not</em> migrating,
     *                      which is the step most easily missed when standing it up by hand
     * @return an unstarted fixture
     */
    public static EmbeddedKernelFixture forJdbcUrl(Set<String> subsystems,
                                                   String jdbcUrl,
                                                   boolean runMigrations) {
        Objects.requireNonNull(subsystems, "subsystems must not be null");
        if (subsystems.isEmpty()) {
            throw new IllegalArgumentException("subsystems must name at least one subsystem");
        }
        return new KernelBootstrapRuntimeFixture(
                Set.copyOf(subsystems),
                Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null"),
                runMigrations);
    }

    private static EmbeddedKernelFixture onH2(Set<String> subsystems) {
        return new KernelBootstrapRuntimeFixture(
                subsystems, String.format(H2_URL_TEMPLATE, uniqueDatabaseName()), true);
    }

    private static String uniqueDatabaseName() {
        return "exeris_testkit_" + Long.toUnsignedString(System.nanoTime(), 36);
    }
}
