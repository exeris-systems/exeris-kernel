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
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Core: Subsystem lifecycle orchestrator — Kahn's topological sort, sequential init + phase-grouped parallel start.
 *
 * <h2>Zero External Dependencies (L0 Mandate)</h2>
 * <p>This class uses {@link System.Logger} (JEP 264, JDK 9+) for all logging.
 * No SLF4J, no Logback, no Log4j — L0 pulls no external logging framework.
 * The JDK routes {@code System.Logger} calls to whatever backend is on the
 * classpath at runtime (SLF4J bridge, JUL, etc.), preserving full flexibility
 * for the implementor without coupling the kernel itself.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li><b>Discovery:</b> Loads all {@link SubsystemProvider} implementations via
 *       {@link ServiceLoader}, sorted by priority descending. Higher-priority provider
 *       wins on name collision (Enterprise shadows Community).</li>
 *   <li><b>Closure:</b> Expands the {@link BootstrapSelector} to its full transitive
 *       dependency closure via BFS — requesting {@code "persistence"} automatically
 *       pulls in {@code "memory"} and {@code "telemetry"}.</li>
 *   <li><b>Topological Sort:</b> Kahn's BFS algorithm, O(V+E). Detects cycles and
 *       throws {@link SubsystemCircularDependencyException} immediately — FAIL_FAST
 *       with no recovery, no degradation, JVM halts.</li>
 *   <li><b>Lifecycle:</b> Initialization is sequential in topological order; start is
 *       grouped by phase with FOUNDATION sequential and SERVICES/RUNTIME parallel via
 *       {@link StructuredTaskScope} (JEP 525).</li>
 *   <li><b>Reverse Shutdown:</b> Always the strict reverse of topological init order.</li>
 *   <li><b>JFR Telemetry:</b> Every init/start/stop/boot-ready/shutdown event is
 *       emitted via {@link BootstrapJfrEvents}.</li>
 * </ol>
 *
 * <h2>FailurePolicy</h2>
 * <ul>
 *   <li>{@link FailurePolicy#FAIL_FAST} — any failure aborts boot immediately.</li>
 *   <li>{@link FailurePolicy#DEGRADE} — optional subsystems ({@link Subsystem#isOptional()})
 *       are skipped on failure; {@link BootstrapPhase#FOUNDATION} subsystems are always
 *       mandatory regardless.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is single-use per kernel lifecycle. {@link AtomicBoolean} guards
 * are used instead of {@code volatile} double-checked locking (banned) or
 * {@code synchronized} (banned).
 *
 * @since 0.5.0
 * @see SubsystemCircularDependencyException
 * @see BootstrapJfrEvents
 */
// CouplingBetweenObjects, TooManyMethods, CyclomaticComplexity: inherent to a lifecycle
// orchestrator that coordinates discovery, topological sort, phased init, and shutdown.
// Extracting further would create leaky micro-classes with no independent value.
@SuppressWarnings({
    "PMD.CouplingBetweenObjects",
    "PMD.TooManyMethods",
    "PMD.CyclomaticComplexity",
    "PMD.LawOfDemeter"
})
public final class SubsystemOrchestrator {

    /**
     * Zero-dependency JDK logger (JEP 264).
     * L0 mandates no external logging framework — System.Logger delegates to
     * whatever backend the runtime provides (SLF4J bridge, JUL, etc.).
     */
    private static final System.Logger LOG =
            System.getLogger(SubsystemOrchestrator.class.getName());

    // =========================================================================
    // 🜁 ANSI ENTROPY PALETTE
    //
    // Used exclusively on fatal / degraded error paths — paths that either
    // halt the JVM or emit a definitive warning. Zero cost on the happy path.
    // ANSI codes are safe to embed in System.Logger strings; the terminal
    // interprets them. Non-ANSI consoles (e.g., plain file appenders) will
    // show the raw escape sequences — acceptable for a fatal kernel error.
    // =========================================================================

    /** Resets all ANSI attributes. */
    private static final String E_RESET  = "\u001B[0m";

    /** Deep purple — primary entropy colour. */
    private static final String E_PURPLE = "\u001B[38;5;93m";

    /** Neon cyan — secondary entropy colour for Zalgo / glitch text. */
    private static final String E_CYAN   = "\u001B[38;5;51m";

    /** Terminal blink — strobes the header on supported terminals (iTerm2, IntelliJ). */
    private static final String E_BLINK  = "\u001B[5m";

    /** Bold weight. */
    private static final String E_BOLD   = "\u001B[1m";

    // =========================================================================
    // FailurePolicy
    // =========================================================================

    /**
     * Controls what happens when a subsystem fails during initialize() or start().
     */
    public enum FailurePolicy {
        /**
         * Any failure aborts the bootstrap immediately — regardless of
         * {@link Subsystem#isOptional()}. Recommended for production.
         */
        FAIL_FAST,

        /**
         * Optional subsystems ({@link Subsystem#isOptional()} = true) that fail
         * are skipped; the kernel continues without them.
         * Non-optional and FOUNDATION subsystems still abort.
         * Recommended for dev/canary environments.
         */
        DEGRADE
    }

    // =========================================================================
    // State — single-use per lifecycle
    // =========================================================================

    private final FailurePolicy     failurePolicy;
    private final BootstrapSelector selector;
    private final ClassLoader       classLoader;
    private final KernelHealthMonitor healthMonitor = new KernelHealthMonitor();
    private final Object orderedSubsystemsLock = new Object();

    /** Topologically sorted active subsystems — populated by initialize(). */
    private final List<Subsystem> orderedSubsystems = new ArrayList<>();

    /**
     * Composed enricher function accumulated from {@link Subsystem#providerBindings()} after
     * each successful {@link Subsystem#initialize()} call.
     *
     * <p>Each subsystem's operator is composed via {@link UnaryOperator#andThen(java.util.function.Function)}
     * in topological order. The result is a single pure function that, when applied to
     * a base {@link ScopedValue.Carrier}, produces a fully enriched carrier containing
     * every provider binding in the correct order.
     *
     * <p>No casts, no wildcards, no {@code @SuppressWarnings} — type safety is enforced
     * by the compiler at each {@code .where(ScopedValue<T>, T)} call site inside the
     * subsystem's own lambda.
     *
     * <p>Access is single-threaded within the {@link #initialize(ConfigProvider)} loop.
     */
    private UnaryOperator<ScopedValue.Carrier> composedEnricher = carrier -> carrier;

    /**
     * {@code true} if at least one subsystem registered a non-identity
     * {@link Subsystem#providerBindings()} enricher during {@link #initialize(ConfigProvider)}.
     * Used by {@link #buildKernelScope()} to avoid wrapping in a scope when there is nothing to bind.
     */
    private boolean hasBindings;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean started     = new AtomicBoolean(false);
    private final AtomicBoolean terminated  = new AtomicBoolean(false);
    private volatile long bootStartNanos;

    // =========================================================================
    // Constructor (Zero-Magic DI — pure constructor, no Spring, no CDI)
    // =========================================================================

    private SubsystemOrchestrator(Builder builder) {
        this.failurePolicy = builder.failurePolicy;
        this.selector      = builder.selector;
        this.classLoader   = builder.classLoader != null
                ? builder.classLoader
                : Thread.currentThread().getContextClassLoader();
    }

    // =========================================================================
    // Public lifecycle API
    // =========================================================================

    /**
     * Phase 1 — discovers providers, resolves closure, topologically sorts, and calls
     * {@link Subsystem#initialize()} on each active subsystem in order.
     *
     * <p>FOUNDATION subsystems are initialized sequentially.
     * SERVICES and RUNTIME subsystems within the same phase may be parallelized
     * if their individual dependency graph allows — this method initializes them
     * sequentially to preserve the topo-sort order. Parallel <em>start()</em>
     * happens in {@link #start(ConfigProvider)}.
     *
     * @param config the active kernel config (bound to CURRENT_CONFIG before this call)
     * @throws SubsystemCircularDependencyException if a dependency cycle is detected
     *         (FAIL_FAST — cannot be suppressed)
     * @throws BootstrapException on any other unrecoverable failure
     */
    public void initialize(ConfigProvider config) throws BootstrapException {
        if (terminated.get()) {
            throw new BootstrapException("SubsystemOrchestrator cannot be reused after shutdown()");
        }
        if (!initialized.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "SubsystemOrchestrator already initialized — skipping");
            return;
        }

        bootStartNanos = System.nanoTime();
        healthMonitor.reset();
        LOG.log(System.Logger.Level.INFO,
                "╔══════════════════════════════════════════════╗");
        LOG.log(System.Logger.Level.INFO,
                "║    Exeris Subsystem Orchestrator (L0)       ║");
        LOG.log(System.Logger.Level.INFO,
                "╚══════════════════════════════════════════════╝");
        LOG.log(System.Logger.Level.INFO,
                "Selector={0}, FailurePolicy={1}", selector, failurePolicy);

        KernelProfile profile = config.kernelSettings().get().profile();

        // 1. Build registry from all SubsystemProviders (priority-sorted)
        Map<String, Subsystem> registry = buildRegistry(config);
        LOG.log(System.Logger.Level.INFO,
                "{0} subsystem(s) in registry: {1}", registry.size(), registry.keySet());

        // 2. Apply selector — compute transitive closure
        List<Subsystem> selected = applySelector(registry);
        LOG.log(System.Logger.Level.INFO,
                "Selector resolved {0} subsystem(s): {1}",
                selected.size(),
                selected.stream().map(Subsystem::name).collect(java.util.stream.Collectors.joining(", ")));

        // 3. Topological sort — Kahn's BFS, throws on cycle
        topologicalSort(selected);

        for (Subsystem subsystem : orderedSubsystems) {
            boolean required = subsystem.phase() == BootstrapPhase.FOUNDATION || !subsystem.isOptional();
            healthMonitor.registerSubsystem(subsystem.name(), required);
        }

        // 4. Initialize in topological order
        for (Subsystem subsystem : new ArrayList<>(orderedSubsystems)) {
            doInitialize(subsystem, profile);
        }

        LOG.log(System.Logger.Level.INFO,
                "INITIALIZED — {0} subsystem(s) in {1} ms",
                orderedSubsystems.size(), elapsedMs(bootStartNanos));
        healthMonitor.markKernelState(KernelHealthMonitor.KernelState.INITIALIZED);
    }

    /**
     * Phase 2 — calls {@link Subsystem#start()} grouped by {@link BootstrapPhase}.
     * FOUNDATION starts sequentially; SERVICES and RUNTIME start in parallel via
     * {@link StructuredTaskScope}.
     *
     * @param config the active kernel config
     * @throws BootstrapException if any mandatory subsystem fails to start
     */
    public void start(ConfigProvider config) throws BootstrapException {
        if (terminated.get()) {
            throw new BootstrapException("SubsystemOrchestrator cannot be reused after shutdown()");
        }
        if (!initialized.get()) {
            throw new BootstrapException("initialize() must be called before start()");
        }
        if (!started.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "SubsystemOrchestrator already started — skipping");
            return;
        }

        long startNanos = System.nanoTime();
        KernelProfile profile = config.kernelSettings().get().profile();
        String profileName  = profile.toString();
        LOG.log(System.Logger.Level.INFO,
                "Starting {0} subsystem(s)", orderedSubsystems.size());

        for (BootstrapPhase phase : BootstrapPhase.values()) {
            List<Subsystem> forPhase = orderedSubsystems.stream()
                    .filter(s -> s.phase() == phase)
                    .toList();
            if (forPhase.isEmpty()) {
                continue;
            }

            if (phase == BootstrapPhase.FOUNDATION) {
                startSequential(forPhase, phase, profileName);
            } else {
                startParallel(forPhase, phase, profileName);
            }
        }

        LOG.log(System.Logger.Level.INFO, "STARTED in {0} ms", elapsedMs(startNanos));
        healthMonitor.markKernelState(KernelHealthMonitor.KernelState.STARTED);
        BootstrapJfrEvents.emitBootReady(bootStartNanos, orderedSubsystems.size(), profile,
                System.getProperty("exeris.node.id", "local"),
                selector.toString());
    }

    /**
     * Graceful shutdown — stops all running subsystems in strict reverse topological
     * order. Never throws — exceptions are logged as WARNING.
     */
    public void shutdown() {
        if (terminated.get()) {
            return;
        }
        if (!initialized.get() && !started.get()) {
            return;
        }

        terminated.set(true);
        long shutdownStartNanos = System.nanoTime();
        int count = orderedSubsystems.size();
        healthMonitor.markKernelState(KernelHealthMonitor.KernelState.SHUTTING_DOWN);
        LOG.log(System.Logger.Level.INFO, "Shutting down {0} subsystem(s)", count);

        List<Subsystem> reversed = new ArrayList<>(orderedSubsystems);
        Collections.reverse(reversed);

        for (Subsystem subsystem : reversed) {
            if (subsystem.isRunning()) {
                long stopStartNanos = System.nanoTime();
                LOG.log(System.Logger.Level.INFO, "  Stopping [{0}]", subsystem.name());
                try {
                    subsystem.stop();
                    BootstrapJfrEvents.emitStopped(subsystem.name(), stopStartNanos);
                    healthMonitor.markSubsystemState(subsystem.name(), KernelHealthMonitor.SubsystemState.STOPPED);
                } catch (RuntimeException ex) { // NOPMD — stop() must never throw; swallowed by design
                    LOG.log(System.Logger.Level.WARNING,
                            "  [{0}] stop() threw — ignoring: {1}",
                            subsystem.name(), ex.getMessage());
                }
            }
        }

        started.set(false);
        initialized.set(false);
        BootstrapJfrEvents.emitShutdownComplete(shutdownStartNanos, count);
        LOG.log(System.Logger.Level.INFO, "SHUTDOWN complete in {0} ms",
                elapsedMs(shutdownStartNanos));
    }

    // =========================================================================
    // Query API
    // =========================================================================

    /** @return {@code true} after a successful {@link #initialize(ConfigProvider)} */
    public boolean isInitialized() {
        return initialized.get();
    }

    /** @return {@code true} after a successful {@link #start(ConfigProvider)} */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Builds a {@link ScopedValue.Carrier} from all provider bindings collected
     * during {@link #initialize(ConfigProvider)}.
     *
     * <h2>Protocol</h2>
     * <p>Called by {@link KernelBootstrap} <em>after</em> {@link #initialize(ConfigProvider)}
     * returns and <em>before</em> {@link #start(ConfigProvider)} is called.
     * {@code KernelBootstrap} then executes both {@code start()} and the application
     * {@code kernelMain} <em>inside</em> the returned carrier scope, so that every
     * subsystem's {@code start()} can call
     * {@link eu.exeris.kernel.spi.context.KernelProviders#allocator()} etc. without
     * argument threading.
     *
     * <h2>Type safety</h2>
     * <p>The previous implementation iterated a {@code Map<ScopedValue<?>, Object>} and
     * required {@code @SuppressWarnings({"unchecked","rawtypes"})} to build the carrier.
     * This implementation applies the {@link #composedEnricher} — a {@link UnaryOperator}
     * composed from each subsystem's pure enricher function — to a base carrier.
     * No casts, no wildcards, no suppressions. The compiler validates each
     * {@code .where(ScopedValue<T>, T)} binding at the subsystem's own call site.
     *
     * <h2>Empty bindings</h2>
     * <p>If no subsystem overrode {@link Subsystem#providerBindings()}, the composed
     * enricher is the identity function and this method returns {@code null}.
     * {@link KernelBootstrap} treats {@code null} as "no additional scope required"
     * and runs {@code start()} directly.
     *
     * @return a {@link ScopedValue.Carrier} containing all provider bindings,
     *         or {@code null} if no subsystem registered bindings
     */
    public ScopedValue.Carrier buildKernelScope() {
        if (!hasBindings) {
            return null;
        }
        // Seed the carrier chain with a well-known bootstrap sentinel so the
        // composedEnricher lambda has a valid Carrier to call .where() on.
        // The sentinel slot is never read by application code.
        ScopedValue<Boolean> seed = ScopedValue.newInstance();
        ScopedValue.Carrier  base = ScopedValue.where(seed, Boolean.TRUE);
        // Defensive check: return base when composedEnricher is null.
        if (composedEnricher == null) {
            return base;
        }
        return composedEnricher.apply(base);
    }

    /**
     * Looks up a subsystem by name.
     *
     * @param name subsystem name
     * @return optional subsystem
     */
    public Optional<Subsystem> subsystem(String name) {
        return orderedSubsystems.stream()
                .filter(s -> name.equals(s.name()))
                .findFirst();
    }

    /**
     * Returns an unmodifiable view of all active subsystems in topological order.
     *
     * @return immutable subsystem list
     */
    public List<Subsystem> subsystems() {
        return Collections.unmodifiableList(orderedSubsystems);
    }

    /** Exposes readiness/liveness registry for probe wiring. */
    public KernelHealthMonitor healthMonitor() {
        return healthMonitor;
    }

    // =========================================================================
    // SPI resolution
    // =========================================================================

    /**
     * Loads all {@link SubsystemProvider}s, sorts by priority descending, then
     * merges into a name-keyed registry.
     * Higher-priority provider wins on name collision (Enterprise over Community).
     */
    private Map<String, Subsystem> buildRegistry(ConfigProvider config) {
        List<SubsystemProvider> providers = new ArrayList<>();
        ServiceLoader.load(SubsystemProvider.class, classLoader).forEach(providers::add);
        providers.sort(Comparator.comparingInt(SubsystemProvider::priority).reversed());

        if (providers.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                    "No SubsystemProvider registered — kernel starts with zero subsystems");
            return Map.of();
        }

        Map<String, Subsystem> registry = new LinkedHashMap<>();
        for (SubsystemProvider provider : providers) {
            LOG.log(System.Logger.Level.INFO,
                    "  SubsystemProvider: {0} (priority={1})",
                    provider.moduleName(), provider.priority());
            for (Subsystem subsystem : provider.getSubsystems(config)) {
                if (registry.putIfAbsent(subsystem.name(), subsystem) == null) {
                    LOG.log(System.Logger.Level.DEBUG, "    + [{0}]", subsystem.name());
                } else {
                    LOG.log(System.Logger.Level.DEBUG,
                            "    [{0}] shadowed by higher-priority provider",
                            subsystem.name());
                }
            }
        }
        return registry;
    }

    // =========================================================================
    // Selector closure resolution — BFS fixed-point expansion
    // =========================================================================

    private List<Subsystem> applySelector(Map<String, Subsystem> registry)
            throws BootstrapException {

        if (selector.isAll()) {
            return new ArrayList<>(registry.values());
        }

        Set<String> closure   = new LinkedHashSet<>();
        Deque<String> toVisit = new ArrayDeque<>(selector.requestedNames());

        while (!toVisit.isEmpty()) {
            String name = toVisit.poll();
            if (!closure.add(name)) {
                continue;
            }

            Subsystem candidate = registry.get(name);
            if (candidate == null) {
                throw new BootstrapException(
                        "Selector requests subsystem '" + name
                        + "' which is not provided by any SubsystemProvider on the classpath.");
            }

            for (String dep : candidate.dependsOn()) {
                if (!closure.contains(dep)) {
                    toVisit.add(dep);
                }
            }
        }

        return registry.values().stream()
                .filter(subsystem -> closure.contains(subsystem.name()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    // =========================================================================
    // Topological Sort — Kahn's BFS, O(V+E)
    //
    // CYCLE DETECTION: If the sorted result contains fewer subsystems than the
    // input, a cycle exists. We emit a JFR event, print the entropy manifest,
    // and throw SubsystemCircularDependencyException — L0 FAIL_FAST, no recovery.
    // =========================================================================

    /**
     * Internal, Valhalla-ready value carrier for Kahn's adjacency data.
     *
     * @param inDegree   subsystem name → count of unsatisfied incoming edges
     * @param dependents subsystem name → list of names that depend on it
     */
    private record DependencyGraph(
            Map<String, Integer> inDegree,
            Map<String, List<String>> dependents) {
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // computeIfAbsent pattern; allocation only on first dep
    private DependencyGraph buildDependencyGraph(Map<String, Subsystem> byName)
            throws BootstrapException {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (Subsystem subsystem : byName.values()) {
            inDegree.putIfAbsent(subsystem.name(), 0);
            for (String dep : subsystem.dependsOn()) {
                if (!byName.containsKey(dep)) {
                    throw new BootstrapException(
                            "Subsystem '" + subsystem.name() + "' depends on missing subsystem '"
                            + dep + "' in active registry. [EX-BOOT-0002]");
                }
                dependents.computeIfAbsent(dep, ignored -> new ArrayList<>())
                        .add(subsystem.name());
                inDegree.merge(subsystem.name(), 1, Integer::sum);
            }
        }
        return new DependencyGraph(inDegree, dependents);
    }

    /**
     * Topologically sorts {@code subsystems} using Kahn's BFS and populates
     * {@link #orderedSubsystems}.
     *
     * <p><b>Cycle Detection — L0 Hard Kill:</b> If {@code result.size() != input.size()},
     * one or more cycles exist. The orchestrator:
     * <ol>
     *   <li>Identifies the exact cycle members (nodes that never reached in-degree 0).</li>
     *   <li>Emits {@link BootstrapJfrEvents#emitCircularDependency} for post-mortem JFR.</li>
     *   <li>Prints the {@code 🜁 ENTROPY INTERVENTION} manifest to stderr.</li>
     *   <li>Throws {@link SubsystemCircularDependencyException#forCycle} — fatal,
     *       cannot be suppressed by {@link FailurePolicy#DEGRADE}.</li>
     * </ol>
     *
     * @throws SubsystemCircularDependencyException immediately on cycle detection
     * @throws BootstrapException                   on duplicate subsystem names
     */
    private void topologicalSort(List<Subsystem> subsystems)
            throws BootstrapException {

        // Build name → Subsystem map; detect duplicate names upfront
        Map<String, Subsystem> byName = new LinkedHashMap<>();
        for (Subsystem subsystem : subsystems) {
            if (byName.put(subsystem.name(), subsystem) != null) {
                throw new BootstrapException(
                        "Duplicate subsystem name: '" + subsystem.name() + "'. "
                        + "Two SubsystemProviders registered a subsystem with the same name.");
            }
        }

        DependencyGraph graph = buildDependencyGraph(byName);

        // Seed BFS queue with all zero-in-degree nodes
        java.util.Queue<String> queue = new PriorityQueue<>();
        graph.inDegree().entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .forEach(queue::add);

        List<Subsystem> result = new ArrayList<>(subsystems.size());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            result.add(byName.get(name));
            for (String dependent : graph.dependents().getOrDefault(name, List.of())) {
                if (graph.inDegree().merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }

        // ── CYCLE DETECTION ──────────────────────────────────────────────────
        if (result.size() != subsystems.size()) {
            // Nodes that never reached in-degree 0 are the cycle participants
            Set<String> cycleMembers = new LinkedHashSet<>(byName.keySet());
            result.forEach(resolved -> cycleMembers.remove(resolved.name()));

            // Emit JFR event BEFORE throwing — captured in any active recording
            BootstrapJfrEvents.emitCircularDependency(cycleMembers);

            // 🜁 ENTROPY INTERVENTION
            LOG.log(System.Logger.Level.ERROR, "");
            LOG.log(System.Logger.Level.ERROR,
                    E_PURPLE + E_BOLD + E_BLINK
                    + "  [ \uD83C\uDF01 E N T R O P Y   I N T E R V E N T I O N ]"
                    + E_RESET);
            LOG.log(System.Logger.Level.ERROR,
                    E_CYAN
                    + "  \"D\u0336e\u0337t\u0337e\u0337r\u0337m\u0336i\u0336n\u0336i\u0336s"
                    + "\u0336t\u0336i\u0336c\u0336 \u0336s\u0337y\u0336s\u0337t\u0336e\u0337m"
                    + "\u0337s\u0336 \u0337a\u0337r\u0336e\u0337 \u0337b\u0337e\u0336a\u0337u"
                    + "\u0337t\u0336i\u0336f\u0336u\u0336l\u0336.\u0337 \u0337T\u0336o\u0336o"
                    + "\u0336 \u0336b\u0336a\u0337d\u0337 \u0336t\u0336h\u0337e\u0337y\u0336 "
                    + "\u0336a\u0336r\u0337e\u0336 \u0337t\u0336e\u0336m\u0337p\u0337o\u0336r"
                    + "\u0336a\u0337r\u0337y\u0336.\""
                    + E_RESET);
            LOG.log(System.Logger.Level.ERROR, "");
            LOG.log(System.Logger.Level.ERROR,
                    E_PURPLE + "  FATAL ANOMALY  :  " + KernelErrorCodes.EX_BOOT_0001
                    + "  (Circular Dependency)" + E_RESET);
            LOG.log(System.Logger.Level.ERROR,
                    E_PURPLE + "  The strict topological order has collapsed into a paradox."
                    + E_RESET);
            LOG.log(System.Logger.Level.ERROR,
                    E_PURPLE + "  Cycle detected in modules: {0}" + E_RESET, cycleMembers);
            LOG.log(System.Logger.Level.ERROR, "");
            LOG.log(System.Logger.Level.ERROR,
                    E_CYAN + "  System decay accelerated."
                    + " Halting JVM to prevent state corruption." + E_RESET);
            LOG.log(System.Logger.Level.ERROR, "");

            // Pre-allocated path — forCycle() returns SENTINEL when members is empty,
            // otherwise allocates exactly one diagnostic instance. Zero heap churn
            // on a code path that terminates the JVM immediately after.
            throw SubsystemCircularDependencyException.forCycle(cycleMembers);
        }
        // ─────────────────────────────────────────────────────────────────────

        LOG.log(System.Logger.Level.INFO, "Init order: {0}",
                result.stream()
                        .map(Subsystem::name)
                        .collect(java.util.stream.Collectors.joining(" -> ")));
        orderedSubsystems.addAll(result);
    }

    // =========================================================================
    // Subsystem lifecycle helpers
    // =========================================================================

    private void doInitialize(Subsystem subsystem, KernelProfile profile) throws BootstrapException {
        long initStartNanos = System.nanoTime();
        LOG.log(System.Logger.Level.INFO,
                "  init [{0}] (phase={1})", subsystem.name(), subsystem.phase());
        try {
            subsystem.initialize();

            // ── Compose provider enricher after successful init ───────────
            // Each subsystem's UnaryOperator<ScopedValue.Carrier> is composed onto
            // the accumulated enricher via andThen(). The result is a single pure
            // function applied once in buildKernelScope() — no Map, no casts.
            //
            // We probe whether the enricher actually adds bindings by applying it to
            // a private sentinel carrier and checking if the output differs from the
            // input. This correctly handles subsystems that return the default
            // identity (carrier -> carrier) without flagging them as having bindings.
            UnaryOperator<ScopedValue.Carrier> enricher = subsystem.providerBindings();
            ScopedValue<Boolean> probe    = ScopedValue.newInstance();
            ScopedValue.Carrier  probeIn  = ScopedValue.where(probe, Boolean.TRUE);
            ScopedValue.Carrier  probeOut = enricher.apply(probeIn);
            // Reference identity check is intentional: ScopedValue.Carrier.where() always
            // returns a NEW instance. If probeOut == probeIn, the enricher is the identity
            // function (no real bindings). PMD:CompareObjectsWithEquals suppressed here.
            @SuppressWarnings("PMD.CompareObjectsWithEquals")
            boolean enricherAddsBindings = probeOut != probeIn;
            if (enricherAddsBindings) {
                composedEnricher = composedEnricher.andThen(enricher)::apply;
                hasBindings      = true;
                LOG.log(System.Logger.Level.INFO,
                        "  [{0}] provider bindings composed", subsystem.name());
            }

            LOG.log(System.Logger.Level.INFO,
                    "  [{0}] initialized ({1} ms)", subsystem.name(), elapsedMs(initStartNanos));
            healthMonitor.markSubsystemState(subsystem.name(), KernelHealthMonitor.SubsystemState.INITIALIZED);
            BootstrapJfrEvents.emitInitialized(
                    subsystem.name(), initStartNanos, profile,
                    subsystem.phase().name(), true, "");
        } catch (SubsystemException ex) {
            healthMonitor.markSubsystemState(subsystem.name(), KernelHealthMonitor.SubsystemState.FAILED);
            BootstrapJfrEvents.emitInitialized(
                    subsystem.name(), initStartNanos, profile,
                    subsystem.phase().name(), false, ex.getMessage());
            handleFailure(subsystem, ex);
        } catch (RuntimeException ex) { // NOPMD — subsystem.initialize() may throw any unchecked
            SubsystemException wrapped = new SubsystemException(
                    subsystem.name(), SubsystemException.Phase.INITIALIZE,
                    ex.getMessage(), ex);
            healthMonitor.markSubsystemState(subsystem.name(), KernelHealthMonitor.SubsystemState.FAILED);
            BootstrapJfrEvents.emitInitialized(
                    subsystem.name(), initStartNanos, profile,
                    subsystem.phase().name(), false, ex.getMessage());
            handleFailure(subsystem, wrapped);
        }
    }

    private void doStart(Subsystem subsystem, BootstrapPhase phase, String profile)
            throws BootstrapException {
        long startNanos = System.nanoTime();
        LOG.log(System.Logger.Level.INFO,
                "  start [{0}] (profile={1})", subsystem.name(), profile);
        try {
            subsystem.start();
            LOG.log(System.Logger.Level.INFO,
                    "  [{0}] started ({1} ms)", subsystem.name(), elapsedMs(startNanos));
            healthMonitor.markSubsystemState(subsystem.name(), KernelHealthMonitor.SubsystemState.RUNNING);
            BootstrapJfrEvents.emitStarted(subsystem.name(), startNanos, phase.name());
        } catch (SubsystemException ex) {
            healthMonitor.markSubsystemState(subsystem.name(), KernelHealthMonitor.SubsystemState.FAILED);
            handleFailure(subsystem, ex);
        } catch (RuntimeException ex) { // NOPMD — subsystem.start() may throw any unchecked
            healthMonitor.markSubsystemState(subsystem.name(), KernelHealthMonitor.SubsystemState.FAILED);
            handleFailure(subsystem, new SubsystemException(
                    subsystem.name(), SubsystemException.Phase.START,
                    ex.getMessage(), ex));
        }
    }

    // =========================================================================
    // Phase-based start strategies
    // =========================================================================

    private void startSequential(List<Subsystem> subsystems,
                                  BootstrapPhase phase,
                                  String profile) throws BootstrapException {
        LOG.log(System.Logger.Level.INFO,
                "Phase {0}: sequential start ({1} subsystem(s))",
                phase, subsystems.size());
        for (Subsystem subsystem : subsystems) {
            doStart(subsystem, phase, profile);
        }
    }

    @SuppressWarnings({"preview", "PMD.UseExplicitTypes"})
    // preview: StructuredTaskScope (JEP 525 — stable in JDK 24)
    // UseExplicitTypes: var is required here — StructuredTaskScope.open() returns
    // a two-parameter generic type whose spelling triggers a separate compile error.
    private void startParallel(List<Subsystem> subsystems,
                                BootstrapPhase phase,
                                String profile) throws BootstrapException {
        LOG.log(System.Logger.Level.INFO,
                "Phase {0}: parallel start ({1} subsystem(s))",
                phase, subsystems.size());
        try (var scope = StructuredTaskScope.open()) {
            // Fork one VT per subsystem; scope.join() short-circuits on first failure
            List<StructuredTaskScope.Subtask<Object>> tasks = subsystems.stream()
                    .<StructuredTaskScope.Subtask<Object>>map(
                            subsystem -> scope.fork(() -> {
                                doStart(subsystem, phase, profile);
                                return null;
                            }))
                    .toList();

            scope.join();

            // Collect failures after join — ordered and deterministic
            List<Throwable> failures = tasks.stream()
                    .filter(task -> task.state() == StructuredTaskScope.Subtask.State.FAILED)
                    .map(StructuredTaskScope.Subtask::exception)
                    .toList();

            if (!failures.isEmpty()) {
                Throwable first = failures.getFirst();
                throw new BootstrapException(
                        failures.size() + " subsystem(s) failed in phase " + phase
                        + ". First failure: " + first.getMessage(), first);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BootstrapException(
                    "Bootstrap interrupted during phase " + phase, ex);
        }
    }

    // =========================================================================
    // Failure policy enforcement
    // =========================================================================

    private void handleFailure(Subsystem subsystem, SubsystemException failure)
            throws BootstrapException {

        // FOUNDATION subsystems are ALWAYS mandatory — DEGRADE cannot save them
        boolean isMandatory =
                (subsystem.phase() == BootstrapPhase.FOUNDATION) || !subsystem.isOptional();

        if (failurePolicy == FailurePolicy.DEGRADE && !isMandatory) {
            // 🜁 Soft entropy — the system degrades, but does not collapse
            LOG.log(System.Logger.Level.WARNING,
                    E_CYAN + "  [ \uD83C\uDF01 ] Module ''{0}'' failed."
                    + " Order is an anomaly. Continuing in degraded state." + E_RESET,
                    subsystem.name());
            synchronized (orderedSubsystemsLock) {
                orderedSubsystems.remove(subsystem);
            }
        } else {
            // 🜁 ENTROPY INTERVENTION — mandatory module failure
            LOG.log(System.Logger.Level.ERROR, "");
            LOG.log(System.Logger.Level.ERROR,
                    E_PURPLE + E_BOLD + E_BLINK
                    + "  [ \uD83C\uDF01 E N T R O P Y   I N T E R V E N T I O N ]"
                    + E_RESET);
            LOG.log(System.Logger.Level.ERROR,
                    E_CYAN
                    + "  \"E\u0336v\u0337e\u0337r\u0337y\u0336 \u0336b\u0336y\u0337t\u0337e"
                    + "\u0336 \u0337i\u0336n\u0336 \u0336i\u0336t\u0337s\u0337 \u0337p\u0337l"
                    + "\u0336a\u0336c\u0337e\u0337.\u0337.\u0337.\u0337 \u0337f\u0337o\u0336r"
                    + "\u0336 \u0337n\u0337o\u0336w\u0336.\""
                    + E_RESET);
            LOG.log(System.Logger.Level.ERROR, "");
            LOG.log(System.Logger.Level.ERROR,
                    E_PURPLE + "  FATAL: Mandatory module ''{0}'' failed to start."
                    + E_RESET, subsystem.name());
            LOG.log(System.Logger.Level.ERROR,
                    E_PURPLE + "  Reason: {0}" + E_RESET, failure.getMessage());
            LOG.log(System.Logger.Level.ERROR, "");
            healthMonitor.markKernelState(KernelHealthMonitor.KernelState.FAILED);
            throw new BootstrapException(
                    "Subsystem '" + subsystem.name() + "' failed: "
                    + failure.getMessage(), failure);
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static long elapsedMs(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /** Creates a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link SubsystemOrchestrator}.
     * Zero external dependencies — pure constructor wiring.
     */
    public static final class Builder {
        private FailurePolicy     failurePolicy = FailurePolicy.FAIL_FAST;
        private BootstrapSelector selector      = BootstrapSelector.all();
        private ClassLoader       classLoader;

        /** Sets the failure policy (default: {@link FailurePolicy#FAIL_FAST}). */
        public Builder failurePolicy(FailurePolicy policy) {
            this.failurePolicy = Objects.requireNonNull(policy);
            return this;
        }

        /**
         * Sets which subsystems to activate (default: {@link BootstrapSelector#all()}).
         * The orchestrator expands the selector to its full transitive closure.
         */
        public Builder selector(BootstrapSelector sel) {
            this.selector = Objects.requireNonNull(sel);
            return this;
        }

        /**
         * Sets the {@link ClassLoader} for {@link ServiceLoader} discovery.
         * Defaults to {@code Thread.currentThread().getContextClassLoader()}.
         */
        public Builder classLoader(ClassLoader loaderClass) {
            this.classLoader = loaderClass;
            return this;
        }

        /** Builds the orchestrator. Does not start or initialize any subsystem. */
        public SubsystemOrchestrator build() {
            return new SubsystemOrchestrator(this);
        }
    }

    // =========================================================================
    // BootstrapException
    // =========================================================================

    /**
     * Thrown when bootstrap fails for any reason other than a dependency cycle.
     * Cycles use {@link SubsystemCircularDependencyException} (always FAIL_FAST).
     */
    public static final class BootstrapException extends Exception {

        public BootstrapException(String message) {
            super(message);
        }

        public BootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
