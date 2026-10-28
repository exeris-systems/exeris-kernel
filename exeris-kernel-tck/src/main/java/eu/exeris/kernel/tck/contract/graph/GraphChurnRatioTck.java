/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <h2>What graph.md publishes</h2>
 * <ul>
 *   <li><b>Community contract:</b> &lt;23x allocation-to-data ratio in steady state, in either of the
 *       two allocation regimes the Bolt path settles into.</li>
 *   <li><b>Enterprise target:</b> &lt;1x (off-heap, zero JVM object churn per traversal).</li>
 * </ul>
 *
 * <h2>Measurement method</h2>
 * <ol>
 *   <li><b>The numerator is a byte count.</b> It is the exact per-thread allocated-bytes delta across
 *       the measured traversals, not a sum over sampled JFR allocation events: the sampler's
 *       {@code weight} arrives in a near-constant quantum, so a sum of samples is a count of sampler
 *       hits scaled by that quantum, and the ratio it yields moves in steps of one hit.</li>
 *   <li><b>Every allocation on the path counts, not only {@code eu.exeris.*}.</b> The documented cost
 *       is the driver's, and on the Community Bolt path the kernel's own share of the total is
 *       small, so a filtered numerator excludes the thing being measured. Attribution by type belongs to
 *       {@link JfrAllocationMonitor}'s separate <em>zero-allocation</em> contract.</li>
 *   <li><b>The workload returns {@link #traversalFanOut()} ids per traversal.</b> The ratio is defined
 *       per byte transferred; a traversal returning one id measures session setup, not churn per
 *       data byte. With a fan-out the fixed per-round-trip cost amortises where the documented figure
 *       puts it. The numerator and the denominator cover the same traversals.</li>
 * </ol>
 *
 * <p>Payload is counted as {@link #bytesPerResultId()} = 16, the UUID's own width. Deliberately
 * conservative: the Bolt/PGQ wire encodes each id as a 36-character string, so counting semantic
 * bytes makes the denominator smaller and the assertion stricter than counting transferred bytes.
 *
 * <h2>Warm-up, and the transient</h2>
 * <p>Allocation is not in steady state until C2 has recompiled the path. Measured on a fresh JVM in
 * windows of 100 traversals with no prelude, across five JVMs: 18.0–18.3x over the first hundred,
 * 19.9–20.6x over the second, then 17.4–18.0x from traversal ~200 onward. Every window is inside the
 * bound; {@link #warmupIterations()} still clears the knee, because the published figure is a
 * steady-state one.
 *
 * <h2>Why the Community bound is 23x</h2>
 * <p>Measured this way, the path shows <b>two allocation regimes</b>, and the choice
 * is made once per JVM and then holds:
 *
 * <pre>
 *   fast regime : ~142 000 bytes/traversal  → 17.5 – 17.9x
 *   slow regime : ~166 500 bytes/traversal  → 20.8x
 * </pre>
 *
 * <p>They are separated by a flat 17%, and nothing mixes them within a process. A single figure below
 * the slow regime would fail on a property of the driver path rather than of the code under test, so
 * the Community contract is the one bound that covers both: <b>23x</b>, about 10% above the observed
 * slow-regime ceiling. A change adding 128 bytes per returned row adds 8x and trips it from either
 * regime. A regression smaller than about 35 bytes per row in the slow regime, or about 80 in the fast
 * one, stays inside the bound. Raising the number to hide a real regression would defeat the only thing
 * the test does.
 *
 * @since 0.5
 */
@DisplayName("Graph Churn-to-Data Ratio TCK (graph.md contract)")
public abstract class GraphChurnRatioTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Creates a bootstrapped, running {@link GraphEngine}.
     *
     * @return a running graph engine ready to open sessions
     */
    protected abstract GraphEngine createEngine();

    /**
     * Returns the UUID of the node the measured traversal starts from.
     *
     * @return the start node UUID for the measured traversal
     */
    protected abstract UUID startNodeId();

    /**
     * Returns the edge descriptor used for test traversals.
     *
     * @return the edge descriptor for the seeded fan-out edges
     */
    protected abstract GraphEdgeDescriptor testEdge();

    /**
     * Returns {@code true} if this is the Enterprise implementation, whose {@code < 1x} target is
     * the contract itself — an off-heap path has no second regime to leave room for.
     *
     * @return {@code true} for the Enterprise tier, {@code false} for Community
     */
    protected boolean isEnterpriseTier() { return false; }

    /**
     * The bound this test enforces, and the figure graph.md publishes. Enterprise: 1.0. Community:
     * 23.0, which sits above both allocation regimes of the Bolt path — see the class Javadoc.
     *
     * @return the churn-to-data ratio this test fails above
     */
    protected double churnBound() { return isEnterpriseTier() ? 1.0 : 23.0; }

    /**
     * The ratio graph.md publishes for this tier, reported alongside the measurement. It equals
     * {@link #churnBound()}: the published figure is the enforced one.
     *
     * @return the churn-to-data ratio documented in graph.md for this tier
     */
    protected double documentedContract() { return isEnterpriseTier() ? 1.0 : 23.0; }

    /**
     * Edges seeded from the start node, and therefore ids each measured traversal returns. Large
     * enough that per-round-trip setup is not the whole measurement.
     *
     * @return the number of edges seeded from {@link #startNodeId()}
     */
    protected int traversalFanOut() { return 500; }

    /**
     * Bytes of useful data per returned id — a UUID is 128 bits.
     *
     * @return the number of semantic payload bytes counted per returned id
     */
    protected int bytesPerResultId() { return 16; }

    /**
     * Traversals in the measured window.
     *
     * @return the number of traversals sampled for the ratio measurement
     */
    protected int measurementIterations() { return 300; }

    /**
     * Traversals discarded first; sized from the convergence curve in this class's Javadoc.
     *
     * @return the number of warm-up traversals run and discarded before measurement
     */
    protected int warmupIterations() { return 300; }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private GraphEngine    engine;
    private GraphTraversal hotTraversal;

    /**
     * Creates the contract; subclasses supply the {@link GraphEngine} under test via {@link #createEngine()},
     * the traversal start node via {@link #startNodeId()}, and the seeded fan-out edge via {@link #testEdge()}.
     */
    public GraphChurnRatioTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUp() {
        engine = createEngine();
        engine.registerNodes(List.of(
                GraphNodeDescriptor.create("Node", "nodes")));
        engine.registerEdges(List.of(testEdge()));
        // Seeded here rather than in the binding, so the count the denominator uses and the count
        // the graph actually holds come from one source.
        try (GraphSession session = engine.openSession()) {
            for (int i = 0; i < traversalFanOut(); i++) {
                session.upsertEdge(testEdge(), startNodeId(), UUID.randomUUID(), 1.0, "{}");
            }
        }
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
    @DisplayName("Churn-to-data ratio within the published contract (Community:<23x, Enterprise:<1x)")
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void churnToDataRatioWithinSlo() throws IOException {
        int iterations = measurementIterations();

        // Non-vacuity gate: the denominator is only meaningful if the traversal really returns the
        // seeded fan-out. A silently empty result would divide by zero data bytes, and a short one
        // would inflate the ratio for a reason that has nothing to do with churn.
        int returnedIds;
        try (GraphSession session = engine.openSession()) {
            returnedIds = session.traverseBreadthFirst(hotTraversal).size();
        }
        assertThat(returnedIds)
                .as("The measured traversal must return the seeded fan-out; a short result set "
                    + "would make the churn denominator fictional")
                .isEqualTo(traversalFanOut());

        // The churn bound is a byte budget in disguise. A ratio of r against a payload of
        // (returnedIds x bytesPerResultId) bytes per traversal is r x that many bytes per
        // iteration, and stating it that way lets the recording carry the contract: a reader has
        // the bytes delta and the iteration count, but no idea what the payload was.
        double budgetBytesPerIteration = churnBound() * returnedIds * bytesPerResultId();
        var contract = JfrAllocationMonitor.Contract.bytesPerIteration(budgetBytesPerIteration);
        var config = new JfrAllocationMonitor.Config(
                "Graph-ChurnRatio", getClass().getSimpleName(), warmupIterations(), iterations,
                contract);
        JfrAllocationMonitor.Result result =
                JfrAllocationMonitor.measure(config, this::runTraversals);

        assertThat(result.allocatedBytesDelta())
                .as("This JVM does not report per-thread allocated bytes, so the churn-to-data "
                    + "ratio cannot be certified. Failing rather than skipping: a silent pass "
                    + "would read as compliance with graph.md.")
                .isNotEqualTo(JfrAllocationMonitor.ALLOCATED_BYTES_UNAVAILABLE);

        // Asserted through the contract, so the bound in the failure message and the bound written
        // into the recording cannot drift apart. The ratio survives as the unit the docs publish.
        double actualBytesPerIteration = (double) result.allocatedBytesDelta() / iterations;
        double actualRatio = actualBytesPerIteration / (returnedIds * bytesPerResultId());

        assertThat(actualBytesPerIteration)
                .as("Graph churn-to-data ratio MUST be < %.1fx. Actual: %.2fx — allocatedBytes=%d "
                    + "over %d traversals × %d ids × %d bytes (%.0f bytes/traversal). %s tier; "
                    + "graph.md publishes %.1fx.%n%s%n"
                    + "JFR-sampled kernel-type attribution (diagnostic only, NOT the measured "
                    + "quantity): %s",
                    churnBound(), actualRatio, result.allocatedBytesDelta(), iterations,
                    returnedIds, bytesPerResultId(),
                    (double) result.allocatedBytesDelta() / iterations,
                    isEnterpriseTier() ? "Enterprise" : "Community",
                    documentedContract(), regimeNote(), result.summary())
                .isLessThan(contract.budgetBytesPerIteration());
    }

    /** Tier-specific context for a breach: what a value above the bound does and does not mean. */
    private String regimeNote() {
        return isEnterpriseTier()
                ? "The off-heap path has no second regime: anything above the bound is JVM object "
                  + "churn on a path contracted to have none."
                : "The Community Bolt path runs in one of two regimes per JVM, ~142 000 "
                  + "bytes/traversal (17.5-17.9x) or ~166 500 (20.8x); both are inside the bound, "
                  + "so a value above it is a regression.";
    }

    private void runTraversals(int count) {
        for (int i = 0; i < count; i++) {
            try (GraphSession session = engine.openSession()) {
                if (session.traverseBreadthFirst(hotTraversal) == null) {
                    throw new AssertionError("traverseBreadthFirst returned null");
                }
            }
        }
    }
}
