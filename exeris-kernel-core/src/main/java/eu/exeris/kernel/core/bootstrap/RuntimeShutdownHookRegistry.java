/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

/**
 * The production {@link ShutdownHookRegistry}: delegates to the JVM's {@link Runtime}.
 *
 * @since 0.13
 */
final class RuntimeShutdownHookRegistry implements ShutdownHookRegistry {

    /** The shared instance; the registry holds no state. */
    /* default */ static final RuntimeShutdownHookRegistry INSTANCE = new RuntimeShutdownHookRegistry();

    private RuntimeShutdownHookRegistry() {
        // Stateless; use INSTANCE.
        super();
    }

    @Override
    public void register(Thread hook) {
        Runtime.getRuntime().addShutdownHook(hook);
    }

    @Override
    public void unregister(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ex) {
            // Removal is only an optimisation once shutdown has begun.
        }
    }
}
