/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * SPI: moves a parked saga from one definition version to the next (ADR-064).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Adjacent hops only. A migration registered for {@code fromVersion} produces state valid under
 *       {@code fromVersion + 1}; the runtime chains hops to reach a registered version.</li>
 *   <li>Pure with respect to kernel state: it reads the supplied {@link FlowMigrationState} and
 *       returns a new one. It performs no I/O and touches nothing else.</li>
 *   <li>Its output is <b>validated, not trusted</b>. The step it names is checked against the target
 *       plan by the same guard that checks an ordinary resume (ADR-062), so a transform that returns
 *       a step which does not exist, or a name that disagrees with the index, fails closed.</li>
 *   <li>Throwing is a failure of the migration, not of the saga: the wake fails closed and the parked
 *       row is left untouched, so a corrected transform can be deployed and the saga recovered.</li>
 * </ul>
 *
 * <p>This runs on the {@code wake()} path only. A saga resubmitted through {@code schedule()} under a
 * mismatched version is refused rather than migrated — see ADR-064 amendment A1, which states that
 * narrowing rather than leaving it implied.
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface FlowDefinitionMigration {

    /**
     * Maps a saga parked under version {@code n} onto its position under version {@code n + 1}.
     *
     * @param parked the saga's rewritable state under the source version; never {@code null}
     * @return the equivalent state under the next version; must not be {@code null}
     */
    FlowMigrationState migrate(FlowMigrationState parked);
}
