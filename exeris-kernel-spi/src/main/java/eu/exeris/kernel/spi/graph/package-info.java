/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Graph Subsystem SPI — L2 Data Synthesis.
 *
 * <h2>Entry Point</h2>
 * <p>{@link eu.exeris.kernel.spi.graph.GraphProvider} is the {@code ServiceLoader}
 * entry point. The kernel bootstrapper selects the highest-priority provider and
 * creates a {@link eu.exeris.kernel.spi.graph.GraphEngine}.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This package has <strong>zero knowledge</strong> of any backend-specific APIs,
 * protocols, drivers, data sources, or transport mechanisms. It defines only pure
 * contracts and Valhalla-ready value records, and remains implementation-blind at
 * the SPI surface. References to specific technologies (e.g., JDBC, Neo4j Bolt,
 * io_uring) appear solely in documentation to describe expected implementation
 * bindings; they must never appear in types or method signatures within this package.
 *
 * <h2>Package Layout</h2>
 * <pre>
 * eu.exeris.kernel.spi.graph           — Provider, Engine, Session, Dialect, Config
 * eu.exeris.kernel.spi.graph.model     — GraphNodeDescriptor, GraphEdgeDescriptor,
 *                                         GraphTraversal, PathResult
 * eu.exeris.kernel.spi.graph.algorithm — PathFinder, EdgeWeightFunction
 * </pre>
 *
 * <h2>Memory Model</h2>
 * <ul>
 *   <li><b>Community:</b> {@code MemoryAllocator} → {@code LoanedBuffer}
 *       (Arena per request). Temporary arenas for graph building, no preallocated
 *       slab pools, no raw pointers.</li>
 *   <li><b>Enterprise:</b> {@code GlobalMemoryArbiter} → preallocated partition →
 *       {@code PartitionedSlabPool}. Node/edge registries in slab pools,
 *       query caches in off-heap LRU, scheduler queues as raw pointer arrays.
 *       Zero dynamic allocation after startup.</li>
 * </ul>
 *
 * @since 0.5
 * @see eu.exeris.kernel.spi.graph.GraphProvider
 * @see eu.exeris.kernel.spi.graph.GraphEngine
 */
package eu.exeris.kernel.spi.graph;

