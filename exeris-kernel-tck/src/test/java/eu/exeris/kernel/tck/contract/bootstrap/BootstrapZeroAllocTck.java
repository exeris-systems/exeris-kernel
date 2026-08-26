/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

/**
 * TCK: JFR Zero-Allocation monitor for the Bootstrap topological sort hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code SubsystemOrchestrator.sort(subsystems)} — Kahn's BFS executed on a
 * pre-built 100-subsystem linear chain. The graph is assembled <em>before</em>
 * JFR recording starts (bootstrap phase). Only the actual sort operation is
 * measured for heap allocations.
 *
 * <h2>Tier Differentiation</h2>
 * <ul>
 *   <li><b>Community</b>: bounded allocations acceptable (ArrayList, HashMap churn).</li>
 *   <li><b>Enterprise</b>: override {@link #supportsZeroGcHotPath()} to {@code true}
 *       when the sort uses pre-allocated structures.</li>
 * </ul>
 *
 * <h2>How to implement</h2>
 * <pre>{@code
 * class CoreBootstrapZeroAllocTckTest extends BootstrapZeroAllocTck {
 *     \@Override
 *     protected List<Subsystem> runSort(List<Subsystem> input) {
 *         return BootstrapOrchestrator.sort(input);
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractSubsystemZeroAllocTck
 */
@DisplayName("Bootstrap topological sort JFR zero-allocation TCK")
public abstract class BootstrapZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    /**
     * Constant for the graph width used in the pre-built test fixture.
     * 100 nodes is large enough to exercise the sorting algorithm and expose
     * per-node allocation patterns.
     */
    private static final int GRAPH_SIZE = 100;

    // =========================================================================
    // Template method — subclass wires the real orchestrator
    // =========================================================================

    /**
     * Runs the topological sort on the given subsystem list.
     *
     * <p>Implementations delegate to the real orchestrator under test.
     * The input is a pre-built linear chain of {@value #GRAPH_SIZE} subsystems.
     *
     * @param input pre-built linear subsystem chain
     * @return topologically sorted list
     */
    protected abstract List<Subsystem> runSort(List<Subsystem> input);

    // =========================================================================
    // Pre-built fixture
    // =========================================================================

    /** Pre-built linear chain of {@value #GRAPH_SIZE} subsystems. */
    private List<Subsystem> fixture;

    // =========================================================================
    // AbstractSubsystemZeroAllocTck overrides
    // =========================================================================

    @Override
    protected String subsystemName() {
        return "Bootstrap";
    }

    @Override
    protected String hotPathDescription() {
        return "SubsystemOrchestrator.sort(" + GRAPH_SIZE + "-node linear chain)";
    }

    @Override
    protected int warmupIterations() {
        // Fewer iterations — sort is O(V+E) and warm-up is just for JIT
        return 500;
    }

    @Override
    protected int hotPathIterations() {
        // 5 000 sorts of 100-node chains = 500 000 node visits
        return 5_000;
    }

    @Override
    protected int maxExerisAllocationsPerIteration() {
        // Community: HashMap + ArrayList churn expected per sort run
        return 20;
    }

    @Override
    protected void bootstrapSubsystem() {
        // Build the fixture ONCE — before JFR recording starts.
        // The same fixture list is reused every iteration (runSort must NOT mutate it).
        fixture = buildLinearChain(GRAPH_SIZE);
    }

    @Override
    protected void runSingleIteration() {
        // JFR measures only this call.
        // runSort() must not modify the input list — a new sorted list is returned.
        runSort(fixture);
    }

    @Override
    protected void tearDownSubsystem() {
        fixture = null;
    }

    // =========================================================================
    // Fixture builder
    // =========================================================================

    /**
     * Builds a linear chain of {@code size} subsystems:
     * {@code node-0 ← node-1 ← node-2 ← … ← node-(size-1)}.
     *
     * <p>Each subsystem declares a single dependency on its predecessor.
     * The chain is returned in <em>reverse</em> order (most-dependent first)
     * to stress the sort algorithm.
     *
     * @param size number of nodes
     * @return reverse-ordered list ready to be sorted
     */
    private static List<Subsystem> buildLinearChain(int size) {
        List<Subsystem> chain = new ArrayList<>(size);
        for (int idx = 0; idx < size; idx++) {
            final String nodeName = "node-" + idx;
            final String depName  = idx == 0 ? null : "node-" + (idx - 1);
            // Pre-allocate the dependency list once per node so that dependsOn() returns
            // the same instance on every call — no per-iteration List.of() allocation
            // during the BFS sort, keeping the zero-allocation signal clean.
            final List<String> deps = depName == null ? List.of() : List.of(depName);
            chain.add(new Subsystem() {
                @Override public String name()            { return nodeName; }
                @Override public List<String> dependsOn() { return deps; }
                @Override public BootstrapPhase phase()  { return BootstrapPhase.FOUNDATION; }
                @Override public void initialize() throws SubsystemException { /* no-op */ }
                @Override public void start()      throws SubsystemException { /* no-op */ }
                @Override public void stop()                                 { /* no-op */ }
            });
        }
        // Reverse: most-dependent node first — stresses the BFS queue
        List<Subsystem> reversed = new ArrayList<>(size);
        for (int i = size - 1; i >= 0; i--) {
            reversed.add(chain.get(i));
        }
        return reversed;
    }
}

