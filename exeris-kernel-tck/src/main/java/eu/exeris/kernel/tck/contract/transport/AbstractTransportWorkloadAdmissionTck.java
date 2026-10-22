/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: the connection obligations a server-side transport owes a {@link WorkloadAdmission} (the
 * developer-licence workload envelope): every inbound connection counts, a connection past the ceiling
 * is closed right after accept with no byte written, and every admitted connection is released exactly
 * once.
 *
 * <h2>What the suite is built to catch</h2>
 * <p>A driver that never consults the gate looks, from outside, like a driver the gate lets through, so
 * the enforcement case has controls that serve the same load with the gate {@link WorkloadAdmission#UNGATED},
 * not enforced, or not bound at all. A driver that reads the slot on an accept thread, where no binding is
 * visible, fails the enforcement case and passes the controls, which is the fail-open shape the slot's
 * contract names. The suite binds a gate of its own with {@link ScopedValue} around {@link #startEngine}.
 *
 * <h2>Binding</h2>
 * <p>A binding builds an unstarted server engine through {@link #createEngine}; the suite starts it inside
 * the scope that binds the gate, because the engine reads the slot in {@code start()}.
 *
 * @since 0.13
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public abstract class AbstractTransportWorkloadAdmissionTck {

    private static final int CONNECT_LIMIT_MILLIS = 5_000;
    private static final int READ_LIMIT_MILLIS = 10_000;
    private static final long SETTLE_LIMIT_MILLIS = 10_000L;
    private static final String LOOPBACK = "127.0.0.1";

    private final List<TransportEngine> engines = new ArrayList<>();

    /** Constructor for subclasses. */
    protected AbstractTransportWorkloadAdmissionTck() {
        // binding classes build the engine under test
    }

    /**
     * Builds an unstarted server engine bound to {@code port}, with {@code handler} registered.
     *
     * @param port    the port to bind
     * @param handler the handler the engine invokes for every admitted stream
     * @return an unstarted server-mode engine; the suite starts and closes it
     * @implSpec The engine's own connection ceiling must be far above the handful of connections a case
     *           opens, so that only the workload gate can refuse one.
     */
    protected abstract TransportEngine createEngine(int port, StreamHandler handler);

    /**
     * Returns a TCP port the binding can bind and a client can reach on the loopback interface.
     *
     * @return a free port
     */
    protected abstract int freePort();

    @AfterEach
    final void closeEngines() {
        engines.forEach(TransportEngine::close);
        engines.clear();
    }

    @Test
    @DisplayName("past the ceiling a connection is closed with no byte read, a close frees its place, "
            + "and every admission is released once")
    void connectionCeiling() throws Exception {
        Gate gate = new Gate(true, 2);
        Held held = new Held();
        int port = startEngine(gate, held);

        Socket first = connect(port);
        Socket second = connect(port);
        try {
            awaitHandled(held, 2);

            try (Socket third = connect(port)) {
                assertClosedWithNoBytes(third);
            }
            assertThat(held.handled()).as("the refused connection reached no handler").isEqualTo(2);

            held.release(0);
            awaitLive(gate, 1);
            Socket fourth = connect(port);
            try {
                awaitHandled(held, 3);
                assertThat(held.handled()).as("a connection opened after one closed is served").isEqualTo(3);
            } finally {
                fourth.close();
            }
        } finally {
            held.releaseAll();
            first.close();
            second.close();
        }

        awaitLive(gate, 0);
        assertThat(gate.admitted()).as("connections admitted: first, second, fourth").isEqualTo(3);
        assertThat(gate.released())
                .as("every admitted connection is released exactly once, never the refused one")
                .isEqualTo(gate.admitted());
    }

    @Test
    @DisplayName("stopping the engine releases every admitted connection exactly once")
    void stopReleasesEveryAdmission() throws Exception {
        Gate gate = new Gate(true, 10);
        Held held = new Held();
        int port = startEngine(gate, held);

        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                sockets.add(connect(port));
            }
            awaitHandled(held, 3);
            assertThat(gate.live()).as("connections held before the stop").isEqualTo(3);

            // stop() drains: it returns once the in-flight streams end, so the handlers are released
            // while it runs, and the engine closes what the drain leaves.
            Thread stopper = Thread.ofPlatform().start(engines.get(0)::stop);
            held.releaseAll();
            stopper.join(SETTLE_LIMIT_MILLIS);
            assertThat(stopper.isAlive()).as("stop() returned after the drain").isFalse();

            awaitLive(gate, 0);
            assertThat(gate.admitted()).isEqualTo(3);
            assertThat(gate.released()).as("admissions released by the stop path").isEqualTo(gate.admitted());
        } finally {
            held.releaseAll();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("UNGATED bound: more connections than any ceiling are all served")
    void ungatedBound() throws Exception {
        Held held = new Held();
        int port = startEngine(WorkloadAdmission.UNGATED, held);

        assertAllServed(port, held);
    }

    @Test
    @DisplayName("a gate that reports enforced() == false is never consulted")
    void unenforcedGateIsNeverConsulted() throws Exception {
        Gate gate = new Gate(false, 0);
        Held held = new Held();
        int port = startEngine(gate, held);

        assertAllServed(port, held);

        assertThat(gate.calls()).as("tryAdmitConnection calls").isZero();
        assertThat(gate.released()).as("connectionClosed calls").isZero();
    }

    @Test
    @DisplayName("no binding at all behaves as UNGATED")
    void unbound() throws Exception {
        Held held = new Held();
        int port = startEngine(null, held);

        assertAllServed(port, held);
    }

    private int startEngine(WorkloadAdmission gate, StreamHandler handler) {
        int port = freePort();
        TransportEngine engine = createEngine(port, handler);
        engines.add(engine);
        if (gate == null) {
            engine.start();
        } else {
            ScopedValue.where(ContractKernelProviders.WORKLOAD_ADMISSION, gate).run(engine::start);
        }
        return port;
    }

    private static void assertAllServed(int port, Held held) throws Exception {
        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                sockets.add(connect(port));
            }
            awaitHandled(held, 5);
            assertThat(held.handled()).as("connections served with no ceiling in force").isEqualTo(5);
        } finally {
            held.releaseAll();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(LOOPBACK, port), CONNECT_LIMIT_MILLIS);
            socket.setSoTimeout(READ_LIMIT_MILLIS);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private static void assertClosedWithNoBytes(Socket socket) throws IOException {
        assertThat(readOrClosed(socket))
                .as("a refused connection is closed before the server writes a byte")
                .isEqualTo(-1);
    }

    private static int readOrClosed(Socket socket) throws IOException {
        try {
            return socket.getInputStream().read();
        } catch (SocketException _) {
            // A reset is a close with no bytes either; a read that timed out is not caught here and fails the case.
            return -1;
        }
    }

    private static void awaitHandled(Held held, int expected) throws InterruptedException {
        assertThat(held.handled.await(count -> count >= expected, SETTLE_LIMIT_MILLIS))
                .as("streams delivered to the handler: %d, expected %d", held.handled(), expected)
                .isTrue();
    }

    private static void awaitLive(Gate gate, int expected) throws InterruptedException {
        assertThat(gate.live.await(count -> count == expected, SETTLE_LIMIT_MILLIS))
                .as("admitted connections still held: %d, expected %d", gate.live(), expected)
                .isTrue();
    }

    /** Counts the streams it receives and holds each until the case releases it, then closes it. */
    private static final class Held implements StreamHandler {

        private final AwaitableCount handled = new AwaitableCount();
        private final List<CountDownLatch> releases = new CopyOnWriteArrayList<>();

        @Override
        public void handle(TransportStream stream) {
            CountDownLatch release = new CountDownLatch(1);
            releases.add(release);
            handled.increment();
            try (stream) {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        int handled() {
            return handled.get();
        }

        /** Lets the handler of the {@code index}-th stream received close it. */
        void release(int index) {
            releases.get(index).countDown();
        }

        void releaseAll() {
            releases.forEach(CountDownLatch::countDown);
        }
    }

    /**
     * A count a case can wait on: every change signals the waiters, so a wait ends on the event that
     * satisfies it, or on its timeout, and never re-checks on a timer.
     */
    private static final class AwaitableCount {

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private int value;

        int get() {
            lock.lock();
            try {
                return value;
            } finally {
                lock.unlock();
            }
        }

        int increment() {
            return add(1);
        }

        int decrement() {
            return add(-1);
        }

        /** Takes one place if the count is below {@code ceiling}. */
        boolean incrementBelow(int ceiling) {
            lock.lock();
            try {
                if (value >= ceiling) {
                    return false;
                }
                value++;
                changed.signalAll();
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** Waits until {@code condition} holds for the count; returns whether it did within the timeout. */
        boolean await(IntPredicate condition, long timeoutMillis) throws InterruptedException {
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            lock.lock();
            try {
                while (!condition.test(value)) {
                    if (remaining <= 0) {
                        return false;
                    }
                    remaining = changed.awaitNanos(remaining);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        private int add(int delta) {
            lock.lock();
            try {
                value += delta;
                changed.signalAll();
                return value;
            } finally {
                lock.unlock();
            }
        }
    }

    /** A gate of the suite's own: a bounded count of live connections, with every call counted. */
    private static final class Gate implements WorkloadAdmission {

        private final boolean enforced;
        private final int connectionCeiling;
        private final AtomicInteger calls = new AtomicInteger();
        private final AwaitableCount live = new AwaitableCount();
        private final AtomicInteger admitted = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();

        Gate(boolean enforced, int connectionCeiling) {
            this.enforced = enforced;
            this.connectionCeiling = connectionCeiling;
        }

        @Override
        public boolean enforced() {
            return enforced;
        }

        @Override
        public boolean tryAdmitConnection() {
            calls.incrementAndGet();
            if (!live.incrementBelow(connectionCeiling)) {
                return false;
            }
            admitted.incrementAndGet();
            return true;
        }

        @Override
        public void connectionClosed() {
            released.incrementAndGet();
            live.decrement();
        }

        @Override
        public boolean tryAdmitRequest() {
            return true;
        }

        int calls() {
            return calls.get();
        }

        int live() {
            return live.get();
        }

        int admitted() {
            return admitted.get();
        }

        int released() {
            return released.get();
        }
    }
}
