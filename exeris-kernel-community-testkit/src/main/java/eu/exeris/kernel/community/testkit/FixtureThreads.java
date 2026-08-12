/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit;

import java.util.concurrent.TimeUnit;

/**
 * Thread handling shared by the fixtures that hold a kernel boot open on a dedicated thread.
 *
 * <p>Testkit plumbing, not fixture API. Public only so the fixture subpackages can reach it.
 *
 * @since 0.11.0
 */
public final class FixtureThreads {

    private FixtureThreads() {
    }

    /**
     * Waits for a fixture runtime thread to finish, then interrupts it if it has not.
     *
     * <p>A fixture that hangs on close must not hang the suite: the deadline turns a stuck kernel
     * shutdown into a late-but-terminating test rather than a build that never returns.
     *
     * @param thread         the thread to join; {@code null} is a no-op
     * @param timeoutSeconds how long to wait before interrupting
     */
    public static void joinQuietly(Thread thread, long timeoutSeconds) {
        if (thread == null) {
            return;
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (thread.isAlive() && System.nanoTime() < deadlineNanos) {
            try {
                thread.join(100L);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (thread.isAlive()) {
            thread.interrupt();
        }
    }
}
