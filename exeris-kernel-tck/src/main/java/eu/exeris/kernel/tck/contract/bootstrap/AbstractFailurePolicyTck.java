/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: verifies bootstrap failure-policy behaviour — a missing dependency fails the boot under either
 * policy, {@code DEGRADE} tolerates an optional subsystem's failure but not a mandatory one, and
 * {@code FAIL_FAST} aborts the boot on any failure.
 *
 * <p>The TCK supplies the subsystems and judges the boot by what they observed: how often each was
 * initialized, started and stopped, and whether the application body ran. The binding only boots the
 * kernel over the subsystems it is handed, through {@link #bootCapturing(List, boolean, Runnable)}.
 *
 * @since 0.5
 */
public abstract class AbstractFailurePolicyTck {

    /**
     * Creates the contract; subclasses boot the kernel through
     * {@link #bootCapturing(List, boolean, Runnable)}.
     *
     * @since 0.5
     */
    public AbstractFailurePolicyTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Boots a kernel over exactly {@code subsystems} with the given failure policy, running
     * {@code kernelMain} if the boot completes, and returns once the boot has returned or thrown.
     *
     * @param subsystems the subsystems the kernel discovers for this boot, and no others
     * @param degrade    {@code true} for {@code DEGRADE}, {@code false} for {@code FAIL_FAST}
     * @param kernelMain the application body to run inside the booted kernel
     * @return what the boot threw, or {@code null} if it returned normally
     * @throws Exception if the harness itself fails
     */
    protected abstract Throwable bootCapturing(List<Subsystem> subsystems, boolean degrade,
                                               Runnable kernelMain) throws Exception;

    @ParameterizedTest(name = "degrade={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("missing dependency fails the boot before any initialize(), under either policy")
    void missingDependencyFailsFast(boolean degrade) throws Exception {
        ProbeSubsystem memory = new ProbeSubsystem("memory", BootstrapPhase.FOUNDATION, false, false, List.of());
        ProbeSubsystem transport = new ProbeSubsystem(
                "transport", BootstrapPhase.SERVICES, false, false, List.of("memory", "ghost"));
        AtomicBoolean mainRan = new AtomicBoolean();

        Throwable thrown = bootCapturing(List.of(memory, transport), degrade, () -> mainRan.set(true));

        assertThat(thrown).as("boot outcome").isNotNull();
        assertThat(causeChainMessages(thrown)).as("a cause names the missing dependency").anyMatch(m -> m.contains("ghost"));
        assertThat(mainRan).as("kernelMain ran").isFalse();
        assertThat(memory.initializeCount()).as("memory initialize() calls").isZero();
        assertThat(transport.initializeCount()).as("transport initialize() calls").isZero();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("DEGRADE: an optional subsystem's start failure is skipped and kernelMain runs")
    void degradeToleratesOptionalStartFailure() throws Exception {
        ProbeSubsystem memory = new ProbeSubsystem("memory", BootstrapPhase.FOUNDATION, false, false, List.of());
        ProbeSubsystem events = new ProbeSubsystem("events", BootstrapPhase.SERVICES, true, true, List.of("memory"));
        AtomicBoolean mainRan = new AtomicBoolean();

        Throwable thrown = bootCapturing(List.of(memory, events), true, () -> mainRan.set(true));

        assertThat(thrown).as("boot outcome").isNull();
        assertThat(mainRan).as("kernelMain ran").isTrue();
        assertThat(memory.startCount()).as("memory start() calls").isEqualTo(1);
        assertThat(events.startCount()).as("events start() calls").isEqualTo(1);
        assertThat(memory.stopCount()).as("memory stop() calls").isEqualTo(1);
        assertThat(events.stopCount()).as("events stop() calls: it never ran").isZero();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("DEGRADE: a mandatory subsystem's start failure aborts the boot")
    void degradeStillAbortsOnMandatoryFailure() throws Exception {
        ProbeSubsystem memory = new ProbeSubsystem("memory", BootstrapPhase.FOUNDATION, false, false, List.of());
        ProbeSubsystem events = new ProbeSubsystem("events", BootstrapPhase.SERVICES, false, true, List.of("memory"));
        AtomicBoolean mainRan = new AtomicBoolean();

        Throwable thrown = bootCapturing(List.of(memory, events), true, () -> mainRan.set(true));

        assertThat(thrown).as("boot outcome").isNotNull();
        assertThat(mainRan).as("kernelMain ran").isFalse();
        assertThat(memory.stopCount()).as("memory stop() calls").isEqualTo(1);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("DEGRADE: a FOUNDATION subsystem is mandatory even when it declares itself optional")
    void degradeTreatsFoundationAsMandatory() throws Exception {
        ProbeSubsystem memory = new ProbeSubsystem("memory", BootstrapPhase.FOUNDATION, true, true, List.of());
        AtomicBoolean mainRan = new AtomicBoolean();

        Throwable thrown = bootCapturing(List.of(memory), true, () -> mainRan.set(true));

        assertThat(thrown).as("boot outcome").isNotNull();
        assertThat(mainRan).as("kernelMain ran").isFalse();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("FAIL_FAST: an optional subsystem's start failure aborts the boot")
    void failFastAbortsOnOptionalFailure() throws Exception {
        ProbeSubsystem memory = new ProbeSubsystem("memory", BootstrapPhase.FOUNDATION, false, false, List.of());
        ProbeSubsystem events = new ProbeSubsystem("events", BootstrapPhase.SERVICES, true, true, List.of("memory"));
        AtomicBoolean mainRan = new AtomicBoolean();

        Throwable thrown = bootCapturing(List.of(memory, events), false, () -> mainRan.set(true));

        assertThat(thrown).as("boot outcome").isNotNull();
        assertThat(mainRan).as("kernelMain ran").isFalse();
        assertThat(memory.stopCount()).as("memory stop() calls: started subsystems are stopped on abort").isEqualTo(1);
    }

    private static List<String> causeChainMessages(Throwable thrown) {
        List<String> messages = new java.util.ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
            if (t.getCause() == t) {  // NOPMD CompareObjectsWithEquals - self-referential cause guard
                break;
            }
        }
        return messages;
    }

    /**
     * A subsystem that counts its lifecycle calls and optionally fails in {@code start()}.
     */
    static final class ProbeSubsystem implements Subsystem {

        private final String name;
        private final BootstrapPhase phase;
        private final boolean optional;
        private final boolean failOnStart;
        private final List<String> dependsOn;
        private final AtomicInteger initializeCount = new AtomicInteger();
        private final AtomicInteger startCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();
        private volatile boolean running;

        /**
         * Creates a probe.
         *
         * @param name        the subsystem name
         * @param phase       the bootstrap phase it belongs to
         * @param optional    whether it declares itself optional
         * @param failOnStart whether {@code start()} throws a {@link SubsystemException}
         * @param dependsOn   the names of the subsystems it depends on
         */
        ProbeSubsystem(String name, BootstrapPhase phase, boolean optional,
                              boolean failOnStart, List<String> dependsOn) {
            this.name = name;
            this.phase = phase;
            this.optional = optional;
            this.failOnStart = failOnStart;
            this.dependsOn = List.copyOf(dependsOn);
        }

        @Override public String name() { return name; }
        @Override public BootstrapPhase phase() { return phase; }
        @Override public List<String> dependsOn() { return dependsOn; }
        @Override public boolean isOptional() { return optional; }

        @Override
        public void initialize() {
            initializeCount.incrementAndGet();
        }

        @Override
        public void start() {
            startCount.incrementAndGet();
            if (failOnStart) {
                throw new SubsystemException(name, SubsystemException.Phase.START, "probe start failure");
            }
            running = true;
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
            running = false;
        }

        @Override public boolean isRunning() { return running; }

        /**
         * Returns how many times {@code initialize()} ran.
         *
         * @return the call count
         */
        int initializeCount() { return initializeCount.get(); }

        /**
         * Returns how many times {@code start()} ran.
         *
         * @return the call count
         */
        int startCount() { return startCount.get(); }

        /**
         * Returns how many times {@code stop()} ran.
         *
         * @return the call count
         */
        int stopCount() { return stopCount.get(); }
    }
}
