/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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

    /**
     * Wires this registry to the shared migration map and the config whose
     * {@link FlowEngineConfig#maxExecutionPlans()} bounds it.
     *
     * @param config     the engine configuration; also read by {@link CoreFlowPlanFactory}
     * @param migrations the map this registry admits into; shared with {@link CoreFlowRuntime}'s
     *                    migration-chain walk, which only reads it
     * @throws NullPointerException if {@code config} or {@code migrations} is {@code null}
     */
    /* default */ CoreMigrationRegistry(FlowEngineConfig config,
                                        ConcurrentMap<MigrationKey, FlowDefinitionMigration> migrations) {
        this.config = Objects.requireNonNull(config, "config");
        this.migrations = Objects.requireNonNull(migrations, "migrations");
    }

    /**
     * Validates and admits an adjacent-hop migration transform (ADR-064); see
     * {@link eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory#registerMigration
     * FlowExecutionPlanFactory.registerMigration} for the caller-facing contract.
     *
     * @param definitionName the definition these versions belong to; must not be {@code null} or
     *                        blank
     * @param fromVersion     the version being migrated away from; must be
     *                        {@code >= }{@link FlowDefinition#INITIAL_VERSION}
     * @param migration       the transform; must not be {@code null}
     * @throws NullPointerException     if {@code definitionName} or {@code migration} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code definitionName} is blank or {@code fromVersion} is
     *                                  below {@link FlowDefinition#INITIAL_VERSION}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} if the
     *         registry is at {@link FlowEngineConfig#maxExecutionPlans()} and this
     *         {@code (definitionName, fromVersion)} pair is new, or if a migration is already
     *         registered for that pair
     */
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
