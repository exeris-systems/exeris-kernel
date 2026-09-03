/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkitconsumer;

import eu.exeris.kernel.community.testkit.runtime.EmbeddedKernelFixture;
import eu.exeris.kernel.community.testkit.runtime.EmbeddedKernelFixtures;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the events and flow fixtures the way a downstream consumer would.
 *
 * <h2>Shape of this test</h2>
 * <p>It imports the testkit and the SPI and <strong>nothing from
 * {@code eu.exeris.kernel.community.*}</strong> — deliberately, because a test that reached into
 * Community internals would prove the fixture works for someone who does not need it. Every type below
 * is one a host runtime outside this repository can also see, including the payload:
 * {@link EventPayload#empty()} is an SPI factory, so no Community payload implementation is required.
 *
 * <p>Location is the same compromise the persistence consumer test records: this lives in
 * {@code exeris-kernel-community}'s test scope because the testkit builds before Community in the
 * reactor and cannot depend on a provider. That test observed a genuinely external consumer module
 * "is worth doing if this pattern grows past one subsystem" — it now has, which is a decision for the
 * cycle that follows rather than one to take at a release boundary.
 *
 * @since 0.12.0
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@DisplayName("Testkit consumer: the events and flow fixtures boot real engines")
class EmbeddedKernelFixtureConsumerTest {

    private static final String EVENT_TYPE = "testkit.consumer.probe";
    private static final int EVENT_ORDINAL = 9101;
    private static final int ASYNC_FLAGS = 0x02;
    private static final String COUNT_SAGA_STATE = "SELECT COUNT(*) FROM exeris_saga_state";

    @Nested
    @DisplayName("The engines are real, not doubles")
    class RealEngines {

        @Test
        @DisplayName("an event published through the real bus reaches a real subscriber")
        void publishedEventReachesSubscriber() throws Exception {
            try (EmbeddedKernelFixture fixture = EmbeddedKernelFixtures.eventsOnH2()) {
                fixture.start();
                EventEngine engine = fixture.eventEngine();
                engine.registry().register(EventTypeSpec.of(EVENT_TYPE, EVENT_ORDINAL));

                CountDownLatch delivered = new CountDownLatch(1);
                engine.bus().subscribe(EVENT_TYPE, (descriptor, payload) -> {
                    try (payload) {
                        delivered.countDown();
                    }
                });
                engine.bus().publish(descriptor(), EventPayload.empty());

                assertThat(delivered.await(10, TimeUnit.SECONDS))
                        .as("the real dispatch path must deliver — a double would not have one")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("flow boots against a database carrying the engine's own saga schema")
        void flowBootsWithSagaSchema() {
            try (EmbeddedKernelFixture fixture = EmbeddedKernelFixtures.flowOnH2()) {
                fixture.start();

                assertThat(fixture.flowEngine()).isNotNull();
                // The strongest available proof that flow composes with persistence rather than
                // standing beside it: the saga table the engine writes is readable through the very
                // engine this fixture hands back, in the same database.
                assertThat(countRows(fixture.persistenceEngine()))
                        .as("exeris_saga_state must exist in the fixture's database")
                        .isZero();
            }
        }
    }

    @Nested
    @DisplayName("Composition is one runtime, not several")
    class Composition {

        @Test
        @DisplayName("events and flow come out of a single boot, sharing one database")
        void bothEnginesShareOneRuntime() {
            try (EmbeddedKernelFixture fixture = EmbeddedKernelFixtures.eventsAndFlowOnH2()) {
                fixture.start();

                assertThat(fixture.eventEngine()).isNotNull();
                assertThat(fixture.flowEngine()).isNotNull();
                assertThat(fixture.persistenceEngine()).isNotNull();
                assertThat(fixture.jdbcUrl()).contains("MODE=PostgreSQL");
                assertThat(fixture.isRunning()).isTrue();
            }
        }

        @Test
        @DisplayName("a subsystem that was not selected is refused by name, not by NullPointerException")
        void unselectedSubsystemIsRefusedClearly() {
            try (EmbeddedKernelFixture fixture = EmbeddedKernelFixtures.eventsOnH2()) {
                fixture.start();

                assertThatThrownBy(fixture::flowEngine)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("flow")
                        .hasMessageContaining("not selected");
            }
        }
    }

    private static EventDescriptor descriptor() {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                EVENT_ORDINAL, ASYNC_FLAGS, System.currentTimeMillis());
    }

    private static long countRows(PersistenceEngine engine) {
        try (PersistenceConnection connection = engine.openConnection();
             QueryResult result = connection.executeQuery(COUNT_SAGA_STATE)) {
            assertThat(result.next()).isTrue();
            return result.row().getLong(0);
        }
    }
}
