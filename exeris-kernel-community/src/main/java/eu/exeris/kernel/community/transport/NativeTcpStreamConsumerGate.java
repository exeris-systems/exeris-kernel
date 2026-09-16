/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Package-private static helpers enforcing the single-consumer invariant on
 * the inbound and outbound queue gates of {@link NativeTcpStream}.
 *
 * <p>One virtual thread at a time owns a given consumer slot; a reentrant acquisition by the
 * thread already holding it is a no-op, and a concurrent acquisition attempt by a different
 * thread is fail-fast.
 */
final class NativeTcpStreamConsumerGate {

    private NativeTcpStreamConsumerGate() {
        // package-private static utility — never instantiated.
    }

    /**
     * Acquires the single-consumer slot for {@code currentThread}. Throws if a
     * different thread already owns the slot — concurrent consumption is a
     * programming error on the stream's read/flush path.
     */
    /* default */ static void acquireSingleConsumer(AtomicReference<Thread> consumerRef,
                                                    Thread currentThread,
                                                    String queueName) {
        Thread owner = consumerRef.get();
        if (Objects.equals(owner, currentThread)) {
            return;
        }
        if (!consumerRef.compareAndSet(null, currentThread)) {
            throw new IllegalStateException(
                    "Concurrent " + queueName + " queue consumer detected for stream thread "
                            + currentThread.getName());
        }
    }

    /**
     * Non-throwing variant for best-effort cleanup paths: returns {@code true}
     * if {@code currentThread} now owns the slot (either reentrant or freshly
     * acquired), {@code false} when another thread holds it.
     */
    /* default */ static boolean tryAcquireSingleConsumer(AtomicReference<Thread> consumerRef,
                                                          Thread currentThread) {
        Thread owner = consumerRef.get();
        return Objects.equals(owner, currentThread)
                || (owner == null && consumerRef.compareAndSet(null, currentThread));
    }

    /**
     * Releases the slot iff {@code currentThread} currently owns it. Safe to
     * call from {@code finally} blocks even when acquisition failed.
     */
    /* default */ static void releaseSingleConsumer(AtomicReference<Thread> consumerRef,
                                                    Thread currentThread) {
        consumerRef.compareAndSet(currentThread, null);
    }
}
