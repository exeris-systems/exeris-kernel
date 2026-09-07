/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;

import java.util.function.UnaryOperator;

/**
 * Shared base for Community's {@link Subsystem} implementations: a single running flag plus the
 * default (no-op) provider-binding fallback, so each concrete subsystem states only what makes it
 * different — its dependencies, its phase, and the providers it discovers.
 *
 * @implSpec Subclasses report their running state by calling {@link #markRunning(boolean)} from
 *           {@code start()} and {@code stop()} rather than tracking their own flag; {@link #isRunning()}
 *           is {@code final} and reads only that flag.
 */
/* default */ abstract class AbstractCommunitySubsystem implements Subsystem {

    private boolean running;

    /**
     * Returns whether the last call to {@link #markRunning(boolean)} recorded {@code true}.
     *
     * @return {@code true} if this subsystem is currently marked running
     */
    @Override
    public final boolean isRunning() {
        return running;
    }

    /**
     * Records this subsystem's running state for {@link #isRunning()} to report.
     *
     * @param value {@code true} once the subsystem has started successfully, {@code false} once it
     *              has stopped or failed to find a provider to run
     */
    protected final void markRunning(boolean value) {
        this.running = value;
    }

    /**
     * Returns the identity carrier enricher a subclass falls back to when it has nothing to bind —
     * the same default {@link Subsystem#providerBindings()} would return.
     *
     * @return the no-op carrier enricher
     */
    protected final UnaryOperator<ScopedValue.Carrier> defaultProviderBindings() {
        return Subsystem.super.providerBindings();
    }
}
