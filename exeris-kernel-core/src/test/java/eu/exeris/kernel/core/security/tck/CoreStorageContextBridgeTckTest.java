/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security.tck;

import eu.exeris.kernel.core.security.StorageContextBridge;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.security.AbstractStorageContextBridgeTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Core: StorageContextBridge TCK")
class CoreStorageContextBridgeTckTest extends AbstractStorageContextBridgeTck {

    @Override
    protected StorageContext derive(PrincipalContext principal) {
        return StorageContextBridge.derive(principal);
    }

    @Override
    protected StorageContext deriveFromActivePrincipal() {
        return StorageContextBridge.deriveFromActivePrincipal();
    }
}
