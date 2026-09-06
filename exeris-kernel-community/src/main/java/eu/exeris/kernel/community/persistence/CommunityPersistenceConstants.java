/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

/**
 * Shared string constants for the Community persistence tier: the provider identity, the
 * tenant key used when a request declares no isolation, and the prefix marking a resolved
 * tenant key as addressing a dedicated data source.
 */
/* default */ final class CommunityPersistenceConstants {

    /* default */ static final String PROVIDER_ID          = "postgres-community";
    /* default */ static final String SHARED_TENANT        = "shared";
    /* default */ static final String DEDICATED_KEY_PREFIX = ":dedicated:";

    private CommunityPersistenceConstants() {
    }
}
