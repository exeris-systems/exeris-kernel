/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("KernelBootstrap — shutdown entry point and JVM hook")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class KernelBootstrapShutdownTest {

    private static final long JOIN_MILLIS = 15_000;
    private static final long NEGATIVE_WAIT_MILLIS = 300;

    /** Discovers the subsystems a test set for the current boot. */
    public static final class ProbeProvider implements SubsystemProvider {

        static volatile List<Subsystem> subsystems = List.of();

        /** Required public no-arg constructor for {@link java.util.ServiceLoader}. */
        public ProbeProvider() {
            // ServiceLoader-mandated no-op constructor.
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return subsystems;
        }
    }

    /** Records every registration so a test can see what the kernel asked the JVM for. */
    static final class RecordingShutdownHookRegistry implements ShutdownHookRegistry {

        private final List<Thread> registered = new CopyOnWriteArrayList<>();
        private final List<Thread> unregistered = new CopyOnWriteArrayList<>();

        @Override
        public void register(Thread hook) {
            registered.add(hook);
        }

        @Override
        public void unregister(Thread hook) {
            unregistered.add(hook);
        }

        List<Thread> registered() {
            return registered;
        }

        List<Thread> unregistered() {
            return unregistered;
        }
    }

    /** A boot running on its own platform thread, with whatever it threw. */
    private static final class BootRun {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final Thread thread;

        BootRun(KernelBootstrap bootstrap, String name, Runnable kernelMain) {
            this.thread = Thread.ofPlatform().daemon(true).name(name).unstarted(() -> {
                try {
                    bootstrap.boot(kernelMain);
                } catch (Throwable t) { // NOPMD — captured for the asserting thread
                    failure.set(t);
                } finally {
                    finished.countDown();
                }
            });
        }

        BootRun start() {
            thread.start();
            return this;
        }

        void awaitReturned() {
            join(thread);
            assertThat(thread.isAlive()).as("boot thread %s returned", thread.getName()).isFalse();
            assertThat(failure.get()).as("what boot() threw").isNull();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static KernelBootstrap bootstrap(ShutdownHookRegistry registry, Boolean hook) {
        ClassLoader shim = new KernelBootstrapTest.DualServiceShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        KernelBootstrapTest.StubConfigProvider.class.getName(),
                        "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                        ProbeProvider.class.getName()));
        KernelBootstrap.Builder builder = KernelBootstrap.builder()
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                .selector(BootstrapSelector.all())
                .classLoader(shim)
                .shutdownHookRegistry(registry);
        if (hook != null) {
            builder.registerShutdownHook(hook);
        }
        return builder.build();
    }

    /** A kernel with the builder's default hook setting. */
    private static KernelBootstrap bootstrap(ShutdownHookRegistry registry) {
        return bootstrap(registry, null);
    }

    private static void provide(Subsystem... subsystems) {
        ProbeProvider.subsystems = List.of(subsystems);
    }

    private static void join(Thread thread) {
        try {
            thread.join(JOIN_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining " + thread.getName(), ex);
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            assertThat(latch.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)).as(what).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + what, ex);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits a bounded time for {@code thread}; the thread must still be alive afterwards. */
    private static void assertStillBlocked(Thread thread) {
        try {
            thread.join(NEGATIVE_WAIT_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while observing " + thread.getName(), ex);
        }
        assertThat(thread.isAlive()).as("%s is still blocked", thread.getName()).isTrue();
    }

    private static Thread callShutdown(KernelBootstrap bootstrap, String name) {
        Thread thread = Thread.ofPlatform().daemon(true).name(name).unstarted(bootstrap::shutdown);
        thread.start();
        return thread;
    }

    // ── 1 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() before boot() is a no-op")
    void shutdownBeforeBootIsANoOp() throws Exception {
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null);
        provide(probe);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        AtomicBoolean mainRan = new AtomicBoolean();

        bootstrap.shutdown();
        bootstrap.boot(() -> mainRan.set(true));

        assertThat(mainRan).isTrue();
        assertThat(probe.stopCount()).isEqualTo(1);
    }

    // ── 2 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() after boot() returned is a no-op")
    void shutdownAfterBootReturnedIsANoOp() throws Exception {
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null);
        provide(probe);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());

        bootstrap.boot(() -> { });
        bootstrap.shutdown();

        assertThat(probe.stopCount()).isEqualTo(1);
    }

    // ── 3 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() during inspect() is a no-op and no hook is registered")
    void shutdownDuringInspectIsANoOp() throws Exception {
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null);
        provide(probe);
        RecordingShutdownHookRegistry registry = new RecordingShutdownHookRegistry();
        KernelBootstrap bootstrap = bootstrap(registry);
        AtomicBoolean returned = new AtomicBoolean();

        bootstrap.inspect(() -> {
            bootstrap.shutdown();
            returned.set(true);
        });

        assertThat(returned).isTrue();
        assertThat(registry.registered()).isEmpty();
        assertThat(probe.stopCount()).isZero();
    }

    // ── 4 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() from an unbound platform thread completes the ordered stop before it returns,"
            + " and leaves kernelMain running")
    void shutdownFromAnUnboundThreadCompletesTheOrderedStop() {
        List<String> stopLog = new CopyOnWriteArrayList<>();
        AtomicBoolean stopReturned = new AtomicBoolean();
        ShutdownProbe a = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, stopLog);
        ShutdownProbe b = new ShutdownProbe("b", BootstrapPhase.SERVICES, stopLog, "a").onStop(() -> {
            try {
                Thread.sleep(200); // makes a shutdown() that returns early observable
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            stopReturned.set(true);
        });
        provide(a, b);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        CountDownLatch mainEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> mainThread = new AtomicReference<>();
        AtomicBoolean mainInterrupted = new AtomicBoolean();
        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> {
            mainThread.set(Thread.currentThread());
            mainEntered.countDown();
            awaitUninterruptibly(release);
            mainInterrupted.set(Thread.currentThread().isInterrupted());
        }).start();
        await(mainEntered, "kernelMain entered");

        bootstrap.shutdown();

        assertThat(a.stopCount()).isEqualTo(1);
        assertThat(b.stopCount()).isEqualTo(1);
        assertThat(stopReturned).isTrue();
        assertThat(stopLog).containsExactly("b", "a");
        assertThat(mainThread.get().isAlive()).isTrue();
        assertThat(mainThread.get().isInterrupted()).isFalse();

        release.countDown();
        run.awaitReturned();
        assertThat(mainInterrupted).isFalse();
        assertThat(a.stopCount()).isEqualTo(1);
        assertThat(b.stopCount()).isEqualTo(1);
    }

    // ── 5 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a caller that loses the race waits until the winner's stop has returned")
    void aLosingCallerWaitsForTheWinnersStop() {
        CountDownLatch inStop = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null).onStop(() -> {
            inStop.countDown();
            awaitUninterruptibly(releaseStop);
        });
        provide(probe);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        CountDownLatch mainEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> {
            mainEntered.countDown();
            awaitUninterruptibly(release);
        }).start();
        await(mainEntered, "kernelMain entered");

        Thread winner = callShutdown(bootstrap, "shutdown-winner");
        await(inStop, "the winner is inside stop()");
        Thread loser = callShutdown(bootstrap, "shutdown-loser");

        assertStillBlocked(loser);
        assertThat(probe.stopCount()).isEqualTo(1);

        releaseStop.countDown();
        join(winner);
        join(loser);
        assertThat(winner.isAlive()).isFalse();
        assertThat(loser.isAlive()).isFalse();
        assertThat(probe.stopCount()).isEqualTo(1);

        release.countDown();
        run.awaitReturned();
    }

    // ── 6 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("repeated boots: shutdown() racing kernelMain's return runs each stop() once")
    void shutdownRacingKernelMainReturnRunsEachStopOnce() throws Exception {
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        for (int round = 0; round < 200; round++) {
            ShutdownProbe a = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null);
            ShutdownProbe b = new ShutdownProbe("b", BootstrapPhase.SERVICES, null, "a");
            ShutdownProbe c = new ShutdownProbe("c", BootstrapPhase.SERVICES, null, "b");
            provide(a, b, c);
            KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
            CyclicBarrier barrier = new CyclicBarrier(4);
            List<Thread> callers = new ArrayList<>();
            int observed = round;

            bootstrap.boot(() -> {
                for (int i = 0; i < 3; i++) {
                    Thread caller = Thread.ofPlatform().daemon(true).name("shutdown-racer-" + i).unstarted(() -> {
                        try {
                            barrier.await(JOIN_MILLIS, TimeUnit.MILLISECONDS);
                            bootstrap.shutdown();
                            if (a.stopCount() != 1 || b.stopCount() != 1 || c.stopCount() != 1) {
                                violations.add("round " + observed + ": a caller returned before every stop completed");
                            }
                        } catch (Exception ex) { // NOPMD — reported through violations
                            violations.add("round " + observed + ": " + ex);
                        }
                    });
                    callers.add(caller);
                    caller.start();
                }
                try {
                    barrier.await(JOIN_MILLIS, TimeUnit.MILLISECONDS);
                } catch (Exception ex) { // NOPMD — reported through violations
                    violations.add("round " + observed + ": " + ex);
                }
            });

            for (Thread caller : callers) {
                join(caller);
                assertThat(caller.isAlive()).as("racer returned").isFalse();
            }
            assertThat(List.of(a.stopCount(), b.stopCount(), c.stopCount()))
                    .as("stop counts in round %d", round).containsExactly(1, 1, 1);
        }
        assertThat(violations).isEmpty();
    }

    // ── 7 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() called from a stop() returns without deadlock")
    void shutdownFromAStopReturns() {
        AtomicReference<KernelBootstrap> self = new AtomicReference<>();
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null)
                .onStop(() -> self.get().shutdown());
        provide(probe);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        self.set(bootstrap);
        CountDownLatch mainEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> {
            mainEntered.countDown();
            awaitUninterruptibly(release);
        }).start();
        await(mainEntered, "kernelMain entered");

        Thread caller = callShutdown(bootstrap, "shutdown-caller");
        join(caller);

        assertThat(caller.isAlive()).as("shutdown() returned").isFalse();
        assertThat(probe.stopCount()).isEqualTo(1);
        release.countDown();
        run.awaitReturned();
    }

    // ── 8 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() called from kernelMain runs the stop inline")
    void shutdownFromKernelMainRunsTheStopInline() throws Exception {
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null);
        provide(probe);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        AtomicInteger stopsSeenInMain = new AtomicInteger(-1);

        bootstrap.boot(() -> {
            bootstrap.shutdown();
            stopsSeenInMain.set(probe.stopCount());
        });

        assertThat(stopsSeenInMain).hasValue(1);
        assertThat(probe.stopCount()).isEqualTo(1);
    }

    // ── 9 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() during initialize() skips start() and kernelMain, and returns after the stop")
    void shutdownDuringInitializeSkipsStartAndKernelMain() {
        CountDownLatch inInitialize = new CountDownLatch(1);
        CountDownLatch releaseInitialize = new CountDownLatch(1);
        ShutdownProbe p1 = new ShutdownProbe("p1", BootstrapPhase.FOUNDATION, null);
        ShutdownProbe p2 = new ShutdownProbe("p2", BootstrapPhase.SERVICES, null, "p1").onInitialize(() -> {
            inInitialize.countDown();
            awaitUninterruptibly(releaseInitialize);
        });
        provide(p1, p2);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        AtomicBoolean mainRan = new AtomicBoolean();
        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> mainRan.set(true)).start();
        await(inInitialize, "p2 is inside initialize()");

        Thread caller = callShutdown(bootstrap, "shutdown-caller");
        assertStillBlocked(caller);

        releaseInitialize.countDown();
        join(caller);
        run.awaitReturned();

        assertThat(caller.isAlive()).isFalse();
        assertThat(mainRan).isFalse();
        assertThat(p2.startCount()).isZero();
        assertThat(p1.startCount()).isZero();
    }

    // ── 10 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() during start() skips kernelMain and stops what started")
    void shutdownDuringStartSkipsKernelMainAndStopsWhatStarted() {
        CountDownLatch inStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        ShutdownProbe p1 = new ShutdownProbe("p1", BootstrapPhase.FOUNDATION, null);
        ShutdownProbe p2 = new ShutdownProbe("p2", BootstrapPhase.SERVICES, null, "p1").onStart(() -> {
            inStart.countDown();
            awaitUninterruptibly(releaseStart);
        });
        provide(p1, p2);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        AtomicBoolean mainRan = new AtomicBoolean();
        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> mainRan.set(true)).start();
        await(inStart, "p2 is inside start()");
        AtomicInteger stopsSeenByCaller = new AtomicInteger(-1);
        Thread caller = Thread.ofPlatform().daemon(true).name("shutdown-caller").unstarted(() -> {
            bootstrap.shutdown();
            stopsSeenByCaller.set(p1.stopCount() + p2.stopCount());
        });
        caller.start();
        assertStillBlocked(caller);

        releaseStart.countDown();
        join(caller);
        run.awaitReturned();

        assertThat(mainRan).isFalse();
        assertThat(p1.stopCount()).isEqualTo(1);
        assertThat(p2.stopCount()).isEqualTo(1);
        assertThat(stopsSeenByCaller).hasValue(2);
    }

    // ── 11 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown() from start() on the booting thread does not deadlock")
    void shutdownFromStartOnTheBootingThreadDoesNotDeadlock() {
        AtomicReference<KernelBootstrap> self = new AtomicReference<>();
        ShutdownProbe p1 = new ShutdownProbe("p1", BootstrapPhase.FOUNDATION, null);
        ShutdownProbe p2 = new ShutdownProbe("p2", BootstrapPhase.SERVICES, null, "p1")
                .onStart(() -> self.get().shutdown());
        provide(p1, p2);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        self.set(bootstrap);
        AtomicBoolean mainRan = new AtomicBoolean();

        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> mainRan.set(true)).start();
        run.awaitReturned();

        assertThat(mainRan).isFalse();
        assertThat(p1.stopCount()).isEqualTo(1);
        assertThat(p2.stopCount()).isEqualTo(1);
    }

    // ── 12 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("boot() registers one platform hook before any initialize() and removes it before returning")
    void bootRegistersOneHookBeforeInitializeAndRemovesIt() throws Exception {
        RecordingShutdownHookRegistry registry = new RecordingShutdownHookRegistry();
        AtomicInteger registeredAtInitialize = new AtomicInteger(-1);
        ShutdownProbe p1 = new ShutdownProbe("p1", BootstrapPhase.FOUNDATION, null)
                .onInitialize(() -> registeredAtInitialize.set(registry.registered().size()));
        provide(p1);

        bootstrap(registry).boot(() -> { });

        assertThat(registeredAtInitialize).hasValue(1);
        assertThat(registry.registered()).hasSize(1);
        Thread hook = registry.registered().get(0);
        assertThat(hook.isVirtual()).isFalse();
        assertThat(hook.getName()).isEqualTo("exeris-kernel-shutdown");
        assertThat(registry.unregistered()).containsExactly(hook);
    }

    // ── 13 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("booting and returning repeatedly leaves no kernel hook registered")
    void repeatedBootsLeaveNoHookRegistered() throws Exception {
        RecordingShutdownHookRegistry registry = new RecordingShutdownHookRegistry();
        KernelBootstrap bootstrap = bootstrap(registry);

        for (int i = 0; i < 50; i++) {
            provide(new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null));
            bootstrap.boot(() -> { });
        }

        assertThat(registry.registered()).hasSize(50);
        assertThat(registry.unregistered()).hasSize(50);
        List<Thread> leftover = new ArrayList<>(registry.registered());
        leftover.removeAll(registry.unregistered());
        assertThat(leftover).isEmpty();
    }

    // ── 14 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registerShutdownHook(false) registers nothing")
    void disabledHookRegistersNothing() throws Exception {
        RecordingShutdownHookRegistry registry = new RecordingShutdownHookRegistry();
        provide(new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null));

        bootstrap(registry, false).boot(() -> { });

        assertThat(registry.registered()).isEmpty();
        assertThat(registry.unregistered()).isEmpty();
    }

    // ── 15 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the hook's body runs the ordered stop")
    void theHooksBodyRunsTheOrderedStop() {
        List<String> stopLog = new CopyOnWriteArrayList<>();
        ShutdownProbe a = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, stopLog);
        ShutdownProbe b = new ShutdownProbe("b", BootstrapPhase.SERVICES, stopLog, "a");
        provide(a, b);
        RecordingShutdownHookRegistry registry = new RecordingShutdownHookRegistry();
        KernelBootstrap bootstrap = bootstrap(registry);
        CountDownLatch mainEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> {
            mainEntered.countDown();
            awaitUninterruptibly(release);
        }).start();
        await(mainEntered, "kernelMain entered");

        Thread hook = registry.registered().get(0);
        hook.start();
        join(hook);

        assertThat(hook.isAlive()).isFalse();
        assertThat(stopLog).containsExactly("b", "a");
        assertThat(a.stopCount()).isEqualTo(1);
        assertThat(b.stopCount()).isEqualTo(1);
        release.countDown();
        run.awaitReturned();
    }

    // ── 16 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unregistering a hook that is not registered does not throw")
    void unregisteringAnUnregisteredHookDoesNotThrow() {
        assertThatCode(() -> RuntimeShutdownHookRegistry.INSTANCE.unregister(new Thread(() -> { })))
                .doesNotThrowAnyException();
    }

    // ── 17 ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a second concurrent boot() of the same instance is refused")
    void aSecondConcurrentBootIsRefused() {
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null);
        provide(probe);
        KernelBootstrap bootstrap = bootstrap(new RecordingShutdownHookRegistry());
        CountDownLatch mainEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BootRun run = new BootRun(bootstrap, "shutdown-test-boot", () -> {
            mainEntered.countDown();
            awaitUninterruptibly(release);
        }).start();
        await(mainEntered, "kernelMain entered");

        assertThatThrownBy(() -> bootstrap.boot(() -> { })).isInstanceOf(IllegalStateException.class);

        assertThat(run.thread.isAlive()).isTrue();
        assertThat(probe.stopCount()).isZero();
        release.countDown();
        run.awaitReturned();
        assertThat(probe.stopCount()).isEqualTo(1);
    }
}
