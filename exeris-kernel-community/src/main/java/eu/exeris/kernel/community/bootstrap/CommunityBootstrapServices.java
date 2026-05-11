/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Community-internal bootstrap registry for cross-subsystem references that
 * must be resolved during {@link eu.exeris.kernel.spi.bootstrap.Subsystem#initialize()}
 * — before {@code providerBindings()} composition makes {@link eu.exeris.kernel.spi.context.KernelProviders}
 * ScopedValues visible.
 *
 * <p>This indirection exists because the {@code Subsystem} contract is "implementation-blind":
 * a subsystem cannot reach into another subsystem's instance fields directly. The handoff
 * must happen through a Community-internal channel that does not leak across the SPI boundary
 * (see ADR-022 §4 — "wiring must happen inside the Community kernel, not by exposing
 * implementation detail through PersistenceEngine").
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link CommunityPersistenceSubsystem#initialize()} sets the shared engine reference.</li>
 *   <li>{@link CommunityFlowSubsystem#initialize()} reads it to decide between
 *       {@code JdbcFlowSnapshotStore} (engine present) and the in-memory fallback.</li>
 *   <li>{@link CommunityPersistenceSubsystem#stop()} clears the reference so a re-bootstrap
 *       within the same classloader observes a clean slate.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>The field is {@code volatile} — single writer (persistence subsystem during the SERVICES
 * phase) and single reader (flow subsystem during the RUNTIME phase). Phase ordering plus the
 * {@code dependsOn("persistence")} declaration in {@code CommunityFlowSubsystem} guarantee the
 * write happens-before the read.
 */
final class CommunityBootstrapServices {

    private static volatile PersistenceEngine sharedPersistenceEngine;

    private CommunityBootstrapServices() {
        // utility — no instances
    }

    static void setSharedPersistenceEngine(PersistenceEngine engine) {
        sharedPersistenceEngine = engine;
    }

    static PersistenceEngine getSharedPersistenceEngine() {
        return sharedPersistenceEngine;
    }

    static void clearSharedPersistenceEngine() {
        sharedPersistenceEngine = null;
    }
}
