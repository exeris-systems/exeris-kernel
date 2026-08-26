/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.tck.contract.events.EventBusZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: EventBus Zero-Alloc TCK")
class CommunityEventBusZeroAllocTckTest extends EventBusZeroAllocTck {

    @Override
    protected EventEngine createEngine() {
        return new CommunityEventProvider().createEngine(EventEngineConfig.communityDefaults());
    }
}
