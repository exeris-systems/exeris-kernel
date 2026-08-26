/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import jdk.jfr.Event;

/**
 * Process-wide entry point for off-virtual-thread JFR event commit.
 *
 * <p>Static JFR event classes (e.g. {@code AdmissionDecisionEvent}) call {@link #offer(Event)} to
 * hand a constructed event to the installed {@link JfrEventCommitter} instead of committing inline
 * on the request virtual thread (see {@link JfrEventCommitter} for why that is unsafe on JDK 26+35).
 *
 * <h2>Lifecycle</h2>
 * <p>The owning subsystem {@linkplain #install(JfrEventCommitter) installs} a committer at start and
 * {@linkplain #clear() clears} it at stop. Before install / after clear, the active sink is
 * {@link #REJECT_ALL}, so {@link #offer(Event)} returns {@code false} and the caller falls back to a
 * direct, synchronous {@code commit()} — correct for unit tests and any path that runs outside a
 * booted kernel (and on a platform thread there, so inline commit is safe).
 *
 * @since 0.7.1
 */
public final class JfrCommitGate {

    /** Sentinel used when no committer is installed; always rejects so callers commit inline. */
    private static final CommitSink REJECT_ALL = event -> false;

    private static volatile CommitSink current = REJECT_ALL;

    /** Functional view of the active commit destination — avoids a nullable static pointer. */
    @FunctionalInterface
    private interface CommitSink {
        boolean offer(Event event);
    }

    private JfrCommitGate() {
    }

    /** Installs the active committer. Called once by the owning subsystem at start. */
    public static void install(JfrEventCommitter committer) {
        current = committer::offer;
    }

    /** Resets to the reject-all sink so no new events enqueue during shutdown drain. */
    public static void clear() {
        current = REJECT_ALL;
    }

    /**
     * Offers a constructed event to the installed committer for off-thread commit.
     *
     * @param event a constructed, field-populated JFR event (must not be committed by the caller)
     * @return {@code true} if a committer is installed and accepted the event; {@code false}
     *         otherwise, signalling the caller to commit inline as a fallback
     */
    public static boolean offer(Event event) {
        return current.offer(event);
    }
}
