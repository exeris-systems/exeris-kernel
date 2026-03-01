/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: Subsystem Boot Throughput.
 *
 * <h2>Front 4 — Arena wydajności (SLO: &lt; 50 ms for L0 graph)</h2>
 * <p>Measures the time to initialise an <em>empty</em> L0 subsystem graph:
 * load all {@link SubsystemProvider} instances via {@link ServiceLoader}, call
 * {@code getSubsystems()}, and run Kahn's topological sort.
 *
 * <h2>SLO</h2>
 * <p>{@code p99 < 50 ms} for the complete FOUNDATION-phase initialisation
 * of an empty (no SERVICES/RUNTIME subsystems) kernel graph.
 *
 * <h2>Why this matters</h2>
 * <p>Slow bootstrap means slow container restarts in cloud-native deployments.
 * Every millisecond counts in high-density microservice clusters. A 50 ms budget
 * allows for L0 memory, telemetry, and crypto subsystems to initialise without
 * exceeding the Kubernetes readiness probe timeout.
 *
 * @since 0.5.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractSubsystemBootThroughputBenchmark extends AbstractExerisBenchmark {

    /**
     * Subclass provides the {@link SubsystemProvider} under test.
     *
     * <p>May return a minimal implementation with only FOUNDATION-phase subsystems
     * to isolate the L0 boot path.
     */
    protected abstract SubsystemProvider createProvider();

    /**
     * Subclass provides the {@link ConfigProvider} stub used during boot.
     */
    protected abstract ConfigProvider createConfigStub();

    private SubsystemProvider provider;
    private ConfigProvider    config;

    @Setup(Level.Trial)
    public void setUp() {
        provider = createProvider();
        config   = createConfigStub();
    }

    /**
     * Measures the complete L0 boot sequence:
     * <ol>
     *   <li>Call {@code getSubsystems()} — discovers all registered subsystems.</li>
     *   <li>Run Kahn's topological sort — O(V + E), worst case O(n²) if badly
     *       implemented (this benchmark will expose that).</li>
     *   <li>Call {@code initialize()} on each FOUNDATION subsystem in sorted order.</li>
     * </ol>
     *
     * <h2>SLO</h2>
     * <p>Average time MUST be &lt; 50 ms for an empty L0 graph.
     */
    @Benchmark
    public void bootL0Graph(Blackhole bh) throws SubsystemException {
        List<Subsystem> subsystems = provider.getSubsystems(config);
        // Topological sort (Kahn's BFS)
        List<Subsystem> sorted = topoSort(subsystems);
        // Simulate FOUNDATION initialization
        for (Subsystem sub : sorted) {
            if (sub.phase() == BootstrapPhase.FOUNDATION) {
                try {
                    sub.initialize();
                } catch (SubsystemException e) {
                    throw new RuntimeException("Bootstrap TCK: subsystem '" + sub.name()
                            + "' failed during initialize()", e);
                }
            }
        }
        bh.consume(sorted.size());
    }

    /**
     * Reference Kahn's sort — must be O(V + E), not O(n²).
     * Subclasses may override to use the real orchestrator.
     */
    protected List<Subsystem> topoSort(List<Subsystem> subsystems) {
        // Kahn's BFS — true O(V+E): adjacency list built once, no per-node full scan.
        java.util.Map<String, Subsystem> byName      = new java.util.HashMap<>(subsystems.size() * 2);
        java.util.Map<String, Integer>   inDeg       = new java.util.HashMap<>(subsystems.size() * 2);
        // reverseDeps[dep] = list of subsystems that depend ON dep.
        java.util.Map<String, java.util.List<String>> reverseDeps =
                new java.util.HashMap<>(subsystems.size() * 2);

        for (Subsystem s : subsystems) {
            byName.put(s.name(), s);
            inDeg.put(s.name(), 0);
            reverseDeps.put(s.name(), new java.util.ArrayList<>());
        }
        // Phase 1: compute in-degrees and build reverse adjacency list — O(V+E).
        for (Subsystem s : subsystems) {
            int depCount = s.dependsOn().size();
            if (depCount > 0) {
                inDeg.merge(s.name(), depCount, Integer::sum);
                for (String dep : s.dependsOn()) {
                    reverseDeps.computeIfAbsent(dep, k -> new java.util.ArrayList<>()).add(s.name());
                }
            }
        }
        // Phase 2: BFS using pre-built adjacency list — O(V+E), no contains() scan.
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        inDeg.forEach((name, deg) -> { if (deg == 0) queue.add(name); });
        java.util.List<Subsystem> result = new java.util.ArrayList<>(subsystems.size());
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            result.add(byName.get(cur));
            for (String dependent : reverseDeps.getOrDefault(cur, java.util.List.of())) {
                int nd = inDeg.merge(dependent, -1, Integer::sum);
                if (nd == 0) queue.add(dependent);
            }
        }
        return result;
    }
}



