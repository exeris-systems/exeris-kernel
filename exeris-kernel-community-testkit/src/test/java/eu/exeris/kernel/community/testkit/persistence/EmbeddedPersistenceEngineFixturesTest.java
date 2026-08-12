/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Factory-level checks only.
 *
 * <p>Booting is deliberately out of reach here: the testkit declares no {@code PersistenceProvider},
 * which is the whole point of the module. That behaviour is covered by the consumer test in
 * {@code exeris-kernel-community}'s test scope, where a provider exists.
 */
@DisplayName("EmbeddedPersistenceEngineFixtures")
class EmbeddedPersistenceEngineFixturesTest {

    @Test
    @DisplayName("inMemoryH2 hands back an unstarted fixture")
    void inMemoryH2ReturnsUnstartedFixture() {
        EmbeddedPersistenceEngineFixture fixture = EmbeddedPersistenceEngineFixtures.inMemoryH2();

        assertThat(fixture).isNotNull();
        assertThat(fixture.isRunning()).isFalse();
    }

    @Test
    @DisplayName("accessors refuse before start rather than handing back a half-built fixture")
    void accessorsRefuseBeforeStart() {
        EmbeddedPersistenceEngineFixture fixture = EmbeddedPersistenceEngineFixtures.inMemoryH2();

        assertThatThrownBy(fixture::engine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been started");
        assertThatThrownBy(fixture::jdbcUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been started");
        assertThatThrownBy(() -> fixture.runInKernelScope(() -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been started");
    }

    @Test
    @DisplayName("closing a fixture that never started is a no-op")
    void closeBeforeStartIsNoOp() {
        EmbeddedPersistenceEngineFixtures.inMemoryH2().close();
    }

    @Test
    @DisplayName("forJdbcUrl rejects a null URL at the factory rather than at boot")
    void forJdbcUrlRejectsNull() {
        assertThatThrownBy(() -> EmbeddedPersistenceEngineFixtures.forJdbcUrl(null, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jdbcUrl");
    }

    @Test
    @DisplayName("forJdbcUrl accepts a caller-supplied URL")
    void forJdbcUrlAcceptsUrl() {
        assertThat(EmbeddedPersistenceEngineFixtures.forJdbcUrl("jdbc:h2:mem:probe", false))
                .isNotNull();
    }
}
