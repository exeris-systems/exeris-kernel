/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.persistence.AbstractRowCursorTypeSetTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

/**
 * Community binding for {@link AbstractRowCursorTypeSetTck} against the server ADR-080 measured.
 *
 * <p><b>PostgreSQL 17, not 16 as the neighbouring integration tests use.</b> Two rows of the
 * measured set are version-gated above 16 — {@code interval 'infinity'} arrived in PG 17 — and a
 * type-set contract asserted on a server that cannot express part of the set would be quietly
 * partial. The other integration tests do not read the type set, so their 16 stays as it is.
 *
 * <p>This is the only binding of that TCK, and deliberately so: {@link CommunityRowCursorTckTest}
 * runs the engine-independent contract on H2 in the default build, while the rendering contract
 * needs the engine whose {@code <type>_out} it transcribes. H2 renders {@code bool} as {@code TRUE}
 * where PostgreSQL renders {@code t} — measured, and reason enough that one binding cannot serve
 * both halves.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: RowCursor type-set TCK (PostgreSQL 17)")
class CommunityRowCursorTypeSetIT extends AbstractRowCursorTypeSetTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Override
    protected PersistenceEngine createEngine() {
        return new CommunityPersistenceProvider().createEngine(new PersistenceConfig(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of()
        ));
    }
}
