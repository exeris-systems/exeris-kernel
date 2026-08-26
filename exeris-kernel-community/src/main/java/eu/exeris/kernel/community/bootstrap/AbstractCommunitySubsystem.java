/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;

import java.util.function.UnaryOperator;

/* default */ abstract class AbstractCommunitySubsystem implements Subsystem {

    private boolean running;

    @Override
    public final boolean isRunning() {
        return running;
    }

    protected final void markRunning(boolean value) {
        this.running = value;
    }

    protected final UnaryOperator<ScopedValue.Carrier> defaultProviderBindings() {
        return Subsystem.super.providerBindings();
    }
}
