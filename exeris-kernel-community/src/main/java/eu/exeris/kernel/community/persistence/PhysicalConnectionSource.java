/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;

/**
 * Package-private capability seam: implemented by engines that can open a physical
 * connection directly, bypassing request-scoped session-box re-entry.
 *
 * <p>Used by {@link PersistenceSessionBox} to acquire a backing connection without
 * depending on {@link CommunityPersistenceEngine} by name.
 *
 * @since 0.6.0
 */
@FunctionalInterface
/* default */ interface PhysicalConnectionSource {

    /**
     * Opens a physical backing connection, bypassing request-scope session checks.
     *
     * @return a new physical {@link PersistenceConnection}
     */
    PersistenceConnection openPhysical();
}
