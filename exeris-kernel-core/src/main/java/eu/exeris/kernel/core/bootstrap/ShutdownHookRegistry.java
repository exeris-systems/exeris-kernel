/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

/**
 * Where the kernel's JVM shutdown hook is registered; replaced only by tests in this package.
 *
 * @since 0.13
 */
interface ShutdownHookRegistry {

    /**
     * Registers {@code hook}.
     *
     * @param hook the unstarted thread to run when the JVM shuts down
     * @throws IllegalStateException if JVM shutdown is already in progress
     */
    void register(Thread hook);

    /**
     * Unregisters {@code hook}; ignores the {@link IllegalStateException} thrown once JVM shutdown has begun.
     *
     * @param hook the thread passed to {@link #register(Thread)}
     */
    void unregister(Thread hook);
}
