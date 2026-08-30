/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.support;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs two blocking peers concurrently on platform threads, with a deadline.
 *
 * <h2>Why not {@link TckScope}</h2>
 * <p>{@code TckScope} forks virtual threads, and these peers must not run on them. Both sides drive
 * OpenSSL through blocking FFM downcalls ({@code SSL_accept}, {@code SSL_connect}), and an FFM call
 * blocks the carrier rather than unmounting the virtual thread. On a single-CPU CI runner two virtual
 * threads sharing one carrier deadlock: whichever side calls first holds the carrier, and the other
 * can never run to hand it the bytes it is waiting for. Platform threads are preempted by the OS, so
 * both blocking calls make progress.
 *
 * <p>The deadline is the second half of the same problem. A handshake that cannot progress does not
 * fail — it hangs — so a fixture without one converts a protocol bug into a suite timeout with no
 * evidence. Interrupting a thread blocked in {@code read()} delivers {@code EINTR} on Linux, which
 * surfaces as {@code SSL_ERROR_SYSCALL} and ends the peer.
 *
 * <h2>Outcome rather than exceptions</h2>
 * <p>The three call sites report the same events in three vocabularies — a benchmark that must not
 * throw {@code AssertionError}, an integration test that must, and one that aborts rather than fails
 * on a timeout. So this reports what happened and each caller phrases it, instead of choosing for
 * them.
 */
public final class BlockingPeerPair {

    private BlockingPeerPair() {
        // Static entry point only.
    }

    /**
     * A peer's body, allowed to throw whatever the transport under test throws.
     */
    @FunctionalInterface
    public interface Peer {

        /**
         * Drives this side of the exchange.
         *
         * @throws Exception whatever the peer's own code throws; it is captured, not propagated
         */
        void drive() throws Exception; //NOPMD SignatureDeclareThrowsException — a peer body is arbitrary
    }

    /**
     * Runs both peers and waits for them, up to {@code timeout}.
     *
     * <p>On timeout both threads are interrupted and left to die as daemons: a peer wedged in a
     * native call may not return, and blocking the fixture on it would reproduce the hang the
     * deadline exists to end.
     *
     * @param timeout how long both peers together may take
     * @param server the accepting side
     * @param client the connecting side
     * @return what happened, for the caller to phrase
     * @throws InterruptedException if the caller is interrupted while waiting
     */
    public static Outcome drive(Duration timeout, Peer server, Peer client) throws InterruptedException {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicReference<Throwable> clientFailure = new AtomicReference<>();

        Thread serverThread = start("peer-server", server, serverFailure);
        Thread clientThread = start("peer-client", client, clientFailure);

        // ONE absolute deadline for the pair, not a budget each. This is what the scope-level
        // withTimeout() it replaces did, and it is what a handshake needs: the two peers are halves
        // of one exchange, so the question is whether the exchange completed in time, never whether
        // each side did. A per-peer budget would let a pair take twice the timeout and still pass.
        long deadline = System.nanoTime() + timeout.toNanos();
        // Both joins run: the second peer's fate is as much a part of the outcome as the first's.
        // If the first consumed the whole deadline the second is not waited on — by then the answer
        // is already "not in time", and waiting past a deadline is the hang this exists to end.
        boolean serverFinished = joinBy(serverThread, deadline);
        boolean clientFinished = joinBy(clientThread, deadline);
        boolean bothFinished = serverFinished && clientFinished;
        if (!bothFinished) {
            serverThread.interrupt();
            clientThread.interrupt();
        }
        return new Outcome(!bothFinished, serverFailure.get(), clientFailure.get());
    }

    private static Thread start(String name, Peer peer, AtomicReference<Throwable> failure) {
        Thread thread = Thread.ofPlatform().daemon(true).name(name).unstarted(() -> {
            try {
                peer.drive();
            } catch (Throwable t) { // a peer must report what escaped, whatever it was
                failure.set(t);
            }
        });
        thread.start();
        return thread;
    }

    private static boolean joinBy(Thread thread, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            thread.join(Duration.ofNanos(remaining));
        }
        return !thread.isAlive();
    }

    /**
     * What the two peers did.
     *
     * @param timedOut whether the deadline passed with a peer still running
     * @param serverFailure what the accepting side threw, or {@code null}
     * @param clientFailure what the connecting side threw, or {@code null}
     */
    public record Outcome(boolean timedOut, Throwable serverFailure, Throwable clientFailure) {
    }
}
