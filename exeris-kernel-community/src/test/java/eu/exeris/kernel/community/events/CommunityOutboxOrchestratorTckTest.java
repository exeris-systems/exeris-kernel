/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.tck.contract.events.AbstractOutboxOrchestratorTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: Outbox Orchestrator TCK")
class CommunityOutboxOrchestratorTckTest extends AbstractOutboxOrchestratorTck {

    @Override
    protected EventEngine createEngine() {
        return new CommunityEventProvider().createEngine(EventEngineConfig.communityDefaults());
    }
}
