/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.tck.contract.persistence.AbstractPersistenceProviderTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractPersistenceProviderTck} backed by
 * {@link CommunityPersistenceProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>providerId() == "postgres-community" — stable, non-blank identifier</li>
 *   <li>providerName() contains recognizable "Community" branding</li>
 *   <li>priority() == 0 — correct Open-Core slot; Enterprise wins with priority 100</li>
 *   <li>ServiceLoader discovers at least one PersistenceProvider on the classpath</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: CommunityPersistenceProvider TCK")
class CommunityPersistenceProviderTckTest extends AbstractPersistenceProviderTck {

    @Override
    protected PersistenceProvider createProvider() {
        return new CommunityPersistenceProvider();
    }
}
