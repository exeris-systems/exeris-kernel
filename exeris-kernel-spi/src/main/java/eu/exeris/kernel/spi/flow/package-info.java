/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
/**
 * SPI: Flow Engine subsystem — pluggable saga/flow orchestration layer.
 *
 * <h2>Open-Core Boundary (The Wall)</h2>
 * <p>This package is <strong>implementation-blind</strong>. It contains only pure contracts:
 * interfaces, records, enums, and {@code @FunctionalInterface}s. No reference to
 * {@code io_uring}, {@code JDBC}, {@code HikariCP}, {@code PostgreSQL}, off-heap slabs,
 * or any Community/Enterprise driver appears here.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   exeris-kernel-spi (this package)
 *     └── FlowProvider  ← ServiceLoader boundary
 *           └── FlowEngine  ← composite facade
 *                 ├── FlowBuilder
 *                 ├── FlowScheduler
 *                 ├── FlowRegistry
 *                 └── FlowExecutionPlanFactory
 *
 *   exeris-kernel-community (hidden from SPI)
 *     └── HeapFlowProvider (priority=0, heap-based, StructuredTaskScope)
 *
 *   exeris-kernel-enterprise (hidden from SPI)
 *     └── SlabFlowProvider (priority=100, off-heap, lock-free, zero-GC)
 * </pre>
 *
 * <h2>Context Propagation</h2>
 * <p>The resolved {@link eu.exeris.kernel.spi.flow.FlowEngine} is bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#FLOW_ENGINE} once during bootstrap.
 * All subsystems read it via the scoped slot — zero static singletons.
 *
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.flow;

