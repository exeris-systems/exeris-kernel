/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.tck.contract.events.AbstractEventBackpressureTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: EventBus backpressure TCK (EVENT-205b EX-EVENT-6002)")
class CommunityEventBackpressureTckTest extends AbstractEventBackpressureTck {

    @Override
    protected EventEngine createEngine(EventEngineConfig failFastConfig) {
        return new CommunityEventProvider().createEngine(failFastConfig);
    }
}
