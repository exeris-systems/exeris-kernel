/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.bootstrap.SubsystemOrchestrator;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
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
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test: verifies that all Community subsystems initialize and start in the
 * correct Boot DAG order dictated by {@code docs/subsystems/bootstrap.md}.
 *
 * <h2>The Holy Order under test</h2>
 * <pre>
 *   FOUNDATION (sequential):  memory (no deps)
 *   SERVICES   (parallel):    security, persistence  — each depends on memory
 *   RUNTIME    (parallel):    events, flow            — each depends on persistence
 * </pre>
 *
 * <h2>Strategy</h2>
 * <p>The test wires a set of tracking {@link Subsystem} stubs that record
 * {@code initialize()} and {@code start()} call order into a
 * {@link CopyOnWriteArrayList}. The stubs are injected into
 * {@link SubsystemOrchestrator} via the standard
 * {@link SubsystemProvider} / {@link java.util.ServiceLoader} shim mechanism
 * already established in {@code SubsystemOrchestratorKahnTest}.
 *
 * <h2>What is verified</h2>
 * <ul>
 *   <li>Every declared dependency is initialized before its dependant.</li>
 *   <li>FOUNDATION subsystems are always initialized before SERVICES and RUNTIME.</li>
 *   <li>SERVICES subsystems are always initialized before RUNTIME.</li>
 *   <li>After {@code initialize()}, all subsystems have been called exactly once.</li>
 *   <li>After {@code start()}, all subsystems report {@link Subsystem#isRunning()} = true.</li>
 *   <li>After {@code shutdown()}, all subsystems report {@link Subsystem#isRunning()} = false
 *       and shutdown order is strictly the reverse of init order.</li>
 *   <li>The real {@code CommunityMemorySubsystem} starts cleanly within
 *       {@code KernelBootstrap.boot()} and binds {@link KernelProviders#MEMORY_ALLOCATOR}.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see SubsystemOrchestrator
 * @see KernelBootstrap
 */
@DisplayName("Community Bootstrap: Subsystem initialization order integration test")
class SubsystemInitOrderIntegrationTest {

    // =========================================================================
    // Tracking stub infrastructure
    // =========================================================================

    /**
     * A tracking {@link Subsystem} that records lifecycle call timestamps in
     * a shared {@link CopyOnWriteArrayList}.
     *
     * <p>The record format is {@code "<event>:<name>"}, e.g., {@code "init:memory"},
     * {@code "start:persistence"}, {@code "stop:security"}.
     */
    static TrackingSubsystem tracking(String name,
                                       BootstrapPhase phase,
                                       List<String> deps,
                                       CopyOnWriteArrayList<String> eventLog) {
        return new TrackingSubsystem(name, phase, deps, eventLog);
    }

    static final class TrackingSubsystem implements Subsystem {

        private final String                     name;
        private final BootstrapPhase             phase;
        private final List<String>               deps;
        private final CopyOnWriteArrayList<String> log;
        private volatile boolean                 running;

        TrackingSubsystem(String name, BootstrapPhase phase,
                          List<String> deps, CopyOnWriteArrayList<String> log) {
            this.name  = name;
            this.phase = phase;
            this.deps  = deps;
            this.log   = log;
        }

        @Override public String           name()             { return name; }
        @Override public BootstrapPhase   phase()            { return phase; }
        @Override public List<String>     dependsOn()        { return deps; }

        @Override
        public void initialize() throws SubsystemException {
            log.add("init:" + name);
        }

        @Override
        public void start() throws SubsystemException {
            running = true;
            log.add("start:" + name);
        }

        @Override
        public void stop() {
            running = false;
            log.add("stop:" + name);
        }

        @Override public boolean isRunning() { return running; }
    }

    // =========================================================================
    // ClassLoader shim — reuses the mechanism from SubsystemOrchestratorKahnTest
    // =========================================================================

    /**
     * Public named {@link SubsystemProvider} injectable via ServiceLoader.
     * The test sets {@link #subsystems} before constructing the orchestrator.
     */
    public static final class TrackingSubsystemProvider implements SubsystemProvider {

        static List<Subsystem> subsystems = List.of();

        public TrackingSubsystemProvider() { /* ServiceLoader-required public no-arg constructor */ }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return subsystems;
        }

        @Override
        public String moduleName() { return "TrackingTestProvider"; }
    }

    private static SubsystemOrchestrator buildOrchestratorWith(List<Subsystem> subsystems) {
        TrackingSubsystemProvider.subsystems = subsystems;
        ClassLoader shimLoader = new SubsystemProviderShimLoader(
                Thread.currentThread().getContextClassLoader(),
                TrackingSubsystemProvider.class.getName());
        return SubsystemOrchestrator.builder()
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                .selector(BootstrapSelector.all())
                .classLoader(shimLoader)
                .build();
    }

    private static ConfigProvider minimalConfig() {
        return new ConfigProvider() {
            @Override public Supplier<KernelSettings> kernelSettings() { return KernelSettings::defaults; }
            @Override public Optional<String>  getString(String k)  { return Optional.empty(); }
            @Override public Optional<Integer> getInt(String k)      { return Optional.empty(); }
            @Override public Optional<Long>    getLong(String k)     { return Optional.empty(); }
            @Override public Optional<Boolean> getBoolean(String k)  { return Optional.empty(); }
            @Override public <T> Optional<T>   get(String k, Class<T> t) { return Optional.empty(); }
            @Override public void watch(String f, String k, Consumer<Object> cb) { /* no-op — test stub */ }
            @Override public String providerName() { return "MinimalTestConfig"; }
        };
    }

    // =========================================================================
    // Test: Boot DAG order
    // =========================================================================

    @Nested
    @DisplayName("Boot DAG — initialization order invariants")
    class BootDagOrder {

        @Test
        @DisplayName("FOUNDATION subsystem is initialized before SERVICES dependants")
        void foundationBeforeServices() throws Exception {
            var log = new CopyOnWriteArrayList<String>();

            var memory      = tracking("memory",      BootstrapPhase.FOUNDATION, List.of(),           log);
            var security    = tracking("security",    BootstrapPhase.SERVICES,   List.of("memory"),   log);
            var persistence = tracking("persistence", BootstrapPhase.SERVICES,   List.of("memory"),   log);

            buildOrchestratorWith(List.of(security, memory, persistence))
                    .initialize(minimalConfig());

            int idxMemory = indexOfInit(log, "memory");
            assertThat(indexOfInit(log, "security"))
                    .as("security must be initialized after memory")
                    .isGreaterThan(idxMemory);
            assertThat(indexOfInit(log, "persistence"))
                    .as("persistence must be initialized after memory")
                    .isGreaterThan(idxMemory);
        }

        @Test
        @DisplayName("SERVICES subsystems are initialized before RUNTIME dependants")
        void servicesBeforeRuntime() throws Exception {
            var log = new CopyOnWriteArrayList<String>();

            var memory      = tracking("memory",      BootstrapPhase.FOUNDATION, List.of(),                  log);
            var persistence = tracking("persistence", BootstrapPhase.SERVICES,   List.of("memory"),          log);
            var events      = tracking("events",      BootstrapPhase.RUNTIME,    List.of("persistence"),     log);
            var flow        = tracking("flow",        BootstrapPhase.RUNTIME,    List.of("persistence"),     log);

            buildOrchestratorWith(List.of(flow, events, persistence, memory))
                    .initialize(minimalConfig());

            int idxPersistence = indexOfInit(log, "persistence");
            assertThat(indexOfInit(log, "events"))
                    .as("events must be initialized after persistence")
                    .isGreaterThan(idxPersistence);
            assertThat(indexOfInit(log, "flow"))
                    .as("flow must be initialized after persistence")
                    .isGreaterThan(idxPersistence);
        }

        @Test
        @DisplayName("Full 7-subsystem Boot DAG (memory→security,persistence→graph,transport→events,flow)")
        void fullBootDagOrder() throws Exception {
            var log = new CopyOnWriteArrayList<String>();

            var memory      = tracking("memory",      BootstrapPhase.FOUNDATION, List.of(),                        log);
            var security    = tracking("security",    BootstrapPhase.SERVICES,   List.of("memory"),                log);
            var persistence = tracking("persistence", BootstrapPhase.SERVICES,   List.of("memory"),                log);
            var graph       = tracking("graph",       BootstrapPhase.SERVICES,   List.of("memory"),                log);
            var transport   = tracking("transport",   BootstrapPhase.SERVICES,   List.of("memory"),                log);
            var events      = tracking("events",      BootstrapPhase.RUNTIME,    List.of("persistence", "transport"), log);
            var flow        = tracking("flow",        BootstrapPhase.RUNTIME,    List.of("persistence"),            log);

            buildOrchestratorWith(
                    List.of(flow, events, transport, graph, persistence, security, memory))
                    .initialize(minimalConfig());

            List<String> inits = log.stream()
                    .filter(e -> e.startsWith("init:"))
                    .toList();

            assertThat(inits).hasSize(7);

            int idxMemory      = indexOfInit(log, "memory");
            int idxPersistence = indexOfInit(log, "persistence");
            int idxTransport   = indexOfInit(log, "transport");

            // L0 before L1
            assertThat(idxMemory).isLessThan(indexOfInit(log, "security"));
            assertThat(idxMemory).isLessThan(idxPersistence);
            assertThat(idxMemory).isLessThan(indexOfInit(log, "graph"));
            assertThat(idxMemory).isLessThan(idxTransport);

            // L1 before L2
            assertThat(idxPersistence).isLessThan(indexOfInit(log, "events"));
            assertThat(idxPersistence).isLessThan(indexOfInit(log, "flow"));
            assertThat(idxTransport).isLessThan(indexOfInit(log, "events"));
        }

        @Test
        @DisplayName("Each subsystem is initialized exactly once")
        void eachSubsystemInitializedExactlyOnce() throws Exception {
            var log = new CopyOnWriteArrayList<String>();

            var memory      = tracking("memory",      BootstrapPhase.FOUNDATION, List.of(),        log);
            var persistence = tracking("persistence", BootstrapPhase.SERVICES,   List.of("memory"), log);
            var events      = tracking("events",      BootstrapPhase.RUNTIME,    List.of("persistence"), log);

            buildOrchestratorWith(List.of(events, persistence, memory))
                    .initialize(minimalConfig());

            assertThat(log.stream().filter(e -> e.equals("init:memory")).count())
                    .as("memory must be initialized exactly once").isEqualTo(1);
            assertThat(log.stream().filter(e -> e.equals("init:persistence")).count())
                    .as("persistence must be initialized exactly once").isEqualTo(1);
            assertThat(log.stream().filter(e -> e.equals("init:events")).count())
                    .as("events must be initialized exactly once").isEqualTo(1);
        }
    }

    // =========================================================================
    // Test: Start order
    // =========================================================================

    @Nested
    @DisplayName("Start order invariants")
    class StartOrder {

        @Test
        @DisplayName("All subsystems are started after initialize()+start() sequence")
        void allSubsystemsStarted() throws Exception {
            var log = new CopyOnWriteArrayList<String>();

            var memory      = tracking("memory",      BootstrapPhase.FOUNDATION, List.of(),        log);
            var persistence = tracking("persistence", BootstrapPhase.SERVICES,   List.of("memory"), log);

            var orch = buildOrchestratorWith(List.of(memory, persistence));
            var cfg  = minimalConfig();
            orch.initialize(cfg);
            orch.start(cfg);

            assertThat(memory.isRunning()).isTrue();
            assertThat(persistence.isRunning()).isTrue();
        }

        @Test
        @DisplayName("isStarted() is true after start() and false after shutdown()")
        void isStartedLifecycle() throws Exception {
            var memory = tracking("memory", BootstrapPhase.FOUNDATION, List.of(),
                    new CopyOnWriteArrayList<>());
            var orch = buildOrchestratorWith(List.of(memory));
            var cfg  = minimalConfig();

            orch.initialize(cfg);
            orch.start(cfg);
            assertThat(orch.isStarted()).isTrue();

            orch.shutdown();
            assertThat(orch.isStarted()).isFalse();
        }
    }

    // =========================================================================
    // Test: Shutdown reverse order
    // =========================================================================

    @Nested
    @DisplayName("Shutdown — reverse topological order")
    class ShutdownOrder {

        @Test
        @DisplayName("Subsystems are stopped in strict reverse of initialization order")
        void shutdownIsReverseOfInit() throws Exception {
            var log = new CopyOnWriteArrayList<String>();

            var memory      = tracking("memory",      BootstrapPhase.FOUNDATION, List.of(),        log);
            var persistence = tracking("persistence", BootstrapPhase.SERVICES,   List.of("memory"), log);
            var events      = tracking("events",      BootstrapPhase.RUNTIME,    List.of("persistence"), log);

            var orch = buildOrchestratorWith(List.of(events, persistence, memory));
            var cfg  = minimalConfig();
            orch.initialize(cfg);
            orch.start(cfg);
            orch.shutdown();

            List<String> stops = log.stream()
                    .filter(e -> e.startsWith("stop:"))
                    .toList();

            // events was initialized last → must be stopped first
            assertThat(stops.indexOf("stop:events"))
                    .as("events (last initialized) must be stopped first")
                    .isLessThan(stops.indexOf("stop:persistence"));
            assertThat(stops.indexOf("stop:persistence"))
                    .as("persistence must be stopped before memory (reverse order)")
                    .isLessThan(stops.indexOf("stop:memory"));
        }

        @Test
        @DisplayName("shutdown() is safe even if start() was never called")
        void shutdownWithoutStartIsNoOp() throws Exception {
            var memory = tracking("memory", BootstrapPhase.FOUNDATION, List.of(),
                    new CopyOnWriteArrayList<>());
            var orch = buildOrchestratorWith(List.of(memory));
            orch.initialize(minimalConfig());

            // start() NOT called — shutdown must be a no-op (isRunning()=false)
            orch.shutdown();
            assertThat(memory.isRunning()).isFalse();
        }
    }

    // =========================================================================
    // Test: DEGRADE policy
    // =========================================================================

    @Nested
    @DisplayName("Failure policies")
    class FailurePolicies {

        @Test
        @DisplayName("FAIL_FAST: any subsystem failure aborts boot with BootstrapException")
        void failFastAbortsOnFailure() {
            var badSubsystem = new Subsystem() {
                @Override public String           name()         { return "bad"; }
                @Override public BootstrapPhase   phase()        { return BootstrapPhase.FOUNDATION; }
                @Override public List<String>     dependsOn()    { return List.of(); }
                @Override public void initialize() throws SubsystemException {
                    throw new SubsystemException("bad", SubsystemException.Phase.INITIALIZE,
                            "simulated failure", null);
                }
                @Override public void start()  { /* test stub — no lifecycle action needed */ }
                @Override public void stop()   { /* test stub — no lifecycle action needed */ }
            };

            TrackingSubsystemProvider.subsystems = List.of(badSubsystem);
            ClassLoader shimLoader = new SubsystemProviderShimLoader(
                    Thread.currentThread().getContextClassLoader(),
                    TrackingSubsystemProvider.class.getName());

            var orch = SubsystemOrchestrator.builder()
                    .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                    .selector(BootstrapSelector.all())
                    .classLoader(shimLoader)
                    .build();

            assertThatThrownBy(() -> orch.initialize(minimalConfig()))
                    .as("FAIL_FAST: mandatory FOUNDATION failure must throw BootstrapException")
                    .isInstanceOf(SubsystemOrchestrator.BootstrapException.class)
                    .hasMessageContaining("bad");
        }

        @Test
        @DisplayName("DEGRADE: optional SERVICES subsystem failure is skipped; boot continues")
        void degradeSkipsOptionalFailure() throws Exception {
            var log = new CopyOnWriteArrayList<String>();
            var memory = tracking("memory", BootstrapPhase.FOUNDATION, List.of(), log);

            var optionalBad = new Subsystem() {
                @Override public String           name()         { return "optional-bad"; }
                @Override public BootstrapPhase   phase()        { return BootstrapPhase.SERVICES; }
                @Override public List<String>     dependsOn()    { return List.of("memory"); }
                @Override public boolean          isOptional()   { return true; }
                @Override public void initialize() throws SubsystemException {
                    throw new SubsystemException("optional-bad", SubsystemException.Phase.INITIALIZE,
                            "optional failure", null);
                }
                @Override public void start() { /* test stub — no lifecycle action needed */ }
                @Override public void stop()  { /* test stub — no lifecycle action needed */ }
            };

            TrackingSubsystemProvider.subsystems = List.of(memory, optionalBad);
            ClassLoader shimLoader = new SubsystemProviderShimLoader(
                    Thread.currentThread().getContextClassLoader(),
                    TrackingSubsystemProvider.class.getName());

            var orch = SubsystemOrchestrator.builder()
                    .failurePolicy(SubsystemOrchestrator.FailurePolicy.DEGRADE)
                    .selector(BootstrapSelector.all())
                    .classLoader(shimLoader)
                    .build();

            orch.initialize(minimalConfig()); // must NOT throw

            assertThat(orch.subsystems())
                    .as("Optional failed subsystem must be removed from the active registry")
                    .extracting(Subsystem::name)
                    .doesNotContain("optional-bad")
                    .contains("memory");
        }
    }

    // =========================================================================
    // Test: Real CommunityMemorySubsystem boot via KernelBootstrap
    // =========================================================================

    @Nested
    @DisplayName("Real Community boot: CommunityMemorySubsystem via KernelBootstrap")
    class RealCommunityBoot {

        @Test
        @DisplayName("KernelBootstrap with CommunityMemorySubsystem binds MEMORY_ALLOCATOR slot")
        void communityMemorySubsystemBindsAllocator() throws KernelBootstrap.BootstrapException {
            var allocatorRef = new AtomicReference<eu.exeris.kernel.spi.memory.MemoryAllocator>();

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("memory"))
                    .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                    .build()
                    .boot(() -> allocatorRef.set(KernelProviders.MEMORY_ALLOCATOR.get()));

            assertThat(allocatorRef.get())
                    .as("CommunityMemorySubsystem must bind a non-null MemoryAllocator " +
                        "into KernelProviders.MEMORY_ALLOCATOR during boot")
                    .isNotNull();
        }

        @Test
        @DisplayName("MEMORY_ALLOCATOR ScopedValue is unbound after boot() returns")
        void memoryAllocatorUnboundAfterBoot() throws KernelBootstrap.BootstrapException {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("memory"))
                    .build()
                    .boot(() -> {
                        assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound())
                                .as("MEMORY_ALLOCATOR must be bound inside boot()")
                                .isTrue();
                    });

            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound())
                    .as("MEMORY_ALLOCATOR must be unbound after boot() returns — The Wall holds")
                    .isFalse();
        }

        @Test
        @DisplayName("BootstrapSelector.none() boots cleanly with zero subsystems")
        void selectorNoneBootsCleanly() throws KernelBootstrap.BootstrapException {
            var ran = new AtomicBoolean(false);

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.none())
                    .build()
                    .boot(() -> ran.set(true));

            assertThat(ran.get())
                    .as("kernelMain must execute even with zero subsystems")
                    .isTrue();
        }

        @Test
        @DisplayName("KernelBootstrap.boot() with BootstrapSelector.all() includes CommunityMemorySubsystem")
        void selectorAllIncludesMemorySubsystem() throws KernelBootstrap.BootstrapException {
            String dbName = "exeris_allselector_" + System.nanoTime();
            String prevJdbcUrl = System.getProperty("exeris.persistence.jdbcUrl");
            String prevUsername = System.getProperty("exeris.persistence.username");
            String prevPassword = System.getProperty("exeris.persistence.password");
            String prevMaxPool  = System.getProperty("exeris.persistence.maxPoolSize");

            System.setProperty("exeris.persistence.jdbcUrl",
                    "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
            System.setProperty("exeris.persistence.username", "sa");
            System.setProperty("exeris.persistence.password", "");
            System.setProperty("exeris.persistence.maxPoolSize", "4");

            var allocatorBound = new AtomicBoolean(false);

            try {
                KernelBootstrap.builder()
                        .selector(BootstrapSelector.all())
                        .build()
                        .boot(() -> allocatorBound.set(KernelProviders.MEMORY_ALLOCATOR.isBound()));
            } finally {
                restoreProperty("exeris.persistence.jdbcUrl", prevJdbcUrl);
                restoreProperty("exeris.persistence.username", prevUsername);
                restoreProperty("exeris.persistence.password", prevPassword);
                restoreProperty("exeris.persistence.maxPoolSize", prevMaxPool);
            }

            assertThat(allocatorBound.get())
                    .as("BootstrapSelector.all() must activate CommunityMemorySubsystem, " +
                        "binding MEMORY_ALLOCATOR inside boot()")
                    .isTrue();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    /** Returns the index of the first {@code "init:<name>"} entry in the event log. */
    private static int indexOfInit(List<String> log, String name) {
        int idx = log.indexOf("init:" + name);
        assertThat(idx)
                .as("Event 'init:%s' not found in log %s", name, log)
                .isGreaterThanOrEqualTo(0);
        return idx;
    }

    // =========================================================================
    // ServiceLoader shim — injects TrackingSubsystemProvider into ServiceLoader
    // =========================================================================

    /**
     * Injects a single named {@link SubsystemProvider} into the
     * {@link java.util.ServiceLoader} discovery path by overriding the
     * {@code META-INF/services} resource stream — no filesystem writes.
     */
    // PMD.CommentDefaultAccessModifier: package-private is intentional — test infrastructure
    @SuppressWarnings("PMD.CommentDefaultAccessModifier")
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
                            protected URLConnection openConnection(URL url) {
                                return new URLConnection(url) {
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








