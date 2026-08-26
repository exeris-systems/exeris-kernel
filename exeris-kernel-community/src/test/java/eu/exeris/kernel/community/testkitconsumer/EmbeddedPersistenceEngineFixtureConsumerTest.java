/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkitconsumer;

import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixture;
import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixtures;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the persistence fixture the way a downstream consumer would.
 *
 * <h2>Shape of this test, and its one compromise</h2>
 * <p>It imports the testkit and the SPI and <strong>nothing from
 * {@code eu.exeris.kernel.community.*}</strong> — deliberately, because a test that reached into
 * Community internals would prove the fixture works for someone who does not need it. Every type below
 * is one a host runtime outside this repository can also see.
 *
 * <p>The compromise is location: it lives in {@code exeris-kernel-community}'s test scope rather than a
 * separate consumer module, because the testkit builds before Community in the reactor and cannot
 * depend on a provider. The package name keeps the intent legible. A genuinely external consumer module
 * would be stronger and is worth doing if this pattern grows past one subsystem.
 *
 * @since 0.11.0
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Testkit consumer: the persistence fixture boots a real engine")
class EmbeddedPersistenceEngineFixtureConsumerTest {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS testkit_probe (id INT PRIMARY KEY, note VARCHAR(64))";
    private static final String INSERT_ROW = "INSERT INTO testkit_probe (id, note) VALUES (1, 'x')";
    private static final String COUNT_ROWS = "SELECT COUNT(*) FROM testkit_probe";

    @Nested
    @DisplayName("The engine is real, not a double")
    class RealEngine {

        @Test
        @DisplayName("rollback discards the row a committed statement would have kept")
        void rollbackDiscardsWork() {
            try (EmbeddedPersistenceEngineFixture fixture = EmbeddedPersistenceEngineFixtures.inMemoryH2()) {
                fixture.start();
                PersistenceEngine engine = fixture.engine();

                execute(engine, CREATE_TABLE);

                try (PersistenceConnection connection = engine.openConnection()) {
                    connection.beginTransaction();
                    connection.executeUpdate(INSERT_ROW);
                    connection.rollback();
                }

                assertThat(count(engine))
                        .as("a rolled-back insert must leave nothing behind — the property a hand-written "
                                + "double is most likely to get right by assumption and wrong in fact")
                        .isZero();

                try (PersistenceConnection connection = engine.openConnection()) {
                    connection.beginTransaction();
                    connection.executeUpdate(INSERT_ROW);
                    connection.commit();
                }

                assertThat(count(engine))
                        .as("and the same sequence with commit must keep it — without this the assertion "
                                + "above would also pass against an engine that silently writes nothing")
                        .isOne();
            }
        }

        @Test
        @DisplayName("migrations ran, so the engine's own schema is present")
        void migrationsApplied() {
            try (EmbeddedPersistenceEngineFixture fixture = EmbeddedPersistenceEngineFixtures.inMemoryH2()) {
                fixture.start();
                // exeris_outbox ships in the engine's own DDL (V0.5.0). Its presence is what
                // distinguishes "migrations ran" from "the pool connected to an empty database" —
                // the failure mode a consumer hits when run.migrations is left at its false default.
                assertThat(count(fixture.engine(), "SELECT COUNT(*) FROM exeris_outbox"))
                        .as("the engine must have applied its own DDL")
                        .isZero();
            }
        }
    }

    @Nested
    @DisplayName("Kernel scope is reachable for consumer code that needs it")
    class KernelScope {

        @Test
        @DisplayName("runInKernelScope resolves the provider slot the test thread cannot")
        void kernelScopeResolvesProviderSlot() {
            try (EmbeddedPersistenceEngineFixture fixture = EmbeddedPersistenceEngineFixtures.inMemoryH2()) {
                fixture.start();

                AtomicReference<PersistenceEngine> fromScope = new AtomicReference<>();
                fixture.runInKernelScope(() -> fromScope.set(KernelProviders.persistenceEngine()));

                assertThat(fromScope.get())
                        .as("a host runtime's transaction manager resolves the engine from the slot, not "
                                + "from a reference handed to it; if the fixture could not offer that, "
                                + "every such consumer would still be reduced to a double")
                        .isSameAs(fixture.engine());
            }
        }
    }

    private static void execute(PersistenceEngine engine, String sql) {
        try (PersistenceConnection connection = engine.openConnection()) {
            connection.beginTransaction();
            connection.executeUpdate(sql);
            connection.commit();
        }
    }

    private static long count(PersistenceEngine engine) {
        return count(engine, COUNT_ROWS);
    }

    private static long count(PersistenceEngine engine, String sql) {
        try (PersistenceConnection connection = engine.openConnection();
             QueryResult result = connection.executeQuery(sql)) {
            return result.next() ? result.row().getLong(0) : -1L;
        }
    }
}
