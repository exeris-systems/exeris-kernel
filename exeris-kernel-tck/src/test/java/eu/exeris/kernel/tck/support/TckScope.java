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

/**
 * Fan-out / join-all for TCK fixtures, on GA APIs only.
 *
 * <h2>Why this exists rather than an import</h2>
 * <p>The fixtures here used {@code StructuredTaskScope}, which is a preview API: every class using it
 * is stamped {@code minor_version 0xFFFF} and will not load without {@code --enable-preview}. That is
 * fine for a fixture nobody ships — but this module is the one exception. {@code exeris-kernel-tck}
 * has no {@code src/main}; its whole distributed surface IS the test-jar, and a provider author
 * binding {@code Abstract*Tck} to prove conformance consumes exactly those classes. Leaving them
 * stamped forces the flag, and the exact JDK, on every such author — which is the constraint ADR-066
 * exists to lift.
 *
 * <p>It is not {@code core.concurrent.StructuredScope} because this module depends on SPI alone. An
 * edge to Core would buy deduplication at the cost of The Wall (ADR-006), which is not a trade a test
 * fixture gets to make. What the fixtures need is also narrower than what the runtime needs: fork a
 * batch, wait for all of it, surface the first failure, and — where a fixture asserts teardown —
 * interrupt the rest. No deadlines, and no {@code ScopedValue} carrier: a plain virtual thread does
 * NOT inherit bindings, which is a real difference from the preview API and is called out below.
 *
 * <h2>Semantics</h2>
 * <p>{@link #join()} waits for every forked task. {@link #open()} collects failures and reports them
 * as suppressed exceptions of one {@link IllegalStateException}; {@link #openFailFast()} throws on the
 * first failure it observes after all tasks have finished. Neither interrupts siblings — a TCK
 * fixture asserts on what a contract does under concurrency, and killing half the batch mid-assertion
 * makes the result unreadable; {@link #openCancelOnFailure()} is the exception, for fixtures whose
 * subject IS the teardown.
 *
 * <p><b>Bindings are not inherited.</b> {@code StructuredTaskScope.fork} propagates the caller's
 * {@code ScopedValue} bindings; {@code Thread.ofVirtual()} does not. A fixture that asserts a child
 * sees a binding must establish it inside the task, and one whose subject IS that inheritance is
 * asserting a property of the preview API rather than of the kernel.
 *
 * <p>{@link #close()} joins if {@link #join()} was not called, so a try-with-resources block cannot
 * leak a running task past its scope.
 */
public final class TckScope implements AutoCloseable {

    private final List<Thread> forked = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final boolean failFast;

    private boolean joined;
    private boolean cancelOnFailure;
    private boolean failuresAreTheSignal;

    private TckScope(boolean failFast) {
        this.failFast = failFast;
    }

    /** A scope that waits for every task and reports all failures together. */
    public static TckScope open() {
        return new TckScope(false);
    }

    /** A scope that waits for every task and then rethrows the first failure. */
    public static TckScope openFailFast() {
        return new TckScope(true);
    }

    /**
     * A scope that interrupts its remaining tasks as soon as one fails.
     *
     * <p>For the fixtures that assert cancellation itself — that parked work is actually torn down
     * when a sibling blows up, rather than left to run to completion. That is a property of the scope,
     * not of the contract under test, so it has to be reproduced here rather than assumed away.
     *
     * <p>Failures are the trigger, not the outcome: this variant does not rethrow them, because the
     * fixture deliberately throws to start the teardown and then asserts on what survived.
     */
    public static TckScope openCancelOnFailure() {
        TckScope scope = new TckScope(false);
        scope.cancelOnFailure = true;
        scope.failuresAreTheSignal = true;
        return scope;
    }

    /**
     * Runs {@code task} on a virtual thread owned by this scope.
     *
     * @param task the body; its return value is discarded, as no fixture here reads one
     */
    public void fork(Callable<?> task) {
        Thread thread = Thread.ofVirtual().unstarted(() -> {
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
        });
        forked.add(thread);
        thread.start();
    }

    /**
     * Waits for every forked task.
     *
     * @throws InterruptedException if the caller is interrupted while waiting
     */
    public void join() throws InterruptedException {
        joined = true;
        for (Thread thread : forked) {
            thread.join();
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
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while joining TCK scope", e);
        }
    }

    private void cancelSiblings() {
        Thread self = Thread.currentThread();
        for (Thread thread : forked) {
            if (thread != self) {
                thread.interrupt();
            }
        }
    }

    private void reportFailures() {
        synchronized (failures) {
            if (failures.isEmpty() || failuresAreTheSignal) {
                // A cancel-on-failure fixture throws on purpose to trigger the teardown it is
                // asserting on. Rethrowing that would turn the trigger into the verdict — which is
                // what StructuredTaskScope's allUntil joiner also declines to do: it consumes the
                // failure as the cancellation signal and leaves the fixture to assert on state.
                return;
            }
            if (failFast) {
                Throwable first = failures.get(0);
                if (first instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (first instanceof Error errorFailure) {
                    throw errorFailure;
                }
                throw new IllegalStateException("forked task failed", first);
            }
            IllegalStateException aggregate =
                    new IllegalStateException(failures.size() + " forked task(s) failed");
            failures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }
    }
}
