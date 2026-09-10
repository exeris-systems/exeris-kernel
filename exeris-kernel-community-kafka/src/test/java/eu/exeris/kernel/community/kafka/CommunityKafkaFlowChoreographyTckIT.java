/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.community.flow.CommunityFlowProvider;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.AbstractFlowChoreographyTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Community binding for {@link AbstractFlowChoreographyTck} that drives the choreography
 * contract over a real Kafka broker (Testcontainers Kafka 3.x).
 *
 * <h2>DIST-302 — Distributed Choreography Smoke</h2>
 * <p>This integration test proves that the same flow-choreography contract verified by
 * {@code CommunityFlowChoreographyTckTest} (in-memory bus) holds when the
 * {@code FlowChoreographyBridge} is wired against the Kafka {@link EventEngine}: events
 * published on one end traverse a real broker partition before reaching the bridge,
 * which then invokes the {@code FlowChoreographyMapper} and dispatches the resulting
 * {@code ChoreographyDecision} (Wake / Start / Ignore) to the local {@link FlowEngine}.
 *
 * <p>Cross-engine / cross-restart scenarios (two FlowEngine instances sharing a
 * {@code JdbcFlowSnapshotStore}, mid-saga kill, Kafka rebalance during in-flight
 * choreography) are deferred to Sprint 6b — they require the durable snapshot store
 * + Postgres Testcontainer in the same harness.
 *
 * <p>Tagged {@code @Tag("integration")} so default Surefire runs skip it; opt in with
 * {@code mvn -pl exeris-kernel-community-kafka test -Dgroups=integration}.
 *
 * @since 0.7.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community :: Kafka — FlowChoreography TCK (Testcontainers Kafka 3.x)")
class CommunityKafkaFlowChoreographyTckIT extends AbstractFlowChoreographyTck {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Override
    protected FlowEngine createFlowEngine() {
        return new CommunityFlowProvider().createEngine(
                FlowEngineConfig.defaults("Community/Kafka-Choreography-TCK"));
    }

    @Override
    protected EventEngine createEventEngine() {
        EventEngineConfig spi = EventEngineConfig.communityDefaults();
        KafkaEventConfig kafka = KafkaEventConfig.defaults(
                KAFKA.getBootstrapServers(),
                "exeris-kafka-flow-choreography-tck-" + UUID.randomUUID());
        return KafkaEventProvider.create(spi, kafka);
    }

    /**
     * 30 s tolerance for the broker hop. Cold Kafka subscription + consumer rebalance
     * after each test's freshly registered event-type / topic can take 1–4 s on a
     * Testcontainers-hosted broker; the in-memory default of 4 s is too tight.
     */
    @Override
    protected long choreographyRoundtripTimeoutMs() {
        return 30_000L;
    }

    /**
     * 5 s settle for the Ignore-decision test — enough for the consumer poll loop to
     * fetch + dispatch the published event so the no-side-effect assertion is meaningful
     * rather than vacuous (event not yet processed).
     */
    @Override
    protected long choreographyIgnoreSettleMs() {
        return 5_000L;
    }

}
