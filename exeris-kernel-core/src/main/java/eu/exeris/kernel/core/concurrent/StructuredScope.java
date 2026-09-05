/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Owner-confined, await-all structured concurrency built entirely on GA APIs.
 *
 * <p>This is the preview-clean substitute for {@code java.util.concurrent.StructuredTaskScope}
 * on the default distribution line (ADR-066). It stands on virtual threads (GA since JDK 21) and
 * {@link ScopedValue} (GA since JDK 25) and requires no {@code --enable-preview}, so the artifact
 * it ships in imposes no whole-compilation, whole-JVM preview flag on its consumers.
 *
 * <h2>Binding propagation is explicit, and that is the point</h2>
 *
 * <p>{@code StructuredTaskScope} forks inherit the {@link ScopedValue} bindings in effect when the
 * scope was opened. A plain virtual thread does <strong>not</strong> — verified, not assumed: a
 * value bound via {@code ScopedValue.where(...).run(...)} reads back {@code isBound() == false}
 * inside {@code Thread.ofVirtual().start(...)}, and {@code true} inside a forked subtask. There is
 * also no GA API that snapshots a thread's live bindings, so a drop-in replacement that silently
 * preserved inheritance cannot be written.
 *
 * <p>Rather than lose the bindings quietly, this class makes the choice unavoidable at the call
 * site: {@link #open(ScopedValue.Carrier)} re-establishes a carrier inside every forked body, and
 * {@link #openWithoutBindings()} states in its name that children start unbound. There is
 * deliberately no no-argument {@code open()} — the failure mode it would produce (a child reading
 * an unbound provider slot, far from the fork) is exactly the kind this API exists to prevent.
 *
 * <h2>Await-all, never fail-fast</h2>
 *
 * <p>{@link #join()} waits for every forked task and returns normally even when some failed; the
 * caller inspects {@link ForkedTask#state()} and decides. This is not a simplification of
 * {@code StructuredTaskScope} but a deliberate narrowing to the one policy the kernel's
 * orchestration paths actually use, and it removes a preview type from an observable failure
 * contract: {@code StructuredTaskScope.open()} defaults to {@code Joiner.awaitAllSuccessfulOrThrow()},
 * whose {@code join()} throws the preview class {@code StructuredTaskScope.FailedException}.
 *
 * <h2>Structured guarantee</h2>
 *
 * <p>No forked task outlives the scope. {@link #close()} interrupts anything still running and
 * joins it before returning, so a task cannot escape the block that created it. Use
 * try-with-resources; {@code fork} and {@code join} are confined to the thread that opened the
 * scope and reject calls from any other.
 *
 * <h2>Memory model</h2>
 *
 * <p>Two groups of state, protected by two different things — the mix of plain and {@code volatile}
 * fields below is deliberate, not an oversight.
 *
 * <p><strong>Scope state</strong> ({@code joined}, {@code closed}, the task list) is plain, and is
 * confined to the owning thread: every method that touches it calls {@code ensureOwner()} before
 * doing so, {@code close()} included. Nothing here is safe to call from another thread, and the API
 * rejects the attempt rather than tolerating it.
 *
 * <p><strong>Task results</strong> ({@code result}, {@code exception}) are written by the forked
 * thread and read by the owner, and are plain fields made safe by two independent happens-before
 * edges — either one alone would suffice:
 * <ul>
 *   <li>{@code Thread.join()} in {@code awaitTermination()}: everything the forked thread did
 *       happens-before the owner returns from the join, so after {@link #join()} the owner sees the
 *       results (JLS 17.4.5).</li>
 *   <li>the {@code volatile} write to {@code state}, which every task performs <em>after</em>
 *       writing its result or exception. A reader that observes {@code SUCCESS} or {@code FAILED}
 *       therefore also observes what was written before it — which is what makes
 *       {@link ForkedTask#state()} safe to poll before joining, not merely after.</li>
 * </ul>
 *
 * <p>{@code state} moves once, monotonically, from {@code RUNNING} to a terminal value, so a reader
 * can never observe it going backwards.
 *
 * @since 0.11
 */
public final class StructuredScope implements AutoCloseable {

    private final ScopedValue.Carrier bindings;
    private final Thread owner;
    private final List<ForkedTask<?>> tasks = new ArrayList<>();

    private boolean joined;
    private boolean closed;

    // java:S8432 — the carrier is deliberately NOT run here. Storing it and applying it later, on
    // each child thread, is the whole mechanism: the binding must be re-established where the work
    // runs, not where the scope is opened.
    @SuppressWarnings("java:S8432")
    private StructuredScope(ScopedValue.Carrier bindings) {
        this.bindings = bindings;
        this.owner = Thread.currentThread();
    }

    /**
     * Opens a scope whose forked tasks run inside the supplied bindings.
     *
     * <p>The carrier is applied per task, on the child thread, immediately before the body runs —
     * so every task observes exactly the values the carrier carries, regardless of what the owner
     * thread happens to have bound at fork time.
     *
     * @param bindings the bindings to re-establish in each forked task; never {@code null}
     * @return an open scope
     */
    public static StructuredScope open(ScopedValue.Carrier bindings) {
        Objects.requireNonNull(bindings, "bindings");
        return new StructuredScope(bindings);
    }

    /**
     * Opens a scope whose forked tasks start with no {@link ScopedValue} bindings.
     *
     * <p>Correct only when the task bodies read no scoped value — directly or through any SPI they
     * call. When in doubt, prefer {@link #open(ScopedValue.Carrier)}: an unbound read fails where
     * the value is used, not where the binding was dropped.
     *
     * @return an open scope
     */
    public static StructuredScope openWithoutBindings() {
        return new StructuredScope(null);
    }

    /**
     * Forks {@code body} onto a fresh virtual thread.
     *
     * <p>Returns immediately. The returned handle is only meaningful after {@link #join()}.
     *
     * @param body the work to run; may throw
     * @param <T>  the result type
     * @return a handle on the forked task
     * @throws IllegalStateException if called after {@link #join()} or {@link #close()}, or from a
     *                               thread other than the one that opened the scope
     */
    public <T> ForkedTask<T> fork(Callable<T> body) {
        Objects.requireNonNull(body, "body");
        ensureOwner();
        if (joined || closed) {
            throw new IllegalStateException("fork() after join()/close()");
        }
        ForkedTask<T> task = new ForkedTask<>(body, bindings);
        tasks.add(task);
        task.start();
        return task;
    }

    /**
     * Waits for every forked task to finish.
     *
     * <p>Returns normally whether the tasks succeeded or failed — inspect {@link ForkedTask#state()}.
     * Idempotent after the first successful call.
     *
     * @throws InterruptedException  if the owner thread is interrupted while waiting; the scope is
     *                               left un-joined so {@link #close()} still cancels the stragglers
     * @throws IllegalStateException if called from a thread other than the one that opened the
     *                               scope, or after {@link #close()}
     */
    public void join() throws InterruptedException {
        ensureOwner();
        if (closed) {
            throw new IllegalStateException("join() after close()");
        }
        for (ForkedTask<?> task : tasks) {
            task.awaitTermination();
        }
        joined = true;
    }

    /**
     * Interrupts every task that is still running.
     *
     * <p>Best-effort and non-blocking: a task that ignores interruption keeps running until
     * {@link #close()} joins it.
     */
    public void cancel() {
        ensureOwner();
        for (ForkedTask<?> task : tasks) {
            task.interruptIfRunning();
        }
    }

    /**
     * Cancels anything still running and waits for it, so no task outlives the scope.
     *
     * <p>Interruption of the closing thread is absorbed and re-asserted on the current thread once
     * every task has terminated: leaving a forked thread alive would break the structured
     * guarantee, which is the stronger obligation.
     */
    // java:S2142 — the interrupt is re-asserted after the loop, not inside the catch, and that
    // ordering is load-bearing: re-interrupting immediately would make the very next join() throw
    // at once, so the "uninterruptible join" would spin instead of waiting. The obvious fix breaks
    // the structured guarantee this method exists to provide.
    @SuppressWarnings("java:S2142")
    @Override
    public void close() {
        // Owner check FIRST: `closed` is a plain field, so reading it before establishing that we
        // are on the owning thread would be an unsynchronized read of a field another thread wrote.
        // Harmless in outcome — the worst case is a stale `false` followed by the check below — but
        // it is the one place that would have made "these booleans are thread-confined" untrue.
        ensureOwner();
        if (closed) {
            return;
        }
        closed = true;
        for (ForkedTask<?> task : tasks) {
            task.interruptIfRunning();
        }
        boolean interrupted = false;
        for (ForkedTask<?> task : tasks) {
            while (true) {
                try {
                    task.awaitTermination();
                    break;
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // Reference identity is the only correct comparison for thread confinement: two distinct
    // Thread objects are two distinct threads regardless of what equals() would say.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void ensureOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "StructuredScope is confined to its opening thread");
        }
    }

    /** Terminal state of a forked task, observed after {@link StructuredScope#join()}. */
    public enum State {
        /** Still running, or the scope has not been joined yet. */
        RUNNING,
        /** Completed and produced a result. */
        SUCCESS,
        /** Completed by throwing. */
        FAILED
    }

    /**
     * A handle on one forked task.
     *
     * @param <T> the result type
     */
    public static final class ForkedTask<T> {

        private final Callable<T> body;
        private final ScopedValue.Carrier bindings;
        private final Thread thread;

        private volatile State state = State.RUNNING;
        private T result;
        private Throwable exception;

        // java:S8432 — see StructuredScope's constructor: the carrier is applied in execute(),
        // which runs on the forked thread, not here on the forking one.
        @SuppressWarnings("java:S8432")
        private ForkedTask(Callable<T> body, ScopedValue.Carrier bindings) {
            this.body = body;
            this.bindings = bindings;
            // Created unstarted so the constructor never publishes a running task.
            this.thread = Thread.ofVirtual().unstarted(this::execute);
        }

        private void start() {
            thread.start();
        }

        private void interruptIfRunning() {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }

        private void awaitTermination() throws InterruptedException {
            thread.join();
        }

        // java:S1181 — a task's failure is data, and that has to include Errors: a subsystem that
        // fails with NoClassDefFoundError must be reportable to the caller, not lost to a dying
        // virtual thread with only a default handler printout. StructuredTaskScope makes the same
        // choice — Subtask.exception() is typed Throwable.
        @SuppressWarnings("java:S1181")
        private void execute() {
            try {
                if (bindings == null) {
                    result = body.call();
                } else {
                    result = bindings.call(body::call);
                }
                state = State.SUCCESS;
            } catch (Throwable failure) { //NOPMD AvoidCatchingThrowable — a task's failure is data
                exception = failure;
                state = State.FAILED;
            }
        }

        /**
         * @return the task's state; {@link State#RUNNING} until it terminates
         */
        public State state() {
            return state;
        }

        /**
         * @return the value the task produced
         * @throws IllegalStateException if the task did not succeed
         */
        public T result() {
            if (state != State.SUCCESS) {
                throw new IllegalStateException("task did not succeed: " + state);
            }
            return result;
        }

        /**
         * @return the throwable the task terminated with
         * @throws IllegalStateException if the task did not fail
         */
        public Throwable exception() {
            if (state != State.FAILED) {
                throw new IllegalStateException("task did not fail: " + state);
            }
            return exception;
        }
    }
}
