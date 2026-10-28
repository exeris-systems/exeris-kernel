/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemInitDeadlineException;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Behaviour of the initialization deadline on {@link SubsystemOrchestrator#initialize(ConfigProvider)}:
 * a {@code initialize()} that runs past the deadline fails the boot with
 * {@link SubsystemInitDeadlineException} (EX-BOOT-0003), whether it then returns, throws, or ignores the
 * interrupt the orchestrator delivered.
 *
 * <p>Every case is bounded by {@link Timeout}, and every blocking subsystem is bounded by its own wait, so a
 * regression fails as an assertion rather than as a hung build.
 */
@DisplayName("Core: subsystem initialization deadline (EX-BOOT-0003)")
class SubsystemOrchestratorInitDeadlineTest {

    /** Name prefix of the platform watchdog threads the orchestrator arms, one per subsystem. */
    private static final String WATCHDOG_PREFIX = "exeris-boot-init-deadline-";

    /** Upper bound on any wait a test subsystem performs, so a broken deadline cannot hang the build. */
    private static final Duration SUBSYSTEM_WAIT_BOUND = Duration.ofSeconds(5);

    /**
     * In-process provider that hands the orchestrator a fixed subsystem list through ServiceLoader.
     */
    public static final class DeadlineSubsystemProvider implements SubsystemProvider {
        static volatile List<Subsystem> subsystems = List.of();

        /**
         * Public no-arg constructor, required by ServiceLoader.
         */
        public DeadlineSubsystemProvider() {
            super();
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return subsystems;
        }
    }

    // =========================================================================
    // 1. Blocked initialize() fails the boot with the deadline code
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("1. initialize() blocked past a 200 ms deadline: BootstrapException, cause EX-BOOT-0003, rawArgs [name, 200L], FAILED")
    void blockedInitializeFailsBootWithDeadlineCode() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean();
        Subsystem probe = subsystem("probe", false, BootstrapPhase.SERVICES,
                () -> sawInterrupt.set(awaitInterruptibly(release)));
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                Duration.ofMillis(200), List.of(probe));
        try {
            long startNanos = System.nanoTime();
            Throwable thrown = catchThrowable(() -> orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider()));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            assertThat(sawInterrupt).isTrue();
            assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();

            SubsystemInitDeadlineException ex = deadlineFailure(thrown);
            assertThat(ex.errorCode()).isEqualTo("EX-BOOT-0003");
            assertThat(ex.rawArgs()).containsExactly("probe", 200L);
            assertThat(orchestrator.healthMonitor().stateOf("probe"))
                    .isEqualTo(KernelHealthMonitor.SubsystemState.FAILED);
        } finally {
            release.countDown();
        }
    }

    // =========================================================================
    // 2. Init under the deadline: success, clear interrupt flag, no watchdog left
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("2. initialize() under the deadline succeeds, leaves the interrupt flag clear and no watchdog thread")
    void initializeUnderDeadlineSucceedsCleanly() throws Exception {
        Subsystem quick = subsystem("quick", false, BootstrapPhase.SERVICES, () -> sleep(Duration.ofMillis(20)));
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                Duration.ofSeconds(5), List.of(quick));

        orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());

        assertThat(orchestrator.healthMonitor().stateOf("quick"))
                .isEqualTo(KernelHealthMonitor.SubsystemState.INITIALIZED);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(watchdogThreadNames()).isEmpty();
    }

    // =========================================================================
    // 3. Init that ignores interrupts and returns after the deadline still fails
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("3. initialize() ignoring interrupts and returning at deadline+50 ms still fails with EX-BOOT-0003; flag cleared")
    void interruptIgnoringInitializeStillFailsAndFlagIsCleared() {
        Subsystem stubborn = subsystem("stubborn", false, BootstrapPhase.SERVICES,
                () -> busyWaitIgnoringInterrupts(Duration.ofMillis(250)));
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                Duration.ofMillis(200), List.of(stubborn));

        Throwable thrown = catchThrowable(() -> orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider()));

        SubsystemInitDeadlineException ex = deadlineFailure(thrown);
        assertThat(ex.getCause()).isNull();
        assertThat(ex.rawArgs()).containsExactly("stubborn", 200L);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    // =========================================================================
    // 4. The JFR event is observed while initialize() is still blocked
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("4. SubsystemInitDeadlineExceeded is recorded while initialize() is still blocked")
    void deadlineEventIsRecordedWhileInitializeIsBlocked() throws Exception {
        String eventName = "eu.exeris.kernel.bootstrap.SubsystemInitDeadlineExceeded";
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch eventSeen = new CountDownLatch(1);
        AtomicReference<RecordedEvent> recorded = new AtomicReference<>();
        // Ignores interruption, so "still blocked" is a guarantee and not a race against the interrupt.
        Subsystem blocked = subsystem("blocked", false, BootstrapPhase.SERVICES,
                () -> awaitIgnoringInterrupts(release));
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                Duration.ofMillis(200), List.of(blocked));

        ExecutorService booting = Executors.newSingleThreadExecutor();
        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(eventName);
            stream.onEvent(eventName, event -> {
                if ("blocked".equals(event.getString("subsystemName"))) {
                    recorded.compareAndSet(null, event);
                    eventSeen.countDown();
                }
            });
            stream.startAsync();

            Future<?> boot = booting.submit(() -> {
                orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());
                return null;
            });
            try {
                assertThat(eventSeen.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(boot.isDone()).isFalse();
                assertThat(recorded.get().getLong("deadlineMs")).isEqualTo(200L);
                assertThat(recorded.get().getString("errorCode")).isEqualTo("EX-BOOT-0003");
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> boot.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubsystemOrchestrator.BootstrapException.class);
        } finally {
            booting.shutdownNow();
        }
    }

    // =========================================================================
    // 5. DEGRADE drops an optional hung subsystem and its dependents; FAIL_FAST aborts
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("5. optional hung subsystem: DEGRADE drops it and its dependents and boots; FAIL_FAST aborts")
    void optionalHungSubsystemFollowsFailurePolicy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Subsystem base = subsystem("base", false, BootstrapPhase.FOUNDATION, () -> { });
        Subsystem hung = subsystem("hung", true, BootstrapPhase.SERVICES, () -> awaitInterruptibly(release), "base");
        Subsystem dependent = subsystem("dependent", false, BootstrapPhase.SERVICES, () -> { }, "hung");
        List<Subsystem> all = List.of(base, hung, dependent);

        try {
            SubsystemOrchestrator degrade = orchestrator(SubsystemOrchestrator.FailurePolicy.DEGRADE,
                    Duration.ofMillis(200), all);
            degrade.initialize(new KernelBootstrapTest.StubConfigProvider());
            degrade.start(new KernelBootstrapTest.StubConfigProvider());

            assertThat(degrade.isStarted()).isTrue();
            assertThat(degrade.subsystem("hung")).isEmpty();
            assertThat(degrade.subsystem("dependent")).isEmpty();
            assertThat(degrade.subsystem("base")).isPresent();
            assertThat(degrade.healthMonitor().stateOf("hung"))
                    .isEqualTo(KernelHealthMonitor.SubsystemState.FAILED);
            degrade.shutdown();
        } finally {
            release.countDown();
        }

        CountDownLatch releaseAgain = new CountDownLatch(1);
        Subsystem hungAgain = subsystem("hung", true, BootstrapPhase.SERVICES,
                () -> awaitInterruptibly(releaseAgain), "base");
        try {
            SubsystemOrchestrator failFast = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                    Duration.ofMillis(200), List.of(base, hungAgain));
            Throwable thrown = catchThrowable(() -> failFast.initialize(new KernelBootstrapTest.StubConfigProvider()));
            deadlineFailure(thrown);
        } finally {
            releaseAgain.countDown();
        }
    }

    // =========================================================================
    // 6. Deadline disabled: no watchdog armed, a 300 ms init succeeds
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("6. Duration.ZERO arms no watchdog thread, and a 300 ms initialize() succeeds")
    void disabledDeadlineArmsNoWatchdog() throws Exception {
        AtomicBoolean watchdogSeenDuringInit = new AtomicBoolean(true);
        Subsystem slow = subsystem("slow", false, BootstrapPhase.SERVICES, () -> {
            sleep(Duration.ofMillis(150));
            watchdogSeenDuringInit.set(!watchdogThreadNames().isEmpty());
            sleep(Duration.ofMillis(150));
        });
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                Duration.ZERO, List.of(slow));

        orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());

        assertThat(orchestrator.healthMonitor().stateOf("slow"))
                .isEqualTo(KernelHealthMonitor.SubsystemState.INITIALIZED);
        assertThat(watchdogSeenDuringInit).isFalse();
        assertThat(watchdogThreadNames()).isEmpty();
    }

    // =========================================================================
    // 7. An interrupt the subsystem set on itself is left untouched when the watchdog did not fire
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("7. no deadline fired: an interrupt the subsystem set on its own thread is left set")
    void selfSetInterruptIsLeftUntouchedWhenDeadlineDidNotFire() throws Exception {
        Subsystem selfInterrupting = subsystem("self", false, BootstrapPhase.SERVICES,
                () -> Thread.currentThread().interrupt());
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                Duration.ofSeconds(5), List.of(selfInterrupting));
        try {
            orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());

            assertThat(orchestrator.healthMonitor().stateOf("self"))
                    .isEqualTo(KernelHealthMonitor.SubsystemState.INITIALIZED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // =========================================================================
    // 8. The watchdog's interrupt never reaches the next subsystem (window b: flag restored)
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("8. DEGRADE: an optional hung subsystem that restores the interrupt flag does not leak it to the next subsystem")
    void restoredInterruptDoesNotLeakIntoNextSubsystem() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean nextSawInterrupt = new AtomicBoolean(true);
        Subsystem hung = subsystem("hung", true, BootstrapPhase.SERVICES, () -> awaitInterruptibly(release));
        Subsystem next = subsystem("next", false, BootstrapPhase.RUNTIME,
                () -> nextSawInterrupt.set(Thread.currentThread().isInterrupted()));
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.DEGRADE,
                Duration.ofMillis(200), List.of(hung, next));
        try {
            orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());

            assertThat(orchestrator.healthMonitor().stateOf("hung"))
                    .isEqualTo(KernelHealthMonitor.SubsystemState.FAILED);
            assertThat(nextSawInterrupt).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            release.countDown();
            Thread.interrupted();
        }
    }

    // =========================================================================
    // 9. The same, with the interrupt delivered while the booting thread is joining the watchdog
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("9. DEGRADE: initialize() returning while the watchdog is stalled before its interrupt leaves no interrupt behind")
    void interruptDeliveredDuringJoinDoesNotLeakIntoNextSubsystem() throws Exception {
        CountDownLatch watchdogStalled = new CountDownLatch(1);
        AtomicBoolean nextSawInterrupt = new AtomicBoolean(true);
        Subsystem hung = subsystem("hung", true, BootstrapPhase.SERVICES, () -> {
            // Returns normally, without any interrupt, once the watchdog has fired and is held back.
            awaitIgnoringInterrupts(watchdogStalled);
        });
        Subsystem next = subsystem("next", false, BootstrapPhase.RUNTIME,
                () -> nextSawInterrupt.set(Thread.currentThread().isInterrupted()));
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.DEGRADE,
                Duration.ofMillis(200), List.of(hung, next));
        // The interrupt is delivered only once the booting thread waits inside Thread#join, and the
        // watchdog stays alive until the booting thread has taken it, so the join always ends by interrupt.
        InitDeadlineWatch.deliverInterruptWith(booting -> {
            watchdogStalled.countDown();
            long end = System.nanoTime() + SUBSYSTEM_WAIT_BOUND.toNanos();
            while (!isJoiningWatchdog(booting) && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            booting.interrupt();
            while (booting.getState() == Thread.State.WAITING && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
        });
        try {
            orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());

            assertThat(orchestrator.healthMonitor().stateOf("hung"))
                    .isEqualTo(KernelHealthMonitor.SubsystemState.FAILED);
            assertThat(nextSawInterrupt).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            InitDeadlineWatch.deliverInterruptWith(Thread::interrupt);
            Thread.interrupted();
        }
    }

    // =========================================================================
    // 10. A checked exception thrown sneakily still disarms the watchdog
    // =========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("10. initialize() sneaky-throwing a checked exception leaves no watchdog and no later interrupt")
    void sneakyCheckedExceptionDisarmsWatchdog() {
        IOException failure = new IOException("sneaky");
        Subsystem throwing = subsystem("throwing", false, BootstrapPhase.SERVICES,
                () -> SubsystemOrchestratorInitDeadlineTest.<RuntimeException>sneakyThrow(failure));
        Duration deadline = Duration.ofMillis(300);
        SubsystemOrchestrator orchestrator = orchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST,
                deadline, List.of(throwing));

        Throwable thrown = catchThrowable(() -> orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider()));

        assertThat(thrown).isSameAs(failure);
        assertThat(watchdogThreadNames()).isEmpty();
        // An armed watchdog would fire at the deadline and interrupt this thread.
        sleep(deadline.plusMillis(300));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    // =========================================================================
    // 11. Deadlines the event and the exception cannot report are refused
    // =========================================================================

    @Test
    @DisplayName("11. the builder refuses a negative deadline and a positive one under 1 ms; zero and 1 ms are accepted")
    void builderRefusesUnreportableDeadlines() {
        Duration negative = Duration.ofMillis(-1);
        Duration subMillisecond = Duration.ofNanos(999_999);
        Duration oneNanosecond = Duration.ofNanos(1);
        SubsystemOrchestrator.Builder orchestratorBuilder = SubsystemOrchestrator.builder();
        KernelBootstrap.Builder kernelBuilder = KernelBootstrap.builder();
        assertThatThrownBy(() -> orchestratorBuilder.initializeDeadline(negative))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestratorBuilder.initializeDeadline(subMillisecond))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kernelBuilder.initializeDeadline(oneNanosecond))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SubsystemOrchestrator.builder().initializeDeadline(Duration.ZERO)).isNotNull();
        assertThat(SubsystemOrchestrator.builder().initializeDeadline(Duration.ofMillis(1))).isNotNull();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static SubsystemOrchestrator orchestrator(SubsystemOrchestrator.FailurePolicy policy,
                                                      Duration deadline,
                                                      List<Subsystem> subsystems) {
        DeadlineSubsystemProvider.subsystems = subsystems;
        ClassLoader shim = new KernelBootstrapTest.DualServiceShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        KernelBootstrapTest.StubConfigProvider.class.getName(),
                        "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                        DeadlineSubsystemProvider.class.getName()));
        return SubsystemOrchestrator.builder()
                .failurePolicy(policy)
                .selector(BootstrapSelector.all())
                .classLoader(shim)
                .initializeDeadline(deadline)
                .build();
    }

    /**
     * Asserts that {@code thrown} is the boot failure the orchestrator raises for a missed deadline, and
     * returns its cause.
     *
     * @param thrown the throwable from {@code initialize()}
     * @return the {@link SubsystemInitDeadlineException} carried as the boot failure's cause
     */
    private static SubsystemInitDeadlineException deadlineFailure(Throwable thrown) {
        assertThat(thrown).isInstanceOf(SubsystemOrchestrator.BootstrapException.class);
        assertThat(thrown.getCause()).isInstanceOf(SubsystemInitDeadlineException.class);
        return (SubsystemInitDeadlineException) thrown.getCause();
    }

    /**
     * A subsystem with a scripted {@code initialize()} and no {@code start()} work.
     *
     * @param name       subsystem name
     * @param optional   whether the subsystem is optional
     * @param phase      bootstrap phase
     * @param init       the scripted {@code initialize()} body
     * @param dependsOn  names this subsystem depends on
     * @return the subsystem
     */
    private static Subsystem subsystem(String name, boolean optional, BootstrapPhase phase, Runnable init,
                                       String... dependsOn) {
        return new Subsystem() {
            private volatile boolean running;

            @Override public String name() { return name; }
            @Override public List<String> dependsOn() { return List.of(dependsOn); }
            @Override public BootstrapPhase phase() { return phase; }
            @Override public boolean isOptional() { return optional; }
            @Override public void initialize() { init.run(); }
            @Override public void start() { running = true; }
            @Override public void stop() { running = false; }
            @Override public boolean isRunning() { return running; }
        };
    }

    /**
     * Waits for {@code latch} and, on interruption, restores the flag and returns.
     *
     * @return {@code true} if the wait ended by interruption
     */
    private static boolean awaitInterruptibly(CountDownLatch latch) {
        try {
            latch.await(SUBSYSTEM_WAIT_BOUND.toMillis(), TimeUnit.MILLISECONDS);
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    /**
     * Waits for {@code latch} and keeps waiting through interrupts, the way an initialize() does when it
     * cannot honour one. The wait is bounded by {@link #SUBSYSTEM_WAIT_BOUND}; the interrupt flag is restored
     * on return so the orchestrator sees it.
     */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        long end = System.nanoTime() + SUBSYSTEM_WAIT_BOUND.toNanos();
        try {
            while (latch.getCount() > 0) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    latch.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean isJoiningWatchdog(Thread booting) {
        return booting.getState() == Thread.State.WAITING
                && Arrays.stream(booting.getStackTrace()).anyMatch(
                        f -> f.getClassName().equals(Thread.class.getName()) && f.getMethodName().equals("join"));
    }

    private static void busyWaitIgnoringInterrupts(Duration duration) {
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    private static void sleep(Duration duration) {
        long end = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = end - System.nanoTime()) > 0) {
            // parkNanos leaves the interrupt flag as it finds it, which is what the caller asserts on.
            LockSupport.parkNanos(remaining);
        }
    }

    private static List<String> watchdogThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(n -> n.startsWith(WATCHDOG_PREFIX))
                .toList();
    }
}
