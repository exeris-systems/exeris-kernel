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
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core unit tests for the {@link Subsystem#providerBindings()} ScopedValue binding protocol.
 *
 * <h2>What is tested</h2>
 * <p>Verifies the full Option-A provider-binding flow:
 * <ol>
 *   <li>A FOUNDATION subsystem's {@code providerBindings()} return value is collected
 *       by the orchestrator after {@code initialize()}.</li>
 *   <li>{@link SubsystemOrchestrator#buildKernelScope()} produces a non-null
 *       {@link ScopedValue.Carrier} containing those bindings.</li>
 *   <li>Inside {@link KernelBootstrap#boot(Runnable)}, the enriched carrier makes
 *       {@link KernelProviders#MEMORY_ALLOCATOR} available via {@link KernelProviders#allocator()}
 *       during {@code start()} of a dependent subsystem and in {@code kernelMain}.</li>
 *   <li>The carrier scope is strictly contained within {@code boot()} — bindings are
 *       NOT visible after {@code boot()} returns ("The Wall" holds).</li>
 *   <li>A subsystem returning an empty map from {@code providerBindings()} produces
 *       a {@code null} carrier (no unnecessary scope wrapping).</li>
 * </ol>
 *
 * <h2>ClassLoader strategy</h2>
 * <p>Both {@link ConfigProvider} and {@link SubsystemProvider} are injected via
 * {@link DualShimLoader} — the same pattern used in {@link KernelBootstrapTest} and
 * {@link SubsystemOrchestratorKahnTest}.
 *
 * @since 0.5.0
 * @see Subsystem#providerBindings()
 * @see SubsystemOrchestrator#buildKernelScope()
 */
@DisplayName("ScopedValue provider-binding protocol — Core unit tests")
class ScopedValueBindingTest {

    // =========================================================================
    // Stub SPI implementations
    // =========================================================================

    /**
     * Minimal no-op {@link MemoryAllocator} used as the mock binding value.
     * Kernel logic only needs to assert that the same instance is accessible;
     * no actual allocation is performed.
     */
    static final class NoOpMemoryAllocator implements MemoryAllocator {
        static final NoOpMemoryAllocator INSTANCE = new NoOpMemoryAllocator();

        private NoOpMemoryAllocator() {}

        @Override public LoanedBuffer allocate(AllocationHint hint) { throw unsupported(); }
        @Override public LoanedBuffer allocateNetwork(int estimatedBytes) { throw unsupported(); }
        @Override public LoanedBuffer allocateCarrierSlab(int carrierIndex) { throw unsupported(); }
        @Override public LoanedBuffer allocateInfrastructure(long sizeBytes) { throw unsupported(); }
        @Override public MemoryStats stats() { throw unsupported(); }
        @Override public void close() { /* no-op stub — no off-heap resources to release */ }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("NoOpMemoryAllocator — stub only");
        }
    }

    /**
     * Minimal no-op {@link MemoryProvider} — not used by the test but required
     * to populate {@link KernelProviders#MEMORY_PROVIDER}.
     */
    static final class NoOpMemoryProvider implements MemoryProvider {
        static final NoOpMemoryProvider INSTANCE = new NoOpMemoryProvider();

        private NoOpMemoryProvider() {}

        @Override
        public MemoryAllocator createAllocator(MemoryProviderConfig config) {
            return NoOpMemoryAllocator.INSTANCE;
        }

        @Override
        public String providerName() { return "NoOpMemoryProvider"; }
    }

    // =========================================================================
    // Stub Subsystem implementations
    // =========================================================================

    /**
     * FOUNDATION subsystem that publishes {@link KernelProviders#MEMORY_ALLOCATOR}
     * and {@link KernelProviders#MEMORY_PROVIDER} via {@link #providerBindings()}.
     *
     * <p>Represents the expected implementation pattern for
     * {@code CommunityMemorySubsystem} / {@code EnterpriseMemorySubsystem}.
     */
    static final class MemoryBindingSubsystem implements Subsystem {
        private volatile boolean running;

        @Override public String name()           { return "memory"; }
        @Override public BootstrapPhase phase()  { return BootstrapPhase.FOUNDATION; }
        @Override public List<String> dependsOn() { return List.of(); }
        @Override public void initialize()        { /* no-op — resources already "allocated" as statics */ }
        @Override public void start()             { running = true; }
        @Override public void stop()              { running = false; }
        @Override public boolean isRunning()      { return running; }

        /**
         * Exposes {@link NoOpMemoryAllocator#INSTANCE} and {@link NoOpMemoryProvider#INSTANCE}
         * to the kernel scope — the subject of the binding contract.
         */
        @Override
        public UnaryOperator<ScopedValue.Carrier> providerBindings() {
            return carrier -> carrier
                    .where(KernelProviders.MEMORY_ALLOCATOR, NoOpMemoryAllocator.INSTANCE)
                    .where(KernelProviders.MEMORY_PROVIDER,  NoOpMemoryProvider.INSTANCE);
        }
    }

    /**
     * SERVICES-phase subsystem that depends on {@code "memory"}.
     *
     * <p>Captures whether {@link KernelProviders#MEMORY_ALLOCATOR} was accessible
     * during its own {@link #start()} call — this is the core assertion of the test.
     */
    static final class AllocatorConsumerSubsystem implements Subsystem {
        volatile boolean allocatorBoundDuringStart;
        final AtomicReference<MemoryAllocator> capturedAllocator = new AtomicReference<>();
        private volatile boolean running;

        @Override public String name()            { return "transport"; }
        @Override public BootstrapPhase phase()   { return BootstrapPhase.SERVICES; }
        @Override public List<String> dependsOn() { return List.of("memory"); }
        @Override public void initialize()        { /* no-op — nothing to allocate in this stub */ }

        @Override
        public void start() {
            allocatorBoundDuringStart = KernelProviders.MEMORY_ALLOCATOR.isBound();
            if (allocatorBoundDuringStart) {
                capturedAllocator.set(KernelProviders.MEMORY_ALLOCATOR.get());
            }
            running = true;
        }

        @Override public void stop()              { running = false; }
        @Override public boolean isRunning()      { return running; }
    }

    /**
     * FOUNDATION subsystem that returns an empty {@link #providerBindings()} map.
     * Used to verify that {@link SubsystemOrchestrator#buildKernelScope()} returns
     * {@code null} when there are no bindings to publish.
     */
    static final class NoBindingsSubsystem implements Subsystem {
        @Override public String name()            { return "telemetry"; }
        @Override public BootstrapPhase phase()   { return BootstrapPhase.FOUNDATION; }
        @Override public List<String> dependsOn() { return List.of(); }
        @Override public void initialize()        { /* no-op — telemetry stub, no resources */ }
        @Override public void start()             { /* no-op */ }
        @Override public void stop()              { /* no-op */ }
        // providerBindings() intentionally NOT overridden — inherits Map.of()
    }

    // =========================================================================
    // ServiceLoader stubs
    // =========================================================================

    /**
     * {@link SubsystemProvider} exposing {@link MemoryBindingSubsystem} +
     * {@link AllocatorConsumerSubsystem}. Must be {@code public static} with
     * a public no-arg constructor for {@link java.util.ServiceLoader}.
     */
    public static final class BindingSubsystemProvider implements SubsystemProvider {
        public BindingSubsystemProvider() { /* ServiceLoader-mandated no-arg constructor */ }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return List.of(new MemoryBindingSubsystem(), new AllocatorConsumerSubsystem());
        }

        @Override public String moduleName() { return "BindingTestProvider"; }
    }

    /**
     * {@link SubsystemProvider} exposing only {@link NoBindingsSubsystem}.
     */
    public static final class NoBindingsSubsystemProvider implements SubsystemProvider {
        public NoBindingsSubsystemProvider() { /* ServiceLoader-mandated no-arg constructor */ }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return List.of(new NoBindingsSubsystem());
        }

        @Override public String moduleName() { return "NoBindingsTestProvider"; }
    }

    /**
     * Minimal in-process {@link ConfigProvider} — no I/O, no external deps.
     */
    public static final class StubConfigProvider implements ConfigProvider {
        public StubConfigProvider() { /* ServiceLoader-mandated no-arg constructor */ }

        @Override public Supplier<KernelSettings> kernelSettings() { return KernelSettings::defaults; }
        @Override public Optional<String>  getString(String key)   { return Optional.empty(); }
        @Override public Optional<Integer> getInt(String key)      { return Optional.empty(); }
        @Override public Optional<Long>    getLong(String key)     { return Optional.empty(); }
        @Override public Optional<Boolean> getBoolean(String key)  { return Optional.empty(); }
        @Override public <T> Optional<T>   get(String key, Class<T> type) { return Optional.empty(); }
        @Override public void watch(String file, String key, Consumer<Object> callback) {
            // no-op — hot-reload not required in stub
        }
        @Override public String providerName() { return "StubConfigProvider"; }
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private static KernelBootstrap bootstrapWith(Class<?> subsystemProviderClass) {
        ClassLoader shim = new DualShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        StubConfigProvider.class.getName(),
                        "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                        subsystemProviderClass.getName()
                ));

        return KernelBootstrap.builder()
                .selector(BootstrapSelector.all())
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                .classLoader(shim)
                .build();
    }

    // =========================================================================
    // Test suites
    // =========================================================================

    @Nested
    @DisplayName("buildKernelScope() — orchestrator unit tests")
    class BuildKernelScopeUnit {

        /**
         * Verifies that {@link SubsystemOrchestrator#buildKernelScope()} returns
         * {@code null} when no subsystem registers any bindings.
         */
        @Test
        @DisplayName("Returns null when no subsystem publishes any bindings")
        void returnsNullWhenNoBindings() throws Exception {
            SubsystemOrchestrator orch = buildOrchestratorWith(new NoBindingsSubsystem());
            orch.initialize(minimalConfig());

            assertThat(orch.buildKernelScope())
                    .as("buildKernelScope() must return null when no bindings were collected")
                    .isNull();
        }

        /**
         * Verifies that {@link SubsystemOrchestrator#buildKernelScope()} returns a
         * non-null {@link ScopedValue.Carrier} when a subsystem registers bindings.
         */
        @Test
        @DisplayName("Returns non-null Carrier when a subsystem publishes bindings")
        void returnsCarrierWhenBindingsPresent() throws Exception {
            SubsystemOrchestrator orch = buildOrchestratorWith(new MemoryBindingSubsystem());
            orch.initialize(minimalConfig());

            assertThat(orch.buildKernelScope())
                    .as("buildKernelScope() must return a non-null Carrier when bindings exist")
                    .isNotNull();
        }

        /**
         * Verifies that the {@link ScopedValue.Carrier} returned by
         * {@link SubsystemOrchestrator#buildKernelScope()} actually makes
         * {@link KernelProviders#MEMORY_ALLOCATOR} accessible inside its scope.
         */
        @Test
        @DisplayName("Carrier scope makes MEMORY_ALLOCATOR accessible")
        void carrierScopeBindsMemoryAllocator() throws Exception {
            SubsystemOrchestrator orch = buildOrchestratorWith(new MemoryBindingSubsystem());
            orch.initialize(minimalConfig());

            ScopedValue.Carrier carrier = orch.buildKernelScope();
            AtomicReference<MemoryAllocator> captured = new AtomicReference<>();

            assertThat(carrier)
                    .as("carrier must be non-null before running scope")
                    .isNotNull();
            carrier.run(() -> captured.set(KernelProviders.MEMORY_ALLOCATOR.get()));

            assertThat(captured.get())
                    .as("MEMORY_ALLOCATOR must be the NoOpMemoryAllocator.INSTANCE inside carrier scope")
                    .isSameAs(NoOpMemoryAllocator.INSTANCE);
        }

        // ── helper ──────────────────────────────────────────────────────────

        private static SubsystemOrchestrator buildOrchestratorWith(Subsystem subsystem) {
            SubsystemOrchestratorKahnTest.FixedSubsystemProvider.subsystems = List.of(subsystem);

            ClassLoader shim = new SubsystemOrchestratorKahnTest.SubsystemProviderShimLoader(
                    Thread.currentThread().getContextClassLoader(),
                    SubsystemOrchestratorKahnTest.FixedSubsystemProvider.class.getName());

            return SubsystemOrchestrator.builder()
                    .selector(BootstrapSelector.all())
                    .classLoader(shim)
                    .build();
        }
    }

    @Nested
    @DisplayName("KernelBootstrap.boot() — full end-to-end scope binding")
    class BootEndToEnd {

        /**
         * Core contract: {@link KernelProviders#MEMORY_ALLOCATOR} is bound during
         * a dependent subsystem's {@link Subsystem#start()} call.
         *
         * <p>This is the primary regression test for the ScopedValue binding problem
         * identified in the gap analysis.
         */
        @Test
        @DisplayName("MEMORY_ALLOCATOR is bound during start() of a dependent subsystem")
        void allocatorBoundDuringDependentSubsystemStart() throws Exception {
            AllocatorConsumerSubsystem consumer = new AllocatorConsumerSubsystem();

            // We need to capture the specific instance — override BindingSubsystemProvider
            // to inject our pre-constructed consumer.
            SubsystemOrchestratorKahnTest.FixedSubsystemProvider.subsystems =
                    List.of(new MemoryBindingSubsystem(), consumer);

            ClassLoader shim = new DualShimLoader(
                    Thread.currentThread().getContextClassLoader(),
                    Map.of(
                            "eu.exeris.kernel.spi.config.ConfigProvider",
                            StubConfigProvider.class.getName(),
                            "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                            SubsystemOrchestratorKahnTest.FixedSubsystemProvider.class.getName()
                    ));

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.all())
                    .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                    .classLoader(shim)
                    .build()
                    .boot(() -> { /* no-op — we only need start() to execute */ });

            assertThat(consumer.allocatorBoundDuringStart)
                    .as("MEMORY_ALLOCATOR must be bound during transport.start()")
                    .isTrue();

            assertThat(consumer.capturedAllocator.get())
                    .as("Captured allocator must be the NoOpMemoryAllocator.INSTANCE")
                    .isSameAs(NoOpMemoryAllocator.INSTANCE);
        }

        /**
         * Verifies that {@link KernelProviders#MEMORY_ALLOCATOR} is accessible
         * inside {@code kernelMain} — the application's entry point.
         */
        @Test
        @DisplayName("MEMORY_ALLOCATOR is bound inside kernelMain runnable")
        void allocatorBoundInsideKernelMain() throws Exception {
            AtomicBoolean bound             = new AtomicBoolean(false);
            AtomicReference<MemoryAllocator> captured = new AtomicReference<>();

            bootstrapWith(BindingSubsystemProvider.class)
                    .boot(() -> {
                        bound.set(KernelProviders.MEMORY_ALLOCATOR.isBound());
                        if (bound.get()) {
                            captured.set(KernelProviders.MEMORY_ALLOCATOR.get());
                        }
                    });

            assertThat(bound.get())
                    .as("MEMORY_ALLOCATOR must be bound inside kernelMain")
                    .isTrue();

            assertThat(captured.get())
                    .as("MEMORY_ALLOCATOR value must be NoOpMemoryAllocator.INSTANCE")
                    .isSameAs(NoOpMemoryAllocator.INSTANCE);
        }

        /**
         * Verifies "The Wall": bindings published by subsystems MUST NOT leak
         * outside the {@link KernelBootstrap#boot(Runnable)} scope.
         */
        @Test
        @DisplayName("MEMORY_ALLOCATOR is NOT bound after boot() returns — The Wall holds")
        void allocatorUnboundAfterBoot() throws Exception {
            bootstrapWith(BindingSubsystemProvider.class)
                    .boot(() -> { /* let boot complete */ });

            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound())
                    .as("MEMORY_ALLOCATOR must NOT be bound outside the boot() scope")
                    .isFalse();
        }

        /**
         * Verifies that {@code boot()} completes cleanly when no subsystem publishes
         * any bindings (null carrier path — no unnecessary scope wrapping).
         */
        @Test
        @DisplayName("boot() completes cleanly when no subsystem publishes bindings")
        void bootCompletesWithNoBindings() throws Exception {
            AtomicBoolean ran = new AtomicBoolean(false);

            bootstrapWith(NoBindingsSubsystemProvider.class)
                    .boot(() -> ran.set(true));

            assertThat(ran.get()).isTrue();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
        }
    }

    // =========================================================================
    // Infrastructure — shared helpers
    // =========================================================================

    private static ConfigProvider minimalConfig() {
        return new ConfigProvider() {
            @Override public Supplier<KernelSettings> kernelSettings() { return KernelSettings::defaults; }
            @Override public Optional<String>  getString(String key)   { return Optional.empty(); }
            @Override public Optional<Integer> getInt(String key)      { return Optional.empty(); }
            @Override public Optional<Long>    getLong(String key)     { return Optional.empty(); }
            @Override public Optional<Boolean> getBoolean(String key)  { return Optional.empty(); }
            @Override public <T> Optional<T>   get(String key, Class<T> type) { return Optional.empty(); }
            @Override public void watch(String file, String key, Consumer<Object> callback) {
                // no-op — hot-reload not needed in Core unit tests
            }
            @Override public String providerName() { return "MinimalTestConfig"; }
        };
    }

    /**
     * Injects multiple SPI bindings into the {@link java.util.ServiceLoader} discovery
     * path — same in-memory URL strategy used in {@link KernelBootstrapTest}.
     */
    private static final class DualShimLoader extends ClassLoader {

        private final Map<String, String> resourceMap;

        DualShimLoader(ClassLoader parent, Map<String, String> spiBindings) {
            super(parent);
            Map<String, String> mapped = new HashMap<>();
            spiBindings.forEach((iface, impl) ->
                    mapped.put("META-INF/services/" + iface, impl));
            this.resourceMap = Collections.unmodifiableMap(mapped);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            String impl = resourceMap.get(name);
            return impl != null ? toStream(impl) : super.getResourceAsStream(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            String impl = resourceMap.get(name);
            if (impl != null) {
                return Collections.enumeration(List.of(toInMemoryUrl(name, impl)));
            }
            return super.getResources(name);
        }

        private static InputStream toStream(String content) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        private static URL toInMemoryUrl(String resourcePath, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                return new URL(null, "memory://" + resourcePath,
                        new URLStreamHandler() {
                            @Override
                            protected URLConnection openConnection(URL u) {
                                return new URLConnection(u) {
                                    @Override public void connect() { /* no-op — in-memory, no actual connection */ }
                                    @Override public InputStream getInputStream() {
                                        return new ByteArrayInputStream(bytes);
                                    }
                                };
                            }
                        });
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "DualShimLoader: failed to build in-memory URL for " + resourcePath, ex);
            }
        }
    }
}
