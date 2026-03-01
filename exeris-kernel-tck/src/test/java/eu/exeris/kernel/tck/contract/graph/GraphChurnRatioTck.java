/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Graph Subsystem — churn-to-data ratio contract.
 *
 * <h2>The "Zero-BS Performance Metric" (graph.md)</h2>
 * <p>The documentation explicitly documents the gap and the target:
 * <ul>
 *   <li><b>Community baseline:</b> ~15x allocation-to-data ratio (standard Bolt/Netty drivers).</li>
 *   <li><b>Enterprise target:</b> &lt;1x (off-heap, zero JVM object churn per traversal).</li>
 * </ul>
 *
 * <p>This TCK enforces the <em>documented contract</em>:
 * <ol>
 *   <li>Community implementations MUST stay below the <b>20x threshold</b> — any regression
 *       beyond the documented 15x baseline must be caught.</li>
 *   <li>Enterprise implementations MUST stay below the <b>1x threshold</b> — the off-heap
 *       path must produce zero {@code eu.exeris.*} allocations per traversal byte.</li>
 * </ol>
 *
 * <h2>Measurement Method</h2>
 * <p>Uses {@link JfrAllocationMonitor} to count bytes of {@code eu.exeris.*} objects
 * allocated during N traversal iterations. The ratio is:
 * {@code allocatedBytes / (iterations × expectedDataBytes)}.
 *
 * @since 0.5.0
 */
@DisplayName("Graph Churn-to-Data Ratio TCK (graph.md SLO)")
public abstract class GraphChurnRatioTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates a bootstrapped, running {@link GraphEngine}. */
    protected abstract GraphEngine createEngine();

    /** Returns the UUID of a node that exists in the test graph. */
    protected abstract UUID startNodeId();

    /** Returns the edge descriptor used for test traversals. */
    protected abstract GraphEdgeDescriptor testEdge();

    /**
     * Returns {@code true} if this is the Enterprise implementation.
     * Enterprise: churn ratio threshold is 1.0 (zero off-heap allocation per byte).
     * Community: churn ratio threshold is 20.0 (baseline 15x + 33% margin).
     */
    protected boolean isEnterpriseTier() { return false; }

    /**
     * The expected byte count of useful data returned per traversal iteration.
     * Default: 128 bytes (typical UUID list for a small graph).
     */
    protected int expectedDataBytesPerIteration() { return 128; }

    /**
     * Number of traversal iterations used for the ratio measurement.
     * Default: 1000.
     */
    protected int measurementIterations() { return 1_000; }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private GraphEngine   engine;
    private GraphTraversal hotTraversal;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
        engine.registerNodes(List.of(
                GraphNodeDescriptor.create("Node", "nodes")));
        engine.registerEdges(List.of(testEdge()));
        hotTraversal = GraphTraversal.create(startNodeId(), testEdge(), 1);
    }

    @AfterEach
    final void tearDown() {
        if (engine != null) engine.close();
    }

    // =========================================================================
    // Churn ratio test
    // =========================================================================

    @Test
    @DisplayName("Churn-to-data ratio within documented SLO (Community:<20x, Enterprise:<1x)")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void churnToDataRatioWithinSlo() throws IOException {
        int    iterations  = measurementIterations();
        long   dataBytes   = (long) iterations * expectedDataBytesPerIteration();
        double threshold   = isEnterpriseTier() ? 1.0 : 20.0;

        // Warmup — not measured; flushes JIT and class-loading
        for (int i = 0; i < 50; i++) {
            try (GraphSession s = engine.openSession()) {
                s.traverseBreadthFirst(hotTraversal);
            }
        }

        // Measured run via JfrAllocationMonitor
        var config = JfrAllocationMonitor.Config.ofDefaults(
                "Graph-ChurnRatio", getClass().getSimpleName());

        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iters -> {
            for (int i = 0; i < iters; i++) {
                try (GraphSession s = engine.openSession()) {
                    var r = s.traverseBreadthFirst(hotTraversal);
                    if (r == null) throw new AssertionError("null result");
                }
            }
        });

        // Sum allocated bytes from all eu.exeris.* allocation events.
        // Direct getLong() call — fails fast if the JFR event schema changes or
        // the "allocationSize" field is missing, preventing silent false negatives.
        long allocatedBytes = result.exerisAllocations().stream()
                .mapToLong(e -> e.getLong("allocationSize"))
                .sum();
        double actualRatio    = dataBytes > 0
                ? (double) allocatedBytes / dataBytes
                : allocatedBytes;

        assertThat(actualRatio)
                .as("Graph churn-to-data ratio MUST be < %.1fx (graph.md SLO). " +
                    "Actual: %.2fx — allocatedBytes=%d over %d iterations × %d expected bytes. " +
                    "%s tier: %s",
                    threshold, actualRatio, allocatedBytes, iterations,
                    expectedDataBytesPerIteration(),
                    isEnterpriseTier() ? "Enterprise" : "Community",
                    isEnterpriseTier()
                        ? "Off-heap LoanedBuffer + slab pool = zero GC object churn"
                        : "Standard Bolt/Netty baseline is ~15x; >20x means a regression")
                .isLessThan(threshold);
    }
}




