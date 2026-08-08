/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <p>This class is not thread-safe by design: confinement to the owner thread is the invariant that
 * makes the task list safe to publish without synchronization.
 *
 * @since 0.11.0
 */
public final class StructuredScope implements AutoCloseable {

    private final ScopedValue.Carrier bindings;
    private final Thread owner;
    private final List<ForkedTask<?>> tasks = new ArrayList<>();

    private boolean joined;
    private boolean closed;

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
    @Override
    public void close() {
        if (closed) {
            return;
        }
        ensureOwner();
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
