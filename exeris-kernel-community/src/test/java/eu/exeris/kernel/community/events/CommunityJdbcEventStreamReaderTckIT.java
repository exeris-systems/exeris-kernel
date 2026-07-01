/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.events.AbstractEventStreamReaderTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

/**
 * Community binding of {@link AbstractEventStreamReaderTck} against a real Postgres 16 instance
 * (ADR-049). Streams are seeded through the {@link JdbcEventStreamAppender} using
 * {@link EventStreamAppender#ANY_VERSION} so replay is verified end-to-end over the same durable
 * log the appender writes — proving the append→replay per-stream ordering round-trip.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: JdbcEventStreamReader TCK (PostgreSQL, ADR-049)")
class CommunityJdbcEventStreamReaderTckIT extends AbstractEventStreamReaderTck {

    private static final int SEED_EVENT_TYPE_ORDINAL = 7_777;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static PersistenceEngine engine;
    private static JdbcEventStreamAppender seeder;

    @BeforeAll
    static void bootstrap() {
        engine = EventLogTestSupport.createEngine(POSTGRES);
        seeder = new JdbcEventStreamAppender(engine, "tck-event-seeder");
    }

    @AfterAll
    static void teardown() {
        seeder = null;
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @Override
    protected EventStreamReader createReader() {
        EventLogTestSupport.truncateEventLog(engine);
        return new JdbcEventStreamReader(engine, "tck-event-reader", name -> -1);
    }

    @Override
    protected void seedStream(StreamId streamId, int eventCount) {
        for (int i = 0; i < eventCount; i++) {
            UUID eventId = UUID.randomUUID();
            EventDescriptor descriptor = new EventDescriptor(
                    eventId.getMostSignificantBits(), eventId.getLeastSignificantBits(),
                    streamId.streamIdHigh(), streamId.streamIdLow(),
                    SEED_EVENT_TYPE_ORDINAL, EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());
            seeder.append(streamId, EventStreamAppender.ANY_VERSION, descriptor, EventPayload.empty());
        }
    }
}
