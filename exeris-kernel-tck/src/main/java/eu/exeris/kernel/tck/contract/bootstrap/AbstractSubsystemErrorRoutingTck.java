/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemInitDeadlineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK contract verifying that errors thrown by kernel subsystems during
 * {@link Subsystem#initialize()} and {@link Subsystem#start()} are routed
 * through the active failure policy, while fatal {@link VirtualMachineError}
 * instances propagate untouched.
 *
 * @since 0.13
 */
public abstract class AbstractSubsystemErrorRoutingTck {

    /**
     * Creates a new contract test suite for subsystem error routing.
     */
    public AbstractSubsystemErrorRoutingTck() {
        super();
    }

    /**
     * Target lifecycle phase during which the probe subsystem throws its error.
     */
    public enum ThrowPhase {
        /**
         * Error is thrown from {@link Subsystem#initialize()}.
         */
        INITIALIZE,

        /**
         * Error is thrown from {@link Subsystem#start()}.
         */
        START
    }

    /**
     * Boots a kernel instance with the given subsystems and failure policy, executing
     * {@code kernelMain} if the boot completes successfully.
     *
     * @param subsystems the subsystems to register for this boot scenario
     * @param degrade    {@code true} for {@code DEGRADE} failure policy; {@code false} for {@code FAIL_FAST}
     * @param kernelMain the application payload to execute inside the booted kernel scope
     * @return the {@link Throwable} thrown by boot, or {@code null} if boot completed successfully
     * @throws Exception if an unhandled harness failure occurs
     */
    protected abstract Throwable bootCapturing(List<Subsystem> subsystems, boolean degrade, Runnable kernelMain) throws Exception;

    /**
     * Boots a kernel whose initialization deadline is {@code deadline}, executing {@code kernelMain} if the
     * boot completes successfully. Only {@code initialize()} is bounded by the deadline.
     *
     * @param subsystems the subsystems to register for this boot scenario
     * @param degrade    {@code true} for {@code DEGRADE} failure policy; {@code false} for {@code FAIL_FAST}
     * @param deadline   the initialization deadline the kernel is built with
     * @param kernelMain the application payload to execute inside the booted kernel scope
     * @return the {@link Throwable} thrown by boot, or {@code null} if boot completed successfully
     * @throws Exception if an unhandled harness failure occurs
     */
    protected abstract Throwable bootCapturingWithInitDeadline(List<Subsystem> subsystems, boolean degrade,
                                                               Duration deadline, Runnable kernelMain) throws Exception;

    /**
     * Deadline used by the initialization-deadline cases. Short enough that the hanging probe always passes it.
     */
    static final Duration INIT_DEADLINE = Duration.ofMillis(200);

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("FAIL_FAST + optional: initialize() past the deadline aborts boot with EX-BOOT-0003")
    void failFastInitDeadlineAbortsBoot() throws Exception {
        BaseSubsystem base = new BaseSubsystem();
        HangingProbeSubsystem probe = new HangingProbeSubsystem(true);
        AtomicBoolean kernelMainRan = new AtomicBoolean(false);

        long startNanos = System.nanoTime();
        Throwable thrown = bootCapturingWithInitDeadline(List.of(base, probe), false, INIT_DEADLINE,
                () -> kernelMainRan.set(true));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(probe.sawInterrupt()).as("the deadline interrupts the hung initialize()").isTrue();
        assertThat(elapsed).as("the interrupt arrives at the deadline, not after the probe gives up")
                .isLessThan(Duration.ofSeconds(3));
        SubsystemInitDeadlineException deadline = causeInChain(thrown, SubsystemInitDeadlineException.class);
        assertThat(deadline).isNotNull();
        assertThat(deadline.errorCode()).isEqualTo("EX-BOOT-0003");
        assertThat(deadline.rawArgs()).containsExactly("probe", INIT_DEADLINE.toMillis());
        assertThat(kernelMainRan).isFalse();
        assertThat(base.stopCount()).isEqualTo(1);
        assertThat(probe.stopCount()).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("DEGRADE + optional: initialize() past the deadline skips the subsystem and boots kernelMain")
    void degradeOptionalInitDeadlineSkipsSubsystem() throws Exception {
        BaseSubsystem base = new BaseSubsystem();
        HangingProbeSubsystem probe = new HangingProbeSubsystem(true);
        AtomicBoolean kernelMainRan = new AtomicBoolean(false);

        AtomicBoolean kernelMainSawInterrupt = new AtomicBoolean(true);
        long startNanos = System.nanoTime();
        Throwable thrown = bootCapturingWithInitDeadline(List.of(base, probe), true, INIT_DEADLINE, () -> {
            kernelMainRan.set(true);
            kernelMainSawInterrupt.set(Thread.currentThread().isInterrupted());
        });
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(thrown).isNull();
        assertThat(kernelMainRan).isTrue();
        assertThat(probe.sawInterrupt()).as("the deadline interrupts the hung initialize()").isTrue();
        assertThat(elapsed).as("the interrupt arrives at the deadline, not after the probe gives up")
                .isLessThan(Duration.ofSeconds(3));
        assertThat(kernelMainSawInterrupt).as("the deadline's interrupt never reaches application code").isFalse();
        assertThat(base.startCount()).isEqualTo(1);
        assertThat(probe.stopCount()).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("errorAndPhaseCases")
    @DisplayName("DEGRADE + optional skips subsystem error and boots kernelMain")
    void degradeOptionalSubsystemErrorSkipped(Supplier<Error> errorSupplier, ThrowPhase phase) throws Exception {
        Error error = errorSupplier.get();
        BaseSubsystem base = new BaseSubsystem();
        ProbeSubsystem probe = new ProbeSubsystem(true, phase, error);
        AtomicBoolean kernelMainRan = new AtomicBoolean(false);

        Throwable thrown = bootCapturing(List.of(base, probe), true, () -> kernelMainRan.set(true));

        assertThat(thrown).isNull();
        assertThat(kernelMainRan).isTrue();
        assertThat(base.startCount()).isEqualTo(1);
        assertThat(base.stopCount()).isEqualTo(1);
        assertThat(probe.stopCount()).isZero();
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("errorAndPhaseCases")
    @DisplayName("FAIL_FAST + optional wraps subsystem error into exception and aborts boot")
    void failFastOptionalSubsystemErrorAborts(Supplier<Error> errorSupplier, ThrowPhase phase) throws Exception {
        Error error = errorSupplier.get();
        BaseSubsystem base = new BaseSubsystem();
        ProbeSubsystem probe = new ProbeSubsystem(true, phase, error);
        AtomicBoolean kernelMainRan = new AtomicBoolean(false);

        Throwable thrown = bootCapturing(List.of(base, probe), false, () -> kernelMainRan.set(true));

        assertThat(thrown).isNotNull().isNotInstanceOf(Error.class);
        assertThat(rootCause(thrown)).isSameAs(error);
        assertThat(kernelMainRan).isFalse();
        assertThat(base.stopCount()).isEqualTo(1);
        assertThat(probe.stopCount()).isZero();
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("errorAndPhaseCases")
    @DisplayName("DEGRADE + mandatory wraps subsystem error into exception and aborts boot")
    void degradeMandatorySubsystemErrorAborts(Supplier<Error> errorSupplier, ThrowPhase phase) throws Exception {
        Error error = errorSupplier.get();
        BaseSubsystem base = new BaseSubsystem();
        ProbeSubsystem probe = new ProbeSubsystem(false, phase, error);
        AtomicBoolean kernelMainRan = new AtomicBoolean(false);

        Throwable thrown = bootCapturing(List.of(base, probe), true, () -> kernelMainRan.set(true));

        assertThat(thrown).isNotNull().isNotInstanceOf(Error.class);
        assertThat(rootCause(thrown)).isSameAs(error);
        assertThat(kernelMainRan).isFalse();
        assertThat(base.stopCount()).isEqualTo(1);
        assertThat(probe.stopCount()).isZero();
    }

    @ParameterizedTest(name = "VirtualMachineError in {0}")
    @EnumSource(ThrowPhase.class)
    @DisplayName("VirtualMachineError propagates untouched under DEGRADE + optional")
    void virtualMachineErrorPropagatesUntouched(ThrowPhase phase) throws Exception {
        StackOverflowError vme = new StackOverflowError("tck");
        BaseSubsystem base = new BaseSubsystem();
        ProbeSubsystem probe = new ProbeSubsystem(true, phase, vme);
        AtomicBoolean kernelMainRan = new AtomicBoolean(false);

        Throwable thrown = bootCapturing(List.of(base, probe), true, () -> kernelMainRan.set(true));

        assertThat(thrown).isSameAs(vme);
        assertThat(kernelMainRan).isFalse();
    }

    /**
     * Walks the cause chain of {@code thrown} and returns the first instance of {@code type}, or {@code null}.
     */
    private static <T extends Throwable> T causeInChain(Throwable thrown, Class<T> type) {
        Throwable current = thrown;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    static Stream<Arguments> errorAndPhaseCases() {
        return Stream.of(
                argumentsFor(ThrowPhase.INITIALIZE),
                argumentsFor(ThrowPhase.START)
        ).flatMap(List::stream);
    }

    private static List<Arguments> argumentsFor(ThrowPhase phase) {
        return List.of(
                Arguments.of(
                        Named.of("NoClassDefFoundError", (Supplier<Error>) () -> new NoClassDefFoundError("tck/Missing")),
                        phase),
                Arguments.of(
                        Named.of("ExceptionInInitializerError", (Supplier<Error>) () -> new ExceptionInInitializerError("tck")),
                        phase),
                Arguments.of(
                        Named.of("ServiceConfigurationError", (Supplier<Error>) () -> new ServiceConfigurationError("tck")),
                        phase),
                Arguments.of(
                        Named.of("Error", (Supplier<Error>) () -> new Error("tck-unsupported-feature")),
                        phase)
        );
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Healthy foundation subsystem tracking initialization, startup, and shutdown counters.
     */
    static final class BaseSubsystem implements Subsystem {
        private final AtomicInteger initCount = new AtomicInteger();
        private final AtomicInteger startCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();
        private volatile boolean running;

        @Override
        public String name() {
            return "base";
        }

        @Override
        public List<String> dependsOn() {
            return List.of();
        }

        @Override
        public BootstrapPhase phase() {
            return BootstrapPhase.FOUNDATION;
        }

        @Override
        public boolean isOptional() {
            return false;
        }

        @Override
        public void initialize() {
            initCount.incrementAndGet();
            running = true;
        }

        @Override
        public void start() {
            startCount.incrementAndGet();
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            stopCount.incrementAndGet();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        int initCount() {
            return initCount.get();
        }

        int startCount() {
            return startCount.get();
        }

        int stopCount() {
            return stopCount.get();
        }
    }

    /**
     * Services phase probe subsystem parameterized to throw an error during initialization or startup.
     */
    static final class ProbeSubsystem implements Subsystem {
        private final boolean optional;
        private final ThrowPhase throwPhase;
        private final Throwable errorToThrow;
        private final AtomicInteger stopCount = new AtomicInteger();
        private volatile boolean running;

        ProbeSubsystem(boolean optional, ThrowPhase throwPhase, Throwable errorToThrow) {
            super();
            this.optional = optional;
            this.throwPhase = throwPhase;
            this.errorToThrow = errorToThrow;
        }

        @Override
        public String name() {
            return "probe";
        }

        @Override
        public List<String> dependsOn() {
            return List.of("base");
        }

        @Override
        public BootstrapPhase phase() {
            return BootstrapPhase.SERVICES;
        }

        @Override
        public boolean isOptional() {
            return optional;
        }

        @Override
        public void initialize() {
            if (throwPhase == ThrowPhase.INITIALIZE) {
                sneakyThrow(errorToThrow);
            }
        }

        @Override
        public void start() {
            if (throwPhase == ThrowPhase.START) {
                sneakyThrow(errorToThrow);
            }
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            stopCount.incrementAndGet();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        int stopCount() {
            return stopCount.get();
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
            throw (E) e;
        }
    }
    /**
     * Services-phase probe whose {@code initialize()} blocks until the orchestrator interrupts it, the way a
     * subsystem that waits on an unavailable dependency does. The wait is bounded so a missing deadline fails
     * the case by assertion rather than by hanging the build.
     */
    static final class HangingProbeSubsystem implements Subsystem {
        private final boolean optional;
        private final CountDownLatch never = new CountDownLatch(1);
        private final AtomicInteger stopCount = new AtomicInteger();
        private final AtomicBoolean sawInterrupt = new AtomicBoolean();
        private volatile boolean running;

        HangingProbeSubsystem(boolean optional) {
            super();
            this.optional = optional;
        }

        @Override
        public String name() {
            return "probe";
        }

        @Override
        public List<String> dependsOn() {
            return List.of("base");
        }

        @Override
        public BootstrapPhase phase() {
            return BootstrapPhase.SERVICES;
        }

        @Override
        public boolean isOptional() {
            return optional;
        }

        @Override
        public void initialize() {
            try {
                never.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                sawInterrupt.set(true);
                Thread.currentThread().interrupt();
            }
        }

        boolean sawInterrupt() {
            return sawInterrupt.get();
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            stopCount.incrementAndGet();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        int stopCount() {
            return stopCount.get();
        }
    }
}
