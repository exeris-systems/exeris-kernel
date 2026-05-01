/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
