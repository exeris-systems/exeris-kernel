/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fan-out / join-all for TCK fixtures, on GA APIs only.
 *
 * <h2>Why this is duplicated rather than reused</h2>
 * <p>{@code eu.exeris.kernel.core.concurrent.StructuredScope} already does this and has tests for it.
 * It cannot be used here, and the reason is mechanical rather than architectural: {@code
 * exeris-kernel-core}, {@code -community} and {@code -community-kafka} all depend on this module's
 * test-jar, so a dependency from here onto Core would be a <b>cycle in the reactor</b>, which Maven
 * refuses whatever the scope. The Wall is a weaker argument than it looks — {@code StructuredScope}
 * imports only {@code java.lang} and {@code java.util}. Anyone tempted to collapse the duplication
 * will hit the cycle; that is why it is written down here.
 *
 * <p>The fixtures previously used {@code StructuredTaskScope}, a preview API: every class using it is
 * stamped {@code minor_version 0xFFFF} and will not load without {@code --enable-preview}. This module
 * has no {@code src/main} — its whole distributed surface IS the test-jar, and a provider author
 * binding {@code Abstract*Tck} consumes exactly these classes. Leaving them stamped forces the flag,
 * and the exact JDK, on every such author.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #open()} — wait for every task, then report all failures together.</li>
 *   <li>{@link #openFailFast()} — <b>cancel on the first failure</b>, then rethrow it. Cancelling is
 *       the half that is easy to omit and expensive to omit: a fixture forking many tasks would
 *       otherwise run all of them to completion before failing, turning an assertion error into a
 *       suite timeout.</li>
 *   <li>{@link #openCancelOnFailure()} — cancel on the first failure and report nothing, for fixtures
 *       whose subject IS the teardown and which throw on purpose to trigger it.</li>
 * </ul>
 *
 * <p><b>Cancellation is sticky and one-shot.</b> Once triggered it interrupts the tasks running at
 * that moment, and any task forked afterwards is never started — a scope that kept accepting work
 * after deciding to tear down would leave the fixture asserting against a moving target. One-shot
 * because re-running the cascade per failure re-arms interrupts on threads that already consumed
 * theirs, which surfaces as a spurious interrupt inside unrelated task code.
 *
 * <p><b>Bindings are not inherited.</b> {@code StructuredTaskScope.fork} propagates the caller's
 * {@code ScopedValue} bindings; {@code Thread.ofVirtual()} does not. A fixture that asserts a child
 * sees a binding must establish it inside the task.
 *
 * <p>{@link #close()} joins if {@link #join()} was not called, and interrupts stragglers if that join
 * is itself interrupted — joining alone cannot end a task that is not going to finish.
 */
public final class TckScope implements AutoCloseable {

    /**
     * Concurrent, because {@link #cancelSiblings} iterates it from a <em>worker</em> thread while the
     * owner may still be appending in {@link #fork}. A plain {@code ArrayList} there is a data race
     * whose usual symptom is a missed interrupt — that is, a hang, blamed on the fixture.
     */
    private final List<Thread> forked = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private final boolean failFast;
    private final boolean cancelOnFailure;
    private final boolean failuresAreTheSignal;

    private boolean joined;

    private TckScope(boolean failFast, boolean cancelOnFailure, boolean failuresAreTheSignal) {
        this.failFast = failFast;
        this.cancelOnFailure = cancelOnFailure;
        this.failuresAreTheSignal = failuresAreTheSignal;
    }

    /** A scope that waits for every task and reports all failures together. */
    public static TckScope open() {
        return new TckScope(false, false, false);
    }

    /** A scope that cancels its remaining tasks on the first failure, then rethrows it. */
    public static TckScope openFailFast() {
        return new TckScope(true, true, false);
    }

    /**
     * A scope that cancels on the first failure and does not report it.
     *
     * <p>For fixtures that assert cancellation itself — that parked work is actually torn down when a
     * sibling blows up. Those throw deliberately to start the teardown, so the failure is the trigger
     * and not the verdict; rethrowing it would fail the test with its own stimulus.
     */
    public static TckScope openCancelOnFailure() {
        return new TckScope(false, true, true);
    }

    /**
     * Runs {@code task} on a virtual thread owned by this scope.
     *
     * <p>A fork after cancellation is a no-op: the scope has already decided to tear down.
     *
     * @param task the body; its return value is discarded, as no fixture here reads one
     */
    public void fork(Callable<?> task) {
        if (cancelled.get()) {
            return;
        }
        Thread thread = Thread.ofVirtual().unstarted(() -> runCapturing(task));
        forked.add(thread);
        // Re-checked after publishing: a cancellation between the check above and here would
        // otherwise start a thread the cascade has already walked past.
        if (cancelled.get()) {
            thread.interrupt();
        }
        thread.start();
    }

    private void runCapturing(Callable<?> task) {
        try {
            task.call();
        } catch (Throwable t) { // NOPMD — a fixture must report what escaped, whatever it was
            synchronized (failures) {
                failures.add(t);
            }
            if (cancelOnFailure) {
                cancelSiblings();
            }
        }
    }

    /**
     * Waits for every forked task.
     *
     * @throws InterruptedException if the caller is interrupted while waiting
     */
    public void join() throws InterruptedException {
        try {
            for (Thread thread : forked) {
                thread.join();
            }
        } finally {
            // After the loop, and in a finally. Set first, it made close() a no-op on exactly the
            // path where it matters: a join interrupted part-way left tasks running, and the
            // try-with-resources then returned claiming a clean scope over live threads.
            joined = true;
        }
        reportFailures();
    }

    @Override
    public void close() {
        if (joined) {
            return;
        }
        try {
            join();
        } catch (InterruptedException e) {
            cancelSiblings();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while joining TCK scope", e);
        }
    }

    /** Interrupts every task but the caller's own, once — see the class note on stickiness. */
    private void cancelSiblings() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Thread self = Thread.currentThread();
        for (Thread thread : forked) {
            if (thread != self) {
                thread.interrupt();
            }
        }
    }

    private void reportFailures() {
        List<Throwable> observed;
        synchronized (failures) {
            if (failures.isEmpty() || failuresAreTheSignal) {
                return;
            }
            observed = List.copyOf(failures);
        }
        Throwable first = observed.get(0);
        if (failFast) {
            // The first failure is what the fixture is told about, but the rest are not discarded: a
            // fail-fast scope that dropped them would hide a second, different failure behind
            // whichever task happened to lose the race.
            attachRest(first, observed);
            switch (first) {
                case RuntimeException runtimeFailure -> throw runtimeFailure;
                case Error errorFailure -> throw errorFailure;
                default -> throw new IllegalStateException("forked task failed", first);
            }
        }
        IllegalStateException aggregate =
                new IllegalStateException(observed.size() + " forked task(s) failed", first);
        attachRest(aggregate, observed);
        throw aggregate;
    }

    private static void attachRest(Throwable carrier, List<Throwable> observed) {
        for (int i = 1; i < observed.size(); i++) {
            Throwable other = observed.get(i);
            if (other != carrier) {
                carrier.addSuppressed(other);
            }
        }
    }
}
