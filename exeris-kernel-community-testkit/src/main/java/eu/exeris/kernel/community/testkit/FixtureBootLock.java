/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit;

/**
 * Serialises the window in which a fixture publishes its configuration and the kernel reads it.
 *
 * <h2>The race this closes</h2>
 * <p>Kernel configuration arrives through system properties, which are JVM-global. A fixture has to
 * set them, boot, and only then may another fixture set its own. Nothing in the kernel enforces that:
 * {@code CommunityConfigProvider.resolveRaw} performs an uncached {@code System.getProperty} on every
 * call, and {@code KernelBootstrap}'s {@code bootActive} guard is a per-instance {@code AtomicBoolean},
 * so two {@code boot()} calls from different threads are not serialised against each other. Two
 * fixtures started concurrently could therefore each read the other's properties — for persistence
 * that is a fixture connecting to the wrong database, not merely the wrong port.
 *
 * <h2>Why the boot window is the whole exposure</h2>
 * <p>The values are resolved <em>once</em>, while the subsystem initialises, into a configuration
 * object the engine then owns; they are never re-read. So the properties only have to be stable from
 * the moment a fixture sets them until its boot has consumed them. Once past that point, fixtures may
 * overlap freely — including one restoring its property snapshot while another is still running.
 *
 * <p>This lock therefore covers <em>starting</em>, not the fixture lifetime: concurrent tests still
 * run concurrently, they just cannot boot at the same instant.
 *
 * <p>Testkit plumbing, not fixture API. Public only so the fixture subpackages can reach it.
 *
 * @since 0.11.0
 */
public final class FixtureBootLock {

    private static final Object BOOT_LOCK = new Object();

    private FixtureBootLock() {
    }

    /**
     * Runs a fixture's set-properties-then-boot-then-await sequence with no other fixture booting.
     *
     * @param bootAndAwaitStart publishes configuration, starts the runtime thread, and returns only
     *                          once that thread has booted or failed — returning earlier would leave
     *                          the window open
     */
    public static void bootExclusively(Runnable bootAndAwaitStart) {
        synchronized (BOOT_LOCK) {
            bootAndAwaitStart.run();
        }
    }
}
