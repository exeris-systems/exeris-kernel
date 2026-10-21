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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SubsystemOrchestrator — concurrent and re-entrant shutdown")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class SubsystemOrchestratorShutdownTest {

    private static final long JOIN_MILLIS = 15_000;
    private static final long NEGATIVE_WAIT_MILLIS = 300;

    /** Discovers the subsystems a test set for the current orchestrator. */
    public static final class ShutdownProbeProvider implements SubsystemProvider {

        static volatile List<Subsystem> subsystems = List.of();

        /** Required public no-arg constructor for {@link java.util.ServiceLoader}. */
        public ShutdownProbeProvider() {
            // ServiceLoader-mandated no-op constructor.
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return subsystems;
        }
    }

    private static SubsystemOrchestrator orchestrator(Subsystem... subsystems) {
        ShutdownProbeProvider.subsystems = List.of(subsystems);
        return SubsystemOrchestrator.builder()
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                .selector(BootstrapSelector.all())
                .classLoader(new SubsystemOrchestratorKahnTest.SubsystemProviderShimLoader(
                        Thread.currentThread().getContextClassLoader(),
                        ShutdownProbeProvider.class.getName()))
                .build();
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

    private static Thread run(String name, Runnable body) {
        Thread thread = Thread.ofPlatform().daemon(true).name(name).unstarted(body);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(JOIN_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining " + thread.getName(), ex);
        }
        assertThat(thread.isAlive()).as("%s returned", thread.getName()).isFalse();
    }

    @Test
    @DisplayName("a caller that loses the race waits until the winner's stop has returned")
    void aLosingCallerWaitsForTheWinner() throws Exception {
        CountDownLatch inStop = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null).onStop(() -> {
            inStop.countDown();
            awaitUninterruptibly(releaseStop);
        });
        SubsystemOrchestrator orchestrator = orchestrator(probe);
        ConfigProvider config = new KernelBootstrapTest.StubConfigProvider();
        orchestrator.initialize(config);
        orchestrator.start(config);

        Thread winner = run("shutdown-winner", orchestrator::shutdown);
        assertThat(inStop.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
        Thread loser = run("shutdown-loser", orchestrator::shutdown);

        loser.join(NEGATIVE_WAIT_MILLIS);
        assertThat(loser.isAlive()).as("the loser is still waiting").isTrue();
        assertThat(probe.stopCount()).isEqualTo(1);

        releaseStop.countDown();
        join(winner);
        join(loser);
        assertThat(probe.stopCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("shutdown() called from a stop() returns without deadlock")
    void reEntryFromAStopReturns() throws Exception {
        AtomicReference<SubsystemOrchestrator> self = new AtomicReference<>();
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null)
                .onStop(() -> self.get().shutdown());
        SubsystemOrchestrator orchestrator = orchestrator(probe);
        self.set(orchestrator);
        ConfigProvider config = new KernelBootstrapTest.StubConfigProvider();
        orchestrator.initialize(config);
        orchestrator.start(config);

        join(run("shutdown-caller", orchestrator::shutdown));

        assertThat(probe.stopCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("shutdown() on an orchestrator that was never initialized leaves it reusable")
    void shutdownBeforeInitializeLeavesTheOrchestratorReusable() throws Exception {
        ShutdownProbe probe = new ShutdownProbe("a", BootstrapPhase.FOUNDATION, null);
        SubsystemOrchestrator orchestrator = orchestrator(probe);
        ConfigProvider config = new KernelBootstrapTest.StubConfigProvider();

        orchestrator.shutdown();
        orchestrator.initialize(config);
        orchestrator.start(config);

        assertThat(orchestrator.isInitialized()).isTrue();
        assertThat(probe.startCount()).isEqualTo(1);
        orchestrator.shutdown();
        assertThat(probe.stopCount()).isEqualTo(1);
    }
}
