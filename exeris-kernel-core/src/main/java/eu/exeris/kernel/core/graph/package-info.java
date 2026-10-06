/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Core: Graph subsystem orchestration (L2 Data Synthesis).
 *
 * <h2>Package Layout</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.core.graph.GraphBootstrap} — ServiceLoader bootstrap,
 *       selects the highest-priority {@link eu.exeris.kernel.spi.graph.GraphProvider}</li>
 *   <li>{@link eu.exeris.kernel.core.graph.MatchDslTranspiler} — returns the query string a
 *       {@link eu.exeris.kernel.spi.graph.GraphDialect} builds for a traversal or shortest-path
 *       request. It is not wired into any session or backend: the graph backends build the
 *       queries they execute through the dialect themselves, and only tests call this class</li>
 *   <li>{@link eu.exeris.kernel.core.graph.GraphSyncService} — dual-write consistency
 *       orchestrator ensuring L1 Persistence changes are reflected in L2 graph</li>
 *   <li>{@link eu.exeris.kernel.core.graph.AlgoOrchestrator} — delegates Dijkstra,
 *       BFS, and K-shortest traversals to pluggable
 *       {@link eu.exeris.kernel.spi.graph.algorithm.PathFinder} implementations</li>
 *   <li>{@link eu.exeris.kernel.core.graph.GraphMetadataEngine} — annotation-driven
 *       discovery of {@link eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor}
 *       and {@link eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor} from domain classes</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This package depends on {@code exeris-kernel-spi} types and Core-internal bootstrap
 * utilities only. No class in this package may import JDBC, Bolt, io_uring, HikariCP, or
 * any Community/Enterprise implementation class. "The Brain" orchestrates; it never
 * executes I/O.
 *
 * @since 0.5
 */
package eu.exeris.kernel.core.graph;

