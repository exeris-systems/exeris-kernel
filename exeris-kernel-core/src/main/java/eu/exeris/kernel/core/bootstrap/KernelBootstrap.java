/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents;
import eu.exeris.kernel.core.bootstrap.jfr.KernelStartEvent;
import eu.exeris.kernel.core.config.DynamicConfigFileWatcher;
import eu.exeris.kernel.core.config.KernelConfigRegistry;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * @since 0.5.0
 * @see SubsystemOrchestrator
 * @see KernelProviders#CURRENT_CONFIG
 */
// LawOfDemeter: Builder-pattern field access is idiomatic (builder.field) and
// does not constitute a Demeter violation in this composition-root context.
// AvoidCatchingGenericException: ScopedValue.call() declares 'throws Exception' —
// we must catch the broadest type and re-wrap for callers.
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException"
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
    private final AtomicBoolean                       bootActive = new AtomicBoolean(false);
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
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Boots the kernel and runs {@code kernelMain} within the fully established
     * {@link ScopedValue} scope.
     *
     * <p>On return — whether normal or exceptional — {@link SubsystemOrchestrator#shutdown()}
     * is always called, ensuring no subsystem leaks.
     *
     * <h2>Boot sequence</h2>
     * <ol>
     *   <li>Emit {@code KernelStart} JFR event.</li>
     *   <li>Resolve {@link ConfigProvider} via {@code ServiceLoader}.</li>
     *   <li>Emit {@code ConfigSettingsResolved} JFR event.</li>
     *   <li>Bind {@code CURRENT_CONFIG} and run subsystem bootstrap within the scope.</li>
     *   <li>Call {@code orchestrator.initialize(config)} → Kahn BFS → per-subsystem init.</li>
     *   <li>Call {@code orchestrator.start(config)} → phased parallel start.</li>
     *   <li>Run {@code kernelMain}.</li>
     *   <li>Call {@code orchestrator.shutdown()} in {@code finally}.</li>
     * </ol>
     *
     * @param kernelMain the top-level kernel runnable (your application entry point)
     * @throws BootstrapException if config resolution or subsystem boot fails
     */
    @SuppressWarnings("PMD.CloseResource")
    public void boot(Runnable kernelMain) throws BootstrapException {

        // ── Step 1: Emit KernelStart JFR — the first anchor in the waterfall ─
        KernelStartEvent.emit(KERNEL_VERSION, failurePolicy.name(), selector.toString());

        // ── Step 2: Resolve ConfigProvider via ServiceLoader ──────────────────
        long configStartNanos = System.nanoTime();
        ConfigProvider resolvedConfig = resolveConfigProvider();
        KernelConfigRegistry configRegistry = new KernelConfigRegistry();
        ConfigProvider config = new RegistryBackedConfigProvider(resolvedConfig, configRegistry);
        DynamicConfigFileWatcher configWatcher = DynamicConfigFileWatcher.forRegistry(configRegistry);

        // ── Step 3: Emit ConfigSettingsResolved JFR ───────────────────────────
        // Trigger lazy initialization NOW so the duration is captured in the event.
        String profile = config.kernelSettings().get().profile().toString();
        BootstrapJfrEvents.emitConfigResolved(
                config.providerName(), profile, configStartNanos, "serviceloader");

        // ── Step 4: Build the orchestrator ────────────────────────────────────
        SubsystemOrchestrator orchestrator = SubsystemOrchestrator.builder()
                .failurePolicy(failurePolicy)
                .selector(selector)
                .classLoader(classLoader)
                .build();
        activeOrchestrator = orchestrator;
        bootActive.set(true);

        // ── Step 5: Bind CURRENT_CONFIG and run the full boot inside the scope ─
        //
        // From this point every virtual thread spawned in the kernel scope
        // inherits KernelProviders.CURRENT_CONFIG automatically — zero arg-threading,
        // zero ThreadLocal (banned), zero static singletons.
        try {
            ScopedValue.where(KernelProviders.CURRENT_CONFIG, config)
                    .call(() -> {
                        runBootInsideScope(orchestrator, config, configRegistry, configWatcher, kernelMain);
                        return null;
                    });
        } catch (SubsystemCircularDependencyException ex) {
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
     * Returns the live kernel health monitor while boot/runtime is active.
     *
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

        public BootstrapException(String message) {
            super(message);
        }

        public BootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /** Creates a new {@link Builder} for {@link KernelBootstrap}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link KernelBootstrap}.
     *
     * <p>Minimal example:
     * <pre>{@code
     * KernelBootstrap.builder()
     *     .selector(BootstrapSelector.all())
     *     .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
     *     .build()
     *     .boot(myApp::run);
     * }</pre>
     */
    public static final class Builder {

        private SubsystemOrchestrator.FailurePolicy failurePolicy =
                SubsystemOrchestrator.FailurePolicy.FAIL_FAST;
        private BootstrapSelector selector   = BootstrapSelector.all();
        private ClassLoader       classLoader;

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
