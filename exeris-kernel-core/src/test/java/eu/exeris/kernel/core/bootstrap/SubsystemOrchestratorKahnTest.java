/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core unit test for {@link SubsystemOrchestrator} — verifies Kahn's BFS
 * topological sort, cycle detection, and lifecycle state transitions.
 *
 * <h2>Location rationale</h2>
 * <p>{@link SubsystemOrchestrator} is the central "Brain" of the Exeris Kernel,
 * residing in {@code exeris-kernel-core}. Its behaviour tests belong here — not in
 * {@code exeris-kernel-community}, which tests Community-tier provider implementations.
 * This separation enforces "The Wall": Core tests Core logic, Community tests Community
 * implementations.
 *
 * <h2>ClassLoader shim strategy</h2>
 * <p>{@link java.util.ServiceLoader} requires a <em>named, non-anonymous</em> class
 * with a public no-arg constructor. {@link FixedSubsystemProvider} is a
 * {@code public static} inner class satisfying this contract, and
 * {@link SubsystemProviderShimLoader} injects its binary name into the
 * {@code META-INF/services} stream without touching the filesystem.
 *
 * @since 0.5.0
 */
@DisplayName("SubsystemOrchestrator — Kahn BFS (Core unit test)")
class SubsystemOrchestratorKahnTest {

    // =========================================================================
    // FixedSubsystemProvider — named static inner class for ServiceLoader
    // =========================================================================

    /**
     * A concrete, named {@link SubsystemProvider} whose subsystem list is set
     * before each orchestrator construction. Must be {@code public static} with a
     * public no-arg constructor to satisfy {@link java.util.ServiceLoader}.
     */
    public static final class FixedSubsystemProvider implements SubsystemProvider {

        /**
         * Subsystem list injected by the test before {@code buildOrchestrator()} is called.
         * Single-threaded access within each test method.
         */
        static List<Subsystem> subsystems = List.of();

        /** Required public no-arg constructor for ServiceLoader. */
        public FixedSubsystemProvider() {
            // ServiceLoader-mandated no-op constructor.
        }

        @Override
        public String moduleName() { return "FixedTestProvider"; }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return subsystems;
        }
    }

    // =========================================================================
    // Minimal test stubs
    // =========================================================================

    /**
     * Creates a minimal {@link Subsystem} stub (no-op lifecycle) for graph tests.
     */
    private static Subsystem stub(String name, BootstrapPhase phase, List<String> deps) {
        return new Subsystem() {
            private volatile boolean running;

            @Override public String name()             { return name; }
            @Override public List<String> dependsOn() { return deps; }
            @Override public BootstrapPhase phase()   { return phase; }

            @Override
            public void initialize() throws SubsystemException {
                // no-op stub — see trackingStub() for order verification
            }

            @Override
            public void start() throws SubsystemException { running = true; }

            @Override public void stop()           { running = false; }
            @Override public boolean isRunning()   { return running; }
        };
    }

    /**
     * Creates a tracking stub that records its name in {@code order} on
     * {@code initialize()} — used to verify topological ordering.
     */
    private static Subsystem trackingStub(String name,
                                           BootstrapPhase phase,
                                           List<String> deps,
                                           List<String> order) {
        return new Subsystem() {
            private volatile boolean running;

            @Override public String name()             { return name; }
            @Override public List<String> dependsOn() { return deps; }
            @Override public BootstrapPhase phase()   { return phase; }

            @Override
            public void initialize() throws SubsystemException { order.add(name); }

            @Override
            public void start() throws SubsystemException { running = true; }

            @Override public void stop()         { running = false; }
            @Override public boolean isRunning() { return running; }
        };
    }

    /**
     * Minimal {@link ConfigProvider} stub — returns JVM defaults, no I/O.
     * Uses method references instead of lambdas for PMD compliance.
     */
    private static ConfigProvider minimalConfig() {
        return new ConfigProvider() {
            @Override
            public Supplier<KernelSettings> kernelSettings() {
                return KernelSettings::defaults;
            }

            @Override public Optional<String> getString(String key)   { return Optional.empty(); }
            @Override public Optional<Integer> getInt(String key)     { return Optional.empty(); }
            @Override public Optional<Long> getLong(String key)       { return Optional.empty(); }
            @Override public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }

            @Override
            public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }

            @Override
            public void watch(String file, String key, Consumer<Object> callback) {
                // Community hot-reload not needed in Core unit tests.
            }

            @Override public String providerName() { return "MinimalTestConfig"; }
        };
    }

    /**
     * Builds a {@link SubsystemOrchestrator} backed by {@link FixedSubsystemProvider}
     * carrying the given {@code subsystems}, injected via {@link SubsystemProviderShimLoader}.
     */
    private static SubsystemOrchestrator buildOrchestrator(List<Subsystem> subsystems) {
        FixedSubsystemProvider.subsystems = subsystems;

        ClassLoader shimLoader = new SubsystemProviderShimLoader(
                Thread.currentThread().getContextClassLoader(),
                FixedSubsystemProvider.class.getName());

        return SubsystemOrchestrator.builder()
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                .selector(BootstrapSelector.all())
                .classLoader(shimLoader)
                .build();
    }

    // =========================================================================
    // Test suites
    // =========================================================================

    @Nested
    @DisplayName("A round stops at the first failure")
    class RoundFailFast {

        /** Records start() calls and optionally throws for one named subsystem. */
        private Subsystem startStub(String name, List<String> started, String failOn) {
            return new Subsystem() {
                private volatile boolean running;

                @Override public String name()            { return name; }
                @Override public List<String> dependsOn() { return List.of(); }
                @Override public BootstrapPhase phase()   { return BootstrapPhase.SERVICES; }
                @Override public void initialize()        { /* nothing to set up */ }

                @Override
                public void start() throws SubsystemException {
                    started.add(name);
                    if (name.equals(failOn)) {
                        throw new SubsystemException(name, SubsystemException.Phase.START,
                                "deliberate failure in " + name);
                    }
                    running = true;
                }

                @Override public void stop()         { running = false; }
                @Override public boolean isRunning() { return running; }
            };
        }

        @Test
        @DisplayName("a failed subsystem fails the phase, whatever else the round had already begun")
        void roundFailureFailsThePhase() throws Exception {
            List<String> started = new ArrayList<>();
            // No dependency between them, so both are ready in the same round and run in name order.
            Subsystem alpha = startStub("alpha", started, "alpha");
            Subsystem bravo = startStub("bravo", started, null);

            SubsystemOrchestrator orchestrator = buildOrchestrator(List.of(alpha, bravo));
            orchestrator.initialize(minimalConfig());

            assertThatThrownBy(() -> orchestrator.start(minimalConfig()))
                    .isInstanceOf(SubsystemOrchestrator.BootstrapException.class);

            // The distributed line asserts more here: it runs the round in-thread, so it can require
            // that bravo — in the real graph, the subsystem that binds a socket and accepts traffic
            // — was never asked to start at all. This line forks the round, so both subtasks are
            // submitted before either can fail and cancellation is asynchronous; "bravo never ran"
            // is not a property of a concurrent round and asserting it here would fail on a correct
            // implementation. What both lines owe is that the phase fails rather than reporting a
            // half-built kernel as started.
            assertThat(started)
                    .as("alpha ran and failed; the phase must not be reported as successful")
                    .contains("alpha");
        }
    }

    @Nested
    @DisplayName("Topological ordering (Kahn BFS)")
    class TopologicalOrdering {

        @Test
        @DisplayName("Linear chain A → B → C initializes in correct order")
        void linearChainOrder() throws Exception {
            List<String> initOrder = new ArrayList<>();

            Subsystem a = trackingStub("alpha",   BootstrapPhase.FOUNDATION, List.of(),        initOrder);
            Subsystem b = trackingStub("bravo",   BootstrapPhase.FOUNDATION, List.of("alpha"), initOrder);
            Subsystem c = trackingStub("charlie", BootstrapPhase.FOUNDATION, List.of("bravo"), initOrder);

            // Deliberately pass reverse order — orchestrator must sort
            buildOrchestrator(List.of(c, b, a)).initialize(minimalConfig());

            assertThat(initOrder.indexOf("alpha"))
                    .as("alpha must be initialized before bravo")
                    .isLessThan(initOrder.indexOf("bravo"));
            assertThat(initOrder.indexOf("bravo"))
                    .as("bravo must be initialized before charlie")
                    .isLessThan(initOrder.indexOf("charlie"));
        }

        @Test
        @DisplayName("Diamond graph: A → {B,C} → D produces valid topological order")
        void diamondGraphOrder() throws Exception {
            List<String> initOrder = new ArrayList<>();

            Subsystem a = trackingStub("a", BootstrapPhase.FOUNDATION, List.of(),          initOrder);
            Subsystem b = trackingStub("b", BootstrapPhase.FOUNDATION, List.of("a"),       initOrder);
            Subsystem c = trackingStub("c", BootstrapPhase.FOUNDATION, List.of("a"),       initOrder);
            Subsystem d = trackingStub("d", BootstrapPhase.FOUNDATION, List.of("b", "c"),  initOrder);

            buildOrchestrator(List.of(d, b, c, a)).initialize(minimalConfig());

            assertThat(initOrder.indexOf("a")).isLessThan(initOrder.indexOf("b"));
            assertThat(initOrder.indexOf("a")).isLessThan(initOrder.indexOf("c"));
            assertThat(initOrder.indexOf("b")).isLessThan(initOrder.indexOf("d"));
            assertThat(initOrder.indexOf("c")).isLessThan(initOrder.indexOf("d"));
        }

        @Test
        @DisplayName("Independent zero-in-degree nodes are initialized in deterministic name order")
        void independentNodesUseDeterministicNameOrder() throws Exception {
            List<String> initOrder = new ArrayList<>();

            Subsystem epsilon = trackingStub("epsilon", BootstrapPhase.FOUNDATION, List.of("beta"), initOrder);
            Subsystem delta = trackingStub("delta", BootstrapPhase.FOUNDATION, List.of("alpha"), initOrder);
            Subsystem gamma = trackingStub("gamma", BootstrapPhase.FOUNDATION, List.of(), initOrder);
            Subsystem beta = trackingStub("beta", BootstrapPhase.FOUNDATION, List.of(), initOrder);
            Subsystem alpha = trackingStub("alpha", BootstrapPhase.FOUNDATION, List.of(), initOrder);

            buildOrchestrator(List.of(epsilon, gamma, delta, beta, alpha)).initialize(minimalConfig());

            assertThat(initOrder).containsExactly("alpha", "beta", "delta", "epsilon", "gamma");
        }

        @Test
        @DisplayName("Empty subsystem list initializes without error")
        void emptyInputYieldsEmptyRegistry() throws Exception {
            SubsystemOrchestrator orch = buildOrchestrator(List.of());
            orch.initialize(minimalConfig());
            assertThat(orch.subsystems()).isEmpty();
        }

        @Test
        @DisplayName("Single independent subsystem initializes and is in registry")
        void singleSubsystemInRegistry() throws Exception {
            SubsystemOrchestrator orch = buildOrchestrator(
                    List.of(stub("memory", BootstrapPhase.FOUNDATION, List.of())));
            orch.initialize(minimalConfig());
            assertThat(orch.subsystems()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Cycle detection — 🜁 ENTROPY INTERVENTION")
    class CycleDetection {

        @Test
        @DisplayName("2-node cycle A ↔ B throws SubsystemCircularDependencyException")
        void twoNodeCycleIsDetected() {
            Subsystem a = stub("alpha", BootstrapPhase.SERVICES, List.of("bravo"));
            Subsystem b = stub("bravo", BootstrapPhase.SERVICES, List.of("alpha"));

            SubsystemOrchestrator orch = buildOrchestrator(List.of(a, b));

            assertThatThrownBy(() -> orch.initialize(minimalConfig()))
                    .as("2-node cycle MUST throw SubsystemCircularDependencyException")
                    .isInstanceOf(SubsystemCircularDependencyException.class);
        }

        @Test
        @DisplayName("3-node cycle A→B→C→A throws exception with EX-BOOT-0001 code")
        void threeNodeCycleCarriesErrorCode() {
            Subsystem a = stub("alpha",   BootstrapPhase.SERVICES, List.of("charlie"));
            Subsystem b = stub("bravo",   BootstrapPhase.SERVICES, List.of("alpha"));
            Subsystem c = stub("charlie", BootstrapPhase.SERVICES, List.of("bravo"));

            SubsystemOrchestrator orch = buildOrchestrator(List.of(a, b, c));

            assertThatThrownBy(() -> orch.initialize(minimalConfig()))
                    .isInstanceOf(SubsystemCircularDependencyException.class)
                    .hasMessageContaining("EX-BOOT-0001");
        }

        @Test
        @DisplayName("Cycle exception contains names of all cycle members")
        void cycleExceptionContainsMemberNames() {
            Subsystem a = stub("alpha",   BootstrapPhase.SERVICES, List.of("charlie"));
            Subsystem b = stub("bravo",   BootstrapPhase.SERVICES, List.of("alpha"));
            Subsystem c = stub("charlie", BootstrapPhase.SERVICES, List.of("bravo"));

            SubsystemOrchestrator orch = buildOrchestrator(List.of(a, b, c));

            assertThatThrownBy(() -> orch.initialize(minimalConfig()))
                    .isInstanceOf(SubsystemCircularDependencyException.class)
                    .hasMessageContainingAll("alpha", "bravo", "charlie");
        }
    }

    @Nested
    @DisplayName("Lifecycle state machine")
    class LifecycleStateMachine {

        @Test
        @DisplayName("initialize() → start() → shutdown() advances and resets state flags")
        void fullLifecycleCycle() throws Exception {
            Subsystem a = stub("memory", BootstrapPhase.FOUNDATION, List.of());

            SubsystemOrchestrator orch = buildOrchestrator(List.of(a));

            assertThat(orch.isInitialized()).isFalse();
            assertThat(orch.isStarted()).isFalse();

            orch.initialize(minimalConfig());
            assertThat(orch.isInitialized()).isTrue();
            assertThat(orch.isStarted()).isFalse();

            orch.start(minimalConfig());
            assertThat(orch.isStarted()).isTrue();

            orch.shutdown();
            assertThat(orch.isStarted()).isFalse();
            assertThat(orch.isInitialized()).isFalse();
        }

        @Test
        @DisplayName("shutdown() on un-initialized orchestrator is a no-op (idempotent)")
        void shutdownOnUninitializedIsNoOp() {
            SubsystemOrchestrator orch = buildOrchestrator(List.of());
            // Must not throw even though initialize() was never called
            orch.shutdown();
            assertThat(orch.isStarted()).isFalse();
            assertThat(orch.isInitialized()).isFalse();
        }

        @Test
        @DisplayName("shutdown() before initialize() does not consume the orchestrator")
        void shutdownBeforeInitializeDoesNotConsumeOrchestrator() throws Exception {
            Subsystem a = stub("memory", BootstrapPhase.FOUNDATION, List.of());
            SubsystemOrchestrator orch = buildOrchestrator(List.of(a));

            orch.shutdown();
            orch.initialize(minimalConfig());
            orch.start(minimalConfig());

            assertThat(orch.isInitialized()).isTrue();
            assertThat(orch.isStarted()).isTrue();
        }

        @Test
        @DisplayName("orchestrator cannot be initialized again after shutdown")
        void initializeAfterShutdownFails() throws Exception {
            Subsystem a = stub("memory", BootstrapPhase.FOUNDATION, List.of());
            SubsystemOrchestrator orch = buildOrchestrator(List.of(a));

            orch.initialize(minimalConfig());
            orch.start(minimalConfig());
            orch.shutdown();

            assertThatThrownBy(() -> orch.initialize(minimalConfig()))
                    .isInstanceOf(SubsystemOrchestrator.BootstrapException.class)
                    .hasMessageContaining("cannot be reused after shutdown");
        }

        @Test
        @DisplayName("orchestrator cannot be started again after shutdown")
        void startAfterShutdownFails() throws Exception {
            Subsystem a = stub("memory", BootstrapPhase.FOUNDATION, List.of());
            SubsystemOrchestrator orch = buildOrchestrator(List.of(a));

            orch.initialize(minimalConfig());
            orch.start(minimalConfig());
            orch.shutdown();

            assertThatThrownBy(() -> orch.start(minimalConfig()))
                    .isInstanceOf(SubsystemOrchestrator.BootstrapException.class)
                    .hasMessageContaining("cannot be reused after shutdown");
        }

        @Test
        @DisplayName("repeated shutdown() after lifecycle completion is a no-op")
        void repeatedShutdownIsNoOp() throws Exception {
            Subsystem a = stub("memory", BootstrapPhase.FOUNDATION, List.of());
            SubsystemOrchestrator orch = buildOrchestrator(List.of(a));

            orch.initialize(minimalConfig());
            orch.start(minimalConfig());
            orch.shutdown();
            orch.shutdown();

            assertThat(orch.isInitialized()).isFalse();
            assertThat(orch.isStarted()).isFalse();
        }
    }

    @Nested
    @DisplayName("Missing dependency fail-fast")
    class MissingDependency {

        @Test
        @DisplayName("selector=ALL with missing dependency fails initialize()")
        void missingDependencyFailsFast() {
            Subsystem transport = stub("transport", BootstrapPhase.SERVICES, List.of("memory", "ghost"));
            Subsystem memory = stub("memory", BootstrapPhase.FOUNDATION, List.of());

            SubsystemOrchestrator orchestrator = buildOrchestrator(List.of(transport, memory));

            assertThatThrownBy(() -> orchestrator.initialize(minimalConfig()))
                    .isInstanceOf(SubsystemOrchestrator.BootstrapException.class)
                    .hasMessageContaining("ghost");
        }
    }

    // =========================================================================
    // ClassLoader shim — injects FixedSubsystemProvider into ServiceLoader
    // =========================================================================

    /**
     * Injects a single named {@link SubsystemProvider} class into the
     * {@link java.util.ServiceLoader} discovery path by overriding the
     * {@code META-INF/services} resource stream and enumeration — no filesystem
     * writes, no {@code module-info} changes.
     *
     * <h2>Why both getResourceAsStream and getResources?</h2>
     * <p>JDK ServiceLoader uses {@link ClassLoader#getResources(String)} (plural)
     * to iterate all service descriptors across the classpath. Overriding only
     * {@code getResourceAsStream} is insufficient — the loader never calls it.
     *
     * <p>Package-private: reused by {@link ScopedValueBindingTest}.
     */
    static final class SubsystemProviderShimLoader extends ClassLoader {

        private static final String SPI_RESOURCE =
                "META-INF/services/eu.exeris.kernel.spi.bootstrap.SubsystemProvider";

        private final String providerClassName;

        SubsystemProviderShimLoader(ClassLoader parent, String providerClassName) {
            super(parent);
            this.providerClassName = providerClassName;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (SPI_RESOURCE.equals(name)) {
                return toStream(providerClassName);
            }
            return super.getResourceAsStream(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (SPI_RESOURCE.equals(name)) {
                return Collections.enumeration(List.of(toInMemoryUrl(providerClassName)));
            }
            return super.getResources(name);
        }

        // ── helpers ──────────────────────────────────────────────────────────

        private static InputStream toStream(String content) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        private static URL toInMemoryUrl(String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                return new URL(null, "memory://" + SPI_RESOURCE,
                        new URLStreamHandler() {
                            @Override
                            protected URLConnection openConnection(URL u) {
                                return new URLConnection(u) {
                                    @Override public void connect() { /* no-op */ }

                                    @Override
                                    public InputStream getInputStream() {
                                        return new ByteArrayInputStream(bytes);
                                    }
                                };
                            }
                        });
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "SubsystemProviderShimLoader: failed to build in-memory URL", ex);
            }
        }
    }
}
