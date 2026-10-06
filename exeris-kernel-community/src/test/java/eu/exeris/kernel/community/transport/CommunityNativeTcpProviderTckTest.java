/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.transport.TransportProvider;
import eu.exeris.kernel.tck.contract.transport.AbstractTransportProviderTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: NativeTcpTransportProvider TCK")
class CommunityNativeTcpProviderTckTest extends AbstractTransportProviderTck {

    @Override
    protected TransportProvider createProvider() {
        return new NativeTcpTransportProvider();
    }
}
