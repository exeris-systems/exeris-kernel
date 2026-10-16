/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphKernelProviders;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;

import java.util.List;
import java.util.UUID;

/**
 * Executes the graph smoke verification step over PostgreSQL or Neo4j.
 */
@SuppressWarnings({
        "PMD.SystemPrintln", "PMD.DoNotTerminateVM",
        "PMD.AvoidCatchingGenericException", "PMD.SignatureDeclareThrowsException"
})
/* default */ final class GraphStep {

    private static final String BACKEND_NEO4J = "neo4j";
    private static final int REQUIRED_ARGS = 4;
    private static final int NEO4J_REQUIRED_ARGS = 7;
    private static final int EXPECTED_HOPS = 2;
    private static final int TRAVERSAL_DEPTH = 2;

    private GraphStep() {
        // Utility step class
    }

    /* default */ static void run(String... arguments) throws Exception {
        if (arguments.length < REQUIRED_ARGS) {
            System.out.println("ERROR: graph requires <postgresql|neo4j> <jdbcUrl> <user> <pass> "
                    + "[boltUri neo4jUser neo4jPass]");
            System.exit(1);
        }
        String backend = arguments[0];
        String jdbcUrl = arguments[1];
        String username = arguments[2];
        String password = arguments[3];

        Checks checks = Checks.declare("graph", "upsert", "bfs-depth-2", "shortest-path");

        System.setProperty("exeris.persistence.jdbcUrl", jdbcUrl);
        System.setProperty("exeris.persistence.username", username);
        System.setProperty("exeris.persistence.password", password);
        System.setProperty("exeris.persistence.runMigrations", "true");
        System.setProperty("exeris.graph.backendType", backend);

        if (BACKEND_NEO4J.equals(backend)) {
            if (arguments.length < NEO4J_REQUIRED_ARGS) {
                System.out.println("ERROR: neo4j backend requires boltUri, user, and pass");
                System.exit(1);
            }
            System.setProperty("exeris.graph.neo4j.uri", arguments[4]);
            System.setProperty("exeris.graph.neo4j.user", arguments[5]);
            System.setProperty("exeris.graph.neo4j.password", arguments[6]);
        }

        long startTime = System.nanoTime();
        long[] bootTimeHolder = {0L};

        KernelBootstrap bootstrap = KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("graph"))
                .build();

        bootstrap.boot(() -> {
            bootTimeHolder[0] = (System.nanoTime() - startTime) / 1_000_000L;
            try {
                GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("Person", "FOLLOWS", "Person");
                GraphEngine engine = GraphKernelProviders.graphEngine();
                engine.registerEdges(List.of(follows));

                UUID nodeA = UUID.randomUUID();
                UUID nodeB = UUID.randomUUID();
                UUID nodeC = UUID.randomUUID();

                try (GraphSession session = engine.openSession()) {
                    session.upsertEdge(follows, nodeA, nodeB, 1.0, "{}");
                    session.upsertEdge(follows, nodeB, nodeC, 1.0, "{}");
                    checks.check("upsert", true, "edges inserted");

                    List<UUID> reach = session.traverseBreadthFirst(
                            GraphTraversal.create(nodeA, follows, TRAVERSAL_DEPTH));
                    boolean containsC = reach.contains(nodeC);
                    checks.check("bfs-depth-2", containsC, containsC ? "reached c" : "traversed: " + reach);

                    PathResult path = session.findShortestPath(follows, nodeA, nodeC);
                    boolean hopsMatch = path.found() && path.hopCount() == EXPECTED_HOPS;
                    checks.check("shortest-path", hopsMatch, "hops=" + path.hopCount());
                }
            } catch (Throwable t) {
                printException("Graph execution error", t);
                checks.check("upsert", false, t.getMessage());
            }
        });

        boolean passed = checks.finish();
        Checks.printMeasure(bootTimeHolder[0]);
        if (!passed) {
            System.exit(1);
        }
    }

    private static void printException(String prefix, Throwable throwable) {
        System.out.println(prefix + ": " + throwable);
        for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
            System.out.println("  caused by: " + cause);
        }
    }
}
