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
import eu.exeris.kernel.tck.contract.bootstrap.AbstractSubsystemErrorRoutingTck;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core binding for {@link AbstractSubsystemErrorRoutingTck} and direct unit tests
 * for orchestrator health, JFR error reporting, and shutdown error resilience.
 */
@DisplayName("Core: Subsystem Error Routing TCK and direct tests")
class CoreSubsystemErrorRoutingTckTest extends AbstractSubsystemErrorRoutingTck {

    /**
     * In-process provider for supplying dynamic subsystem lists to the test bootstrap sequence.
     */
    public static final class FailurePolicySubsystemProvider implements SubsystemProvider {
        static volatile List<Subsystem> subsystems = List.of();

        public FailurePolicySubsystemProvider() {
            super();
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return subsystems;
        }
    }

    @Override
    protected Throwable bootCapturing(List<Subsystem> subsystems,
                                      boolean degrade,
                                      Runnable kernelMain) throws Exception {
        FailurePolicySubsystemProvider.subsystems = subsystems;
        ClassLoader shim = new KernelBootstrapTest.DualServiceShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        KernelBootstrapTest.StubConfigProvider.class.getName(),
                        "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                        FailurePolicySubsystemProvider.class.getName()
                ));
        SubsystemOrchestrator.FailurePolicy policy = degrade
                ? SubsystemOrchestrator.FailurePolicy.DEGRADE
                : SubsystemOrchestrator.FailurePolicy.FAIL_FAST;
        KernelBootstrap bootstrap = KernelBootstrap.builder()
                .failurePolicy(policy)
                .selector(BootstrapSelector.all())
                .classLoader(shim)
                .build();
        try {
            bootstrap.boot(kernelMain);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    @DisplayName("Health: after FAIL_FAST initialize throws an Error, stateOf(\"probe\") == FAILED")
    void healthStateMarkedFailedWhenInitializeThrowsError() {
        Subsystem failing = new Subsystem() {
            @Override public String name() { return "probe"; }
            @Override public List<String> dependsOn() { return List.of(); }
            @Override public BootstrapPhase phase() { return BootstrapPhase.SERVICES; }
            @Override public boolean isOptional() { return false; }
            @Override public void initialize() { throw new NoClassDefFoundError("tck/Missing"); }
            @Override public void start() { /* nothing to start */ }
            @Override public void stop() { /* nothing to release */ }
        };

        SubsystemOrchestrator orchestrator = newOrchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST, List.of(failing));
        assertThatThrownBy(() -> orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider()))
                .isInstanceOf(SubsystemOrchestrator.BootstrapException.class);

        assertThat(orchestrator.healthMonitor().stateOf("probe"))
                .isEqualTo(KernelHealthMonitor.SubsystemState.FAILED);
    }

    @Test
    @DisplayName("JFR: SubsystemInitialized event recorded with success=false and errorMessage containing Error name")
    void jfrEventRecordedWhenInitializeThrowsError() throws Exception {
        Subsystem failing = new Subsystem() {
            @Override public String name() { return "probe"; }
            @Override public List<String> dependsOn() { return List.of(); }
            @Override public BootstrapPhase phase() { return BootstrapPhase.SERVICES; }
            @Override public boolean isOptional() { return false; }
            @Override public void initialize() { throw new NoClassDefFoundError("tck/Missing"); }
            @Override public void start() { /* nothing to start */ }
            @Override public void stop() { /* nothing to release */ }
        };

        SubsystemOrchestrator orchestrator = newOrchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST, List.of(failing));

        Path dump = Files.createTempFile("subsystem-init-error-jfr", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("eu.exeris.kernel.bootstrap.SubsystemInitialized");
            recording.start();
            try {
                orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());
            } catch (SubsystemOrchestrator.BootstrapException _) {
                // Expected failure
            }
            recording.stop();
            recording.dump(dump);

            List<RecordedEvent> events = RecordingFile.readAllEvents(dump);
            RecordedEvent probeEvent = events.stream()
                    .filter(e -> "eu.exeris.kernel.bootstrap.SubsystemInitialized".equals(e.getEventType().getName()))
                    .filter(e -> "probe".equals(e.getString("subsystemName")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("SubsystemInitialized event not found for probe"));

            assertThat(probeEvent.getBoolean("success")).isFalse();
            assertThat(probeEvent.getString("errorMessage")).contains("NoClassDefFoundError");
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    @Test
    @DisplayName("Shutdown: stop() throwing NoClassDefFoundError still stops dependencies without throwing")
    void shutdownContinuesPastErrorInStop() throws Exception {
        AtomicInteger depStopCount = new AtomicInteger();
        Subsystem dep = new Subsystem() {
            private volatile boolean running;
            @Override public String name() { return "dep"; }
            @Override public List<String> dependsOn() { return List.of(); }
            @Override public BootstrapPhase phase() { return BootstrapPhase.FOUNDATION; }
            @Override public void initialize() { /* nothing to initialise */ }
            @Override public void start() { running = true; }
            @Override public void stop() { running = false; depStopCount.incrementAndGet(); }
            @Override public boolean isRunning() { return running; }
        };

        Subsystem failing = new Subsystem() {
            private volatile boolean running;
            @Override public String name() { return "failing"; }
            @Override public List<String> dependsOn() { return List.of("dep"); }
            @Override public BootstrapPhase phase() { return BootstrapPhase.SERVICES; }
            @Override public void initialize() { /* nothing to initialise */ }
            @Override public void start() { running = true; }
            @Override
            public void stop() {
                running = false;
                throw new NoClassDefFoundError("tck/MissingNativeBinding");
            }
            @Override public boolean isRunning() { return running; }
        };

        SubsystemOrchestrator orchestrator = newOrchestrator(
                SubsystemOrchestrator.FailurePolicy.FAIL_FAST, List.of(dep, failing));
        orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider());
        orchestrator.start(new KernelBootstrapTest.StubConfigProvider());

        assertThat(orchestrator.isStarted()).isTrue();
        assertThat(dep.isRunning()).isTrue();
        assertThat(failing.isRunning()).isTrue();

        // Must not throw even though failing.stop() throws NoClassDefFoundError
        orchestrator.shutdown();

        assertThat(depStopCount).hasValue(1);
        assertThat(orchestrator.isStarted()).isFalse();
    }

    /**
     * A provider ServiceLoader cannot instantiate: its constructor throws, which
     * {@link java.util.ServiceLoader} reports as a {@link java.util.ServiceConfigurationError}.
     */
    public static final class UnloadableSubsystemProvider implements SubsystemProvider {
        public UnloadableSubsystemProvider() {
            throw new NoClassDefFoundError("tck/MissingProviderDependency");
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return List.of();
        }
    }

    @Test
    @DisplayName("Discovery: a provider that cannot load fails the boot as BootstrapException, not as a raw Error")
    void providerDiscoveryErrorBecomesBootstrapException() {
        ClassLoader shimLoader = new KernelBootstrapTest.DualServiceShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        KernelBootstrapTest.StubConfigProvider.class.getName(),
                        "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                        UnloadableSubsystemProvider.class.getName()
                )
        );
        SubsystemOrchestrator orchestrator = SubsystemOrchestrator.builder()
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.DEGRADE)
                .selector(BootstrapSelector.all())
                .classLoader(shimLoader)
                .build();

        assertThatThrownBy(() -> orchestrator.initialize(new KernelBootstrapTest.StubConfigProvider()))
                .isInstanceOf(SubsystemOrchestrator.BootstrapException.class)
                .hasCauseInstanceOf(java.util.ServiceConfigurationError.class)
                .hasRootCauseInstanceOf(NoClassDefFoundError.class);
    }

    private static SubsystemOrchestrator newOrchestrator(SubsystemOrchestrator.FailurePolicy policy, List<Subsystem> subsystems) {
        FailurePolicySubsystemProvider.subsystems = subsystems;
        ClassLoader shimLoader = new KernelBootstrapTest.DualServiceShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        KernelBootstrapTest.StubConfigProvider.class.getName(),
                        "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                        FailurePolicySubsystemProvider.class.getName()
                )
        );
        return SubsystemOrchestrator.builder()
                .failurePolicy(policy)
                .selector(BootstrapSelector.all())
                .classLoader(shimLoader)
                .build();
    }
}
