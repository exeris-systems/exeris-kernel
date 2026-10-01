/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

/**
 * Core: identifies the transform that moves a saga off {@code fromVersion} (ADR-064).
 *
 * <p>Keyed by the source version alone because hops are adjacent by contract — the target is always
 * {@code fromVersion + 1}. That is what lets the chain walk by point-get instead of scanning for a
 * route, which matters because the plan catalogue has no name-to-versions index.
 *
 * @param definitionName the definition these versions belong to
 * @param fromVersion    the version being migrated away from
 * @since 0.11
 */
/* default */ record MigrationKey(String definitionName, int fromVersion) {
}
