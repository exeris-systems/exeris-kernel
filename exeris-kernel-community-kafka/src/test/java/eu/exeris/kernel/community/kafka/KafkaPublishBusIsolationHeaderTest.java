/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.events.IsolationKeySpec;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit: the publish path of {@link KafkaEventEngine} writes the isolation ordinal the bus stamped
 * into the record header, so a consumer on another node can scope the delivery (ADR-101). A
 * {@link MockProducer} records the sent records, so no broker is needed.
 */
@DisplayName("KafkaEventEngine — publish writes the stamped isolation ordinal to the record header (no broker)")
class KafkaPublishBusIsolationHeaderTest {

    private static final String TYPE = "KafkaIsolationHeaderPublishEvent";
    private static final int REGISTERED_ORDINAL = 9_301;
    private static final String TENANT = "tenant-a";
    private static final int TENANT_ORDINAL = 101;

    private MockProducer<byte[], byte[]> producer;
    private KafkaEventEngine engine;

    @BeforeEach
    void setUp() {
        producer = new MockProducer<>(true, null, new ByteArraySerializer(), new ByteArraySerializer());
        engine = new KafkaEventEngine(
                EventEngineConfig.communityDefaults(),
                KafkaEventConfig.defaults("localhost:9092", "unit"),
                producer);
        engine.registry().register(EventTypeSpec.of(TYPE, REGISTERED_ORDINAL));
        engine.isolationRegistry().register(IsolationKeySpec.of(TENANT, TENANT_ORDINAL));
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName("publish under a bound key writes the key's ordinal to the record header")
    void publishWritesTheStampedOrdinal() {
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared(TENANT))
                .run(() -> engine.bus().publish(unscopedDescriptor(), EventPayload.empty()));

        assertThat(producer.history()).hasSize(1);
        assertThat(KafkaIsolationHeader.read(producer.history().get(0).headers()))
                .as("the header MUST carry the ordinal the bus stamped, not the caller's 0")
                .isEqualTo(TENANT_ORDINAL);
    }

    private static EventDescriptor unscopedDescriptor() {
        UUID id = UUID.randomUUID();
        return EventDescriptor.of(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                REGISTERED_ORDINAL, EventDescriptor.FLAG_ASYNC, System.currentTimeMillis());
    }
}
