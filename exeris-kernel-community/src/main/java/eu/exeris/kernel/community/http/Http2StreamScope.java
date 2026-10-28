/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

/**
 * Community: the scope one HTTP/2 connection forks its response tasks into, over a
 * {@link StructuredTaskScope}.
 *
 * <p>The scope lives as long as the connection and forks repeatedly, so it keeps nothing about a task
 * that has finished: its joiner records no subtask, and a long-lived connection holds only the tasks
 * still running.
 *
 * <p>Closing interrupts the running tasks and then waits for them. {@code StructuredTaskScope} cancels
 * only through its joiner, and its {@code close()} refuses an owner that never joined, so each task
 * registers its thread while it runs; {@link #close()} interrupts those threads, joins and closes. A
 * task that starts after closing began interrupts itself, so none is missed between the two.
 *
 * <p><b>Thread confinement:</b> {@link #fork} and {@link #close} on the owner, the thread that created
 * the scope. A task inherits the {@link ScopedValue} bindings that were bound on that thread when the
 * scope was created.
 * <p><b>Ownership:</b> owned by the {@link Http2Connection} that created it, which closes it.
 */
final class Http2StreamScope implements AutoCloseable {

    private final StructuredTaskScope<Object, Void, RuntimeException> scope =
            StructuredTaskScope.open(new DiscardingJoiner());
    private final Set<Thread> running = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    /**
     * Forks a task into the scope.
     *
     * @param task the task; its result is not kept
     * @throws IllegalStateException if the scope is closed
     * @throws WrongThreadException  if called from a thread other than the owner
     */
    /* default */ void fork(Callable<?> task) {
        scope.fork(() -> {
            Thread self = Thread.currentThread();
            running.add(self);
            try {
                if (closing) {
                    self.interrupt();
                }
                return task.call();
            } finally {
                running.remove(self);
            }
        });
    }

    /**
     * Interrupts the running tasks, waits for them, and closes the scope.
     */
    @Override
    public void close() {
        closing = true;
        running.forEach(Thread::interrupt);
        try {
            scope.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            scope.close();
        }
    }

    /** Records nothing, so a finished task is not retained for the life of the connection. */
    private static final class DiscardingJoiner implements StructuredTaskScope.Joiner<Object, Void, RuntimeException> {

        @Override
        public Void result() {
            return null;
        }

        @Override
        public Void timeout() {
            return null;
        }
    }
}
