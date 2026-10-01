/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketProvider;
import eu.exeris.kernel.tck.contract.websocket.AbstractWebSocketProviderTck;
import org.junit.jupiter.api.DisplayName;

/** Binds {@link AbstractWebSocketProviderTck} to the Community driver. */
@DisplayName("Community: WebSocketProvider construction contract")
class CommunityWebSocketProviderTckTest extends AbstractWebSocketProviderTck {

    @Override
    protected WebSocketProvider createProvider() {
        return new CommunityWebSocketProvider();
    }
}
