/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Surface tests for the factory.
 *
 * <p>These deliberately never call {@code start()}: the testkit declares neither a provider nor a JDBC
 * driver, so nothing here can boot. Booting is proved by the consumer test in
 * {@code exeris-kernel-community}, which has both on its test classpath — the split is the same one
 * {@code EmbeddedPersistenceEngineFixturesTest} already follows.
 */
@DisplayName("EmbeddedKernelFixtures")
class EmbeddedKernelFixturesTest {

    @Test
    @DisplayName("each factory hands back an unstarted fixture")
    void factoriesReturnUnstartedFixtures() {
        assertThat(EmbeddedKernelFixtures.eventsOnH2().isRunning()).isFalse();
        assertThat(EmbeddedKernelFixtures.flowOnH2().isRunning()).isFalse();
        assertThat(EmbeddedKernelFixtures.eventsAndFlowOnH2().isRunning()).isFalse();
    }

    @Test
    @DisplayName("accessors refuse before start rather than handing back a half-built fixture")
    void accessorsRefuseBeforeStart() {
        EmbeddedKernelFixture fixture = EmbeddedKernelFixtures.eventsAndFlowOnH2();

        assertThatThrownBy(fixture::eventEngine).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(fixture::flowEngine).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(fixture::persistenceEngine).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(fixture::jdbcUrl).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.runInKernelScope(() -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty subsystem set is refused at construction, not at boot")
    void emptySubsystemSetIsRefusedEarly() {
        assertThatThrownBy(() -> EmbeddedKernelFixtures.forJdbcUrl(Set.of(), "jdbc:h2:mem:x", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one subsystem");
    }

    @Test
    @DisplayName("close before start is a no-op rather than a failure")
    void closeBeforeStartIsQuiet() {
        EmbeddedKernelFixtures.flowOnH2().close();
    }
}
