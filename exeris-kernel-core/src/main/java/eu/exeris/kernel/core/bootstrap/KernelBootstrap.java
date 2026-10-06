/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents;
import eu.exeris.kernel.core.bootstrap.jfr.KernelStartEvent;
import eu.exeris.kernel.core.config.DynamicConfigFileWatcher;
import eu.exeris.kernel.core.config.KernelConfigRegistry;
import eu.exeris.kernel.core.contract.ContractBootstrapStep;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Core: Kernel bootstrap entry point — the "Ignition Switch".
 *
 * <h2>Architecture</h2>
 * <p>This class is the outermost shell of the Exeris boot sequence. Its sole
 * responsibility is to:
 * <ol>
 *   <li>Emit the {@code KernelStart} JFR event — the first anchor in the boot waterfall.</li>
 *   <li>Discover the highest-priority {@link ConfigProvider} via {@link ServiceLoader}.</li>
 *   <li>Emit {@code ConfigSettingsResolved} JFR event.</li>
 *   <li>Bind the resolved config to {@link KernelProviders#CURRENT_CONFIG} via
 *       {@link ScopedValue} — establishing the immutable kernel context scope.</li>
 *   <li>Hand off to {@link SubsystemOrchestrator} which handles DAG resolution,
 *       Kahn's BFS, phased init, parallel start, and shutdown.</li>
 * </ol>
 *
 * <h2>ScopedValue Barrier (The Wall)</h2>
 * <p>Every subsystem, every handler, every virtual thread spawned within
 * {@link #boot(Runnable)} inherits {@link KernelProviders#CURRENT_CONFIG}
 * automatically — no constructor injection, no {@code ThreadLocal}, no static
 * singletons. The scope is torn down when {@code boot()} returns.
 *
 * <h2>JFR Waterfall</h2>
 * <pre>
 *   KernelStart                  ← emitted first (this class)
 *   ConfigSettingsResolved       ← after ServiceLoader picks ConfigProvider
 *   SubsystemInitialized × N    ← per subsystem, inside SubsystemOrchestrator
 *   SubsystemStarted × N        ← per subsystem, inside SubsystemOrchestrator
 *   KernelBootReady              ← all subsystems RUNNING (SubsystemOrchestrator)
 *   KernelShutdownComplete       ← after shutdown() (SubsystemOrchestrator)
 * </pre>
 *
 * <h2>Zero Magic DI</h2>
 * <p>No Spring, no CDI, no Guice. The orchestrator is wired via the builder
 * pattern; providers are loaded via {@link ServiceLoader}.
 *
 * @since 0.5
 * @see SubsystemOrchestrator
 * @see KernelProviders#CURRENT_CONFIG
 */
// LawOfDemeter: Builder-pattern field access is idiomatic (builder.field) and
// does not constitute a Demeter violation in this composition-root context.
// AvoidCatchingGenericException: ScopedValue.call() declares 'throws Exception' —
// we must catch the broadest type and re-wrap for callers.
// TooManyMethods / CyclomaticComplexity: this is the composition root of the boot sequence; every step
// (config, contract gate, orchestrator, scope, shutdown) is one small method, and their sum is the count.
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.TooManyMethods",
    "PMD.CyclomaticComplexity"
})
public final class KernelBootstrap {

    /**
     * Kernel artifact version — emitted in JFR events for traceability.
     *
     * <p>Sourced from the Maven-filtered {@code exeris-kernel.properties}
     * ({@code kernel.version=${project.version}}) so the stamp never drifts from
     * the POM. Falls back to {@code "unknown"} if the resource is absent or its
     * placeholder was not filtered (e.g. classes loaded outside a Maven build).
     */
    private static final String KERNEL_VERSION = KernelVersion.current();

    private final SubsystemOrchestrator.FailurePolicy failurePolicy;
    private final BootstrapSelector                   selector;
    private final ClassLoader                         classLoader;
    private final BiFunction<ConfigProvider, ClassLoader, ExecutionContract> contractGate;
    private final AtomicBoolean                       bootActive = new AtomicBoolean(false);
    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile SubsystemOrchestrator activeOrchestrator;

    // =========================================================================
    // Constructor (Zero-Magic DI — pure constructor, no Spring, no CDI)
    // =========================================================================

    private KernelBootstrap(Builder builder) {
        this.failurePolicy = builder.failurePolicy;
        this.selector      = builder.selector;
        this.classLoader   = builder.classLoader != null
                ? builder.classLoader
                : Thread.currentThread().getContextClassLoader();
        this.contractGate  = builder.contractGate;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Boots the kernel and runs {@code kernelMain} within the fully established
     * {@link ScopedValue} scope.
     *
     * <p>On return — whether normal or exceptional — {@link SubsystemOrchestrator#shutdown()}
     * is always called.
     *
     * <h4>Boot sequence</h4>
     * <ol>
     *   <li>Emit {@code KernelStart} JFR event.</li>
     *   <li>Resolve {@link ConfigProvider} via {@code ServiceLoader}.</li>
     *   <li>Emit {@code ConfigSettingsResolved} JFR event.</li>
     *   <li>Run the Phase 0 contract gate ({@code ContractBootstrapStep}, ADR-088 / ADR-089) before any
     *       subsystem is initialized; a breach propagates as {@link ContractBreachException}.</li>
     *   <li>Bind {@code CURRENT_CONFIG} and {@code EXECUTION_CONTRACT} and run subsystem bootstrap within
     *       the scope.</li>
     *   <li>Call {@code orchestrator.initialize(config)} → Kahn BFS → per-subsystem init.</li>
     *   <li>Call {@code orchestrator.start(config)} → phased parallel start.</li>
     *   <li>Run {@code kernelMain}.</li>
     *   <li>Call {@code orchestrator.shutdown()} in {@code finally}.</li>
     * </ol>
     *
     * @param kernelMain the top-level kernel runnable (your application entry point)
     * @throws BootstrapException if config resolution or subsystem boot fails
     * @throws ContractBreachException if the Phase 0 contract gate refuses the boot, before any subsystem
     *                                 is initialized,
     *                                 with {@code EX-LIC-0001} (a manifest that cannot be read or fails the
     *                                 schema or its signature), {@code EX-LIC-0002} (unknown issuer key),
     *                                 {@code EX-LIC-0003} / {@code EX-LIC-0006} (outside its validity window),
     *                                 {@code EX-LIC-0004} / {@code EX-LIC-0007} (a {@code HARD} capability or
     *                                 environment breach), {@code EX-LIC-0005} (a production class with a
     *                                 declared requirement and no manifest) or {@code EX-LIC-0008} (an
     *                                 {@code EntitlementRequirement} that breaks its contract)
     * @throws ConfigProvider.ConfigProviderException with {@code EX-CFG-1002} if the {@code environment}
     *                                 configuration key names none of the six environment classes
     */
    public void boot(Runnable kernelMain) throws BootstrapException {
        runKernel(true, kernelMain);
    }

    /**
     * Boots the kernel for <em>read-only static introspection</em> and runs {@code inspector}.
     *
     * <p>Resolves config and the orchestrator like {@link #boot(Runnable)}, then
     * {@link SubsystemOrchestrator#resolveTopology(ConfigProvider)} loads + selector-filters +
     * topologically-sorts the subsystem inventory and binds it to {@link KernelProviders#SUBSYSTEMS} —
     * <b>without calling {@code Subsystem.initialize()} or {@code start()}</b>. No infrastructure is
     * touched (no DB drivers, ports, or native libraries), so the {@code KernelDiagnostics} SPI (ADR-033)
     * can describe the static composition of <em>any</em> kernel build, infra-free. Provider discovery is
     * done by the diagnostics provider via {@link java.util.ServiceLoader}, independent of this scope.
     * {@code isRunning()} reports {@code false} for every subsystem — the honest answer for a static
     * composition snapshot. The Phase 0 contract gate does not run, because nothing is initialized:
     * {@link KernelProviders#EXECUTION_CONTRACT} is unbound inside {@code inspector}.
     *
     * @param inspector the read-only introspection runnable
     * @throws BootstrapException if config resolution or topology resolution fails
     */
    public void inspect(Runnable inspector) throws BootstrapException {
        runKernel(false, inspector);
    }

    private void runKernel(boolean fullBoot, Runnable body) throws BootstrapException {

        // ── Step 1: Emit KernelStart JFR — the first anchor in the waterfall ─
        KernelStartEvent.emit(KERNEL_VERSION, failurePolicy.name(), selector.toString());

        // ── Step 2: Resolve ConfigProvider via ServiceLoader ──────────────────
        long configStartNanos = System.nanoTime();
        ConfigProvider resolvedConfig = resolveConfigProvider();
        KernelConfigRegistry configRegistry = new KernelConfigRegistry();
        ConfigProvider config = new RegistryBackedConfigProvider(resolvedConfig, configRegistry);

        // ── Step 3: Emit ConfigSettingsResolved JFR ───────────────────────────
        // Trigger lazy initialization NOW so the duration is captured in the event.
        String profile = config.kernelSettings().get().profile().toString();
        BootstrapJfrEvents.emitConfigResolved(
                config.providerName(), profile, configStartNanos, "serviceloader");

        // ── Step 4: Phase 0 contract gate (ADR-088, ADR-089) ──────────────────
        // The execution contract is decided before any subsystem is initialized, so a breach leaves
        // no memory allocated and no socket bound. inspect() initializes nothing, so it neither runs
        // the gate nor binds a contract.
        ScopedValue.Carrier scope = kernelScope(config, fullBoot);

        // ── Step 5: Build the orchestrator ────────────────────────────────────
        SubsystemOrchestrator orchestrator = SubsystemOrchestrator.builder()
                .failurePolicy(failurePolicy)
                .selector(selector)
                .classLoader(classLoader)
                .build();
        activeOrchestrator = orchestrator;
        bootActive.set(true);

        // ── Step 6: Bind CURRENT_CONFIG and EXECUTION_CONTRACT inside the scope
        //
        // From this point every virtual thread spawned in the kernel scope
        // inherits KernelProviders.CURRENT_CONFIG and EXECUTION_CONTRACT
        // automatically — zero arg-threading, zero ThreadLocal, zero static singletons.
        try {
            scope.call(() -> {
                if (fullBoot) {
                    DynamicConfigFileWatcher configWatcher =
                            DynamicConfigFileWatcher.forRegistry(configRegistry);
                    runBootInsideScope(orchestrator, config, configRegistry, configWatcher, body);
                } else {
                    runInspectInsideScope(orchestrator, config, body);
                }
                return null;
            });
        } catch (SubsystemCircularDependencyException | ContractBreachException ex) {
            throw ex;
        } catch (SubsystemOrchestrator.BootstrapException ex) {
            throw new BootstrapException("Subsystem bootstrap failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new BootstrapException("Unexpected failure during kernel boot", ex);
        } finally {
            bootActive.set(false);
        }
    }

    /**
     * Builds the kernel scope: {@code CURRENT_CONFIG}, and for a full boot the execution contract the
     * Phase 0 contract gate decides (ADR-088 / ADR-089) before any subsystem is initialized.
     *
     * @param config   active configuration provider
     * @param fullBoot {@code false} for {@link #inspect(Runnable)}, which runs no gate and binds no contract
     * @return the scope carrier
     * @throws ContractBreachException if the gate refuses the boot, with an {@code EX-LIC} code as
     *                                 {@link ContractBootstrapStep#run} lists them
     * @throws ConfigProvider.ConfigProviderException with {@code EX-CFG-1002} for an unknown environment
     */
    private ScopedValue.Carrier kernelScope(ConfigProvider config, boolean fullBoot) {
        ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.CURRENT_CONFIG, config);
        return fullBoot
                ? scope.where(KernelProviders.EXECUTION_CONTRACT, contractGate.apply(config, classLoader))
                : scope;
    }

    /**
     * Returns the live kernel health monitor while boot/runtime is active.
     *
     * @return the health monitor of the currently active boot
     * @throws IllegalStateException when bootstrap is not currently active
     */
    public KernelHealthMonitor healthMonitor() {
        SubsystemOrchestrator orchestrator = activeOrchestrator;
        if (!bootActive.get() || orchestrator == null) {
            throw new IllegalStateException("KernelBootstrap is not active; no health monitor available.");
        }
        return orchestrator.healthMonitor();
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Executes within the {@code CURRENT_CONFIG} scope.
     * Guarantees {@code orchestrator.shutdown()} is always called.
     *
     * <h2>Provider Binding Protocol (Option A — ScopedValue.Carrier)</h2>
     * <ol>
     *   <li><b>Phase A — initialize:</b> {@link SubsystemOrchestrator#initialize(ConfigProvider)}
     *       calls each {@link eu.exeris.kernel.spi.bootstrap.Subsystem#initialize()} in
     *       topological order and collects {@link eu.exeris.kernel.spi.bootstrap.Subsystem#providerBindings()}
     *       into an internal map.</li>
     *   <li><b>Phase B — build scope:</b> {@link SubsystemOrchestrator#buildKernelScope()} assembles
     *       a {@link ScopedValue.Carrier} from all collected bindings (e.g.
     *       {@link KernelProviders#MEMORY_ALLOCATOR}).</li>
     *   <li><b>Phase C — enriched start:</b> If bindings exist, both
     *       {@link SubsystemOrchestrator#start(ConfigProvider)} and {@code kernelMain}
     *       execute <em>inside</em> the enriched carrier — every virtual thread spawned
     *       hereafter can call {@link KernelProviders#allocator()} without argument threading.</li>
     *   <li><b>Phase D — shutdown:</b> Always in reverse-topological order, in
     *       the {@code finally} block, regardless of outcome.</li>
     * </ol>
     *
     * <h2>Checked exception propagation through Carrier.run()</h2>
     * <p>{@link ScopedValue.Carrier#run(Runnable)} accepts a {@link Runnable} which
     * cannot declare checked exceptions. {@link SubsystemOrchestrator.BootstrapException}
     * is checked, so it is wrapped in an unchecked carrier and re-thrown after the scope.
     */
    private static void runBootInsideScope(SubsystemOrchestrator orchestrator,
                                           ConfigProvider config,
                                           KernelConfigRegistry configRegistry,
                                           DynamicConfigFileWatcher configWatcher,
                                           Runnable kernelMain)
            throws SubsystemOrchestrator.BootstrapException {
        try {
            // Phase A: initialize all subsystems — providerBindings() collected internally
            orchestrator.initialize(config);
            configRegistry.seal();
            startConfigWatcher(configWatcher);

            // Phase B: build the enriched ScopedValue.Carrier from all collected bindings
            ScopedValue.Carrier kernelScope = orchestrator.buildKernelScope();

            if (kernelScope == null) {
                // No provider bindings — run start() and kernelMain in the current scope
                orchestrator.start(config);
                kernelMain.run();
            } else {
                // Phase C: execute start() and kernelMain inside the enriched scope.
                // Every VT spawned from here inherits MEMORY_ALLOCATOR, MEMORY_PROVIDER, etc.
                //
                // Carrier.run(Runnable) cannot propagate checked exceptions — wrap and re-throw.
                BootstrapExceptionHolder holder = new BootstrapExceptionHolder();
                kernelScope.run(() -> {
                    try {
                        orchestrator.start(config);
                        kernelMain.run();
                    } catch (SubsystemOrchestrator.BootstrapException ex) {
                        holder.exception = ex;
                    }
                });
                if (holder.exception != null) {
                    throw holder.exception;
                }
            }
        } finally {
            closeConfigWatcher(configWatcher);
            // Phase D: always shut down in reverse-topological order
            orchestrator.shutdown();
        }
    }

    /**
     * Read-only counterpart of {@link #runBootInsideScope}: resolves the subsystem topology
     * (no {@code initialize()} / {@code start()}), binds {@link KernelProviders#SUBSYSTEMS}, and runs the
     * inspector inside that scope. Nothing is initialized, so there is nothing to shut down.
     */
    private static void runInspectInsideScope(SubsystemOrchestrator orchestrator,
                                              ConfigProvider config,
                                              Runnable inspector)
            throws SubsystemOrchestrator.BootstrapException {
        List<Subsystem> subsystems = orchestrator.resolveTopology(config);
        ScopedValue.where(KernelProviders.SUBSYSTEMS, subsystems).run(inspector);
    }

    private static void startConfigWatcher(DynamicConfigFileWatcher configWatcher)
            throws SubsystemOrchestrator.BootstrapException {
        if (configWatcher == null) {
            return;
        }
        try {
            configWatcher.start();
        } catch (IOException ex) {
            throw new SubsystemOrchestrator.BootstrapException(
                    "Failed to start dynamic config watcher", ex);
        }
    }

    private static void closeConfigWatcher(DynamicConfigFileWatcher configWatcher) {
        if (configWatcher != null) {
            configWatcher.close();
        }
    }

    /**
     * Single-field mutable carrier for checked exceptions escaping
     * {@link ScopedValue.Carrier#run(Runnable)}.
     *
     * <p>Created once per boot path — not on any hot path.
     * Not a {@code record} because it needs a mutable field.
     */
    private static final class BootstrapExceptionHolder {
        /* default */ SubsystemOrchestrator.BootstrapException exception;
    }

    private static final class RegistryBackedConfigProvider implements ConfigProvider {

        private final ConfigProvider delegate;
        private final KernelConfigRegistry registry;

        private RegistryBackedConfigProvider(ConfigProvider delegate,
                                             KernelConfigRegistry registry) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        @Override
        public Supplier<KernelSettings> kernelSettings() {
            return delegate.kernelSettings();
        }

        @Override
        public Optional<String> getString(String key) {
            return delegate.getString(key);
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return delegate.getInt(key);
        }

        @Override
        public Optional<Long> getLong(String key) {
            return delegate.getLong(key);
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            return delegate.getBoolean(key);
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            return delegate.get(key, type);
        }

        @Override
        public void watch(String file, String key, Consumer<Object> callback) {
            registry.register(file, key, callback::accept);
        }

        @Override
        public void guardImmutable(String file, String key) {
            registry.registerImmutable(file, key);
        }

        @Override
        public int priority() {
            return delegate.priority();
        }

        @Override
        public String providerName() {
            return delegate.providerName();
        }
    }

    /**
     * Resolves the highest-priority {@link ConfigProvider} from the classpath.
     * Throws {@link BootstrapException} if none is found — this is a hard L0 error.
     */
    private ConfigProvider resolveConfigProvider() throws BootstrapException {
        return ServiceLoader.load(ConfigProvider.class, classLoader)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(ConfigProvider::priority))
                .orElseThrow(() -> new BootstrapException(
                        "No ConfigProvider found on classpath. "
                        + "Add exeris-kernel-community (SimpleFileConfigProvider) "
                        + "or exeris-kernel-enterprise to the runtime classpath. "
                        + "[EX-CFG-0001]"));
    }

    // =========================================================================
    // BootstrapException
    // =========================================================================

    /**
     * Thrown when the kernel boot sequence fails unrecoverably.
     *
     * <p>Wraps both {@link SubsystemOrchestrator.BootstrapException} and any
     * unexpected runtime exceptions so callers have a single catch clause.
     */
    public static final class BootstrapException extends Exception {

        /**
         * Creates the exception with {@code message} and no cause.
         *
         * @param message failure detail message
         */
        public BootstrapException(String message) {
            super(message);
        }

        /**
         * Creates the exception with {@code message} and the underlying {@code cause}.
         *
         * @param message failure detail message
         * @param cause   the underlying failure
         */
        public BootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Creates a new {@link Builder} for {@link KernelBootstrap}.
     *
     * @return a new builder with default settings
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link KernelBootstrap}.
     *
     * <p>Minimal example:
     * {@snippet lang="java" :
     * KernelBootstrap.builder()
     *     .selector(BootstrapSelector.all())
     *     .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
     *     .build()
     *     .boot(myApp::run);
     * }
     */
    public static final class Builder {

        private SubsystemOrchestrator.FailurePolicy failurePolicy =
                SubsystemOrchestrator.FailurePolicy.FAIL_FAST;
        private BootstrapSelector selector   = BootstrapSelector.all();
        private ClassLoader       classLoader;
        private BiFunction<ConfigProvider, ClassLoader, ExecutionContract> contractGate = ContractBootstrapStep::run;

        /**
         * Creates a builder with every setting at its default.
         *
         * <p>Obtain one through {@link KernelBootstrap#builder()} rather than directly; the factory is the
         * documented entry point and this constructor exists only because the class is public.
         */
        public Builder() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        /**
         * Sets the failure policy (default: {@link SubsystemOrchestrator.FailurePolicy#FAIL_FAST}).
         *
         * @param policy failure policy
         * @return this builder
         */
        public Builder failurePolicy(SubsystemOrchestrator.FailurePolicy policy) {
            this.failurePolicy = Objects.requireNonNull(policy, "failurePolicy");
            return this;
        }

        /**
         * Sets which subsystems to activate (default: {@link BootstrapSelector#all()}).
         *
         * @param sel bootstrap selector
         * @return this builder
         */
        public Builder selector(BootstrapSelector sel) {
            this.selector = Objects.requireNonNull(sel, "selector");
            return this;
        }

        /**
         * Sets the {@link ClassLoader} for {@link ServiceLoader} discovery
         * (default: current thread context class loader).
         *
         * @param loader class loader
         * @return this builder
         */
        public Builder classLoader(ClassLoader loader) {
            this.classLoader = loader;
            return this;
        }

        /**
         * Replaces the contract gate. Package-private: only code in the bootstrap package can reach it, so a
         * kernel built outside this package always runs {@link ContractBootstrapStep#run}.
         *
         * @param gate decides the execution contract from the configuration and class loader
         * @return this builder
         */
        /* default */ Builder contractGate(BiFunction<ConfigProvider, ClassLoader, ExecutionContract> gate) {
            this.contractGate = Objects.requireNonNull(gate, "contractGate");
            return this;
        }

        /**
         * Builds the {@link KernelBootstrap} instance. Does not start or
         * initialize anything — call {@link KernelBootstrap#boot(Runnable)}.
         *
         * @return configured bootstrapper
         */
        public KernelBootstrap build() {
            return new KernelBootstrap(this);
        }
    }
}
