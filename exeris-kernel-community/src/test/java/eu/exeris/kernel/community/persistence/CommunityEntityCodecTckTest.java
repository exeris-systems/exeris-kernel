/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.tck.contract.persistence.AbstractEntityCodecTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community binding for the persistence entity codec TCK.
 *
 * @since 0.5.0
 */
@DisplayName("Community: Persistence EntityCodec TCK")
class CommunityEntityCodecTckTest extends AbstractEntityCodecTck {

    private final PersistenceProvider provider = new CommunityPersistenceProvider();

    @Override
    protected PersistenceProvider createProvider() {
        return provider;
    }
}