/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
