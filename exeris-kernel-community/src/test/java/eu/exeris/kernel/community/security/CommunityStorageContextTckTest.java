/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.security.AbstractStorageContextTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: StorageContext TCK — ImmutableStorageContext binding")
class CommunityStorageContextTckTest extends AbstractStorageContextTck {

    @Override
    protected StorageContext createShared(String isolationKey) {
        return ImmutableStorageContext.shared(isolationKey);
    }

    @Override
    protected StorageContext createSeparatedSchema(String isolationKey, String schemaName) {
        return ImmutableStorageContext.separatedSchema(isolationKey, schemaName);
    }

    @Override
    protected StorageContext createDedicated(String isolationKey, String dataSourceKey) {
        return ImmutableStorageContext.dedicated(isolationKey, dataSourceKey);
    }

    @Override
    protected StorageContext createGlobal() {
        return ImmutableStorageContext.GLOBAL;
    }
}
