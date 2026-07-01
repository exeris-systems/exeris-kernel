/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.events.AbstractEventStreamAppenderTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Community binding of {@link AbstractEventStreamAppenderTck} against a real Postgres 16 instance
 * (ADR-049). This is one half of the "≥2 durable bindings" merge gate for the event-log fundament;
 * the Kafka binding is the other. The V0.10.0 migration provisions {@code exeris_event_log}; each
 * test starts from a truncated table.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: JdbcEventStreamAppender TCK (PostgreSQL, ADR-049)")
class CommunityJdbcEventStreamAppenderTckIT extends AbstractEventStreamAppenderTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static PersistenceEngine engine;

    @BeforeAll
    static void bootstrap() {
        engine = EventLogTestSupport.createEngine(POSTGRES);
    }

    @AfterAll
    static void teardown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @Override
    protected EventStreamAppender createAppender() {
        EventLogTestSupport.truncateEventLog(engine);
        return new JdbcEventStreamAppender(engine, "tck-event-appender");
    }
}
