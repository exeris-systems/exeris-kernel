/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowDefinitionMigration;

import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * Admission rules for the adjacent-hop migration transforms of ADR-064.
 *
 * <p>Separate from {@code CoreFlowPlanFactory} because it shares no state with plan compilation: the
 * factory validates and assembles definitions, this validates and admits the transforms that move
 * sagas <em>between</em> them. Holding both put the factory over the God-Class cohesion threshold,
 * which was the accurate reading and not a threshold to raise.
 *
 * <p>Storage and admission only — the chain that consumes these lives in {@code CoreFlowRuntime}.
 */
final class CoreMigrationRegistry {

    private final FlowEngineConfig config;
    private final ConcurrentMap<MigrationKey, FlowDefinitionMigration> migrations;

    /* default */ CoreMigrationRegistry(FlowEngineConfig config,
                                        ConcurrentMap<MigrationKey, FlowDefinitionMigration> migrations) {
        this.config = Objects.requireNonNull(config, "config");
        this.migrations = Objects.requireNonNull(migrations, "migrations");
    }

    /* default */ void register(String definitionName, int fromVersion, FlowDefinitionMigration migration) {
        Objects.requireNonNull(definitionName, "definitionName must not be null");
        Objects.requireNonNull(migration, "migration must not be null");
        if (definitionName.isBlank()) {
            throw new IllegalArgumentException("definitionName must not be blank");
        }
        if (fromVersion < FlowDefinition.INITIAL_VERSION) {
            throw new IllegalArgumentException(
                    "fromVersion must be >= " + FlowDefinition.INITIAL_VERSION + ", got: " + fromVersion);
        }
        admit(new MigrationKey(definitionName, fromVersion), definitionName, fromVersion, migration);
    }

    /**
     * Bound check and insert share one critical section, as {@code compile()} does for the plan
     * catalog: split across two, two registrations racing at the ceiling both observe room and both
     * land.
     *
     * <p>The ceiling is the plan catalog's, and needed for the same reason rather than by symmetry — a
     * transform deliberately <em>outlives</em> the version it moves from, which is what lets a version
     * be retired, so this map grows on exactly the deploy cadence the catalog does and cannot be
     * trimmed by the catalog's own eviction. Registration is explicit application code, never
     * attacker-driven, so this is a leak ceiling and not admission control.
     */
    private void admit(MigrationKey key, String definitionName, int fromVersion,
                       FlowDefinitionMigration migration) {
        synchronized (migrations) {
            if (!migrations.containsKey(key) && migrations.size() >= config.maxExecutionPlans()) {
                throw new FlowEngineException(
                        "maxExecutionPlans limit reached for migrations: " + config.maxExecutionPlans());
            }
            if (migrations.putIfAbsent(key, migration) != null) {
                // Silently replacing would mean a redeploy could change how in-flight sagas are moved
                // without anyone stating it — the transform is as load-bearing as the definition itself.
                throw new FlowEngineException(
                        "A migration is already registered for definition '" + definitionName
                        + "' version " + fromVersion);
            }
        }
    }
}
