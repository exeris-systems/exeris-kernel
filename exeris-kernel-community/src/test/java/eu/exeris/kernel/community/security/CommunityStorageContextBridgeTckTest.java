/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.core.security.StorageContextBridge;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.security.AbstractStorageContextBridgeTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: StorageContextBridge TCK")
class CommunityStorageContextBridgeTckTest extends AbstractStorageContextBridgeTck {

    @Override
    protected StorageContext derive(PrincipalContext principal) {
        return StorageContextBridge.derive(principal);
    }

    @Override
    protected StorageContext deriveFromActivePrincipal() {
        return StorageContextBridge.deriveFromActivePrincipal();
    }
}
