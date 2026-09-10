/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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