/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */

/**
 * Exeris Kernel SPI – Memory subsystem contracts (L0 Foundation).
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.memory.MemoryAllocator} — unified allocation facade
 *       (replaces static {@code MemoryManager})</li>
 *   <li>{@link eu.exeris.kernel.spi.memory.LoanedBuffer} — reference-counted, zero-copy buffer
 *       with {@code slice()}, {@code view()}, and {@code peek()} fragmentation support</li>
 *   <li>{@link eu.exeris.kernel.spi.memory.MemoryProvider} — {@code ServiceLoader} factory;
 *       Community provides Heap/Arena, Enterprise provides mmap/io_uring slabs</li>
 *   <li>{@link eu.exeris.kernel.spi.memory.MemoryProviderConfig} — value record config
 *       (Valhalla-ready, JEP 401)</li>
 *   <li>{@link eu.exeris.kernel.spi.memory.AllocationHint} — semantic T-shirt sizes
 *       (MICRO → JUMBO); decouples call sites from raw byte counts</li>
 *   <li>{@link eu.exeris.kernel.spi.memory.MemoryStats} — value record diagnostics snapshot</li>
 *   <li>{@link eu.exeris.kernel.spi.memory.LeakDetectionMode} — DISABLED / SAMPLED / PARANOID</li>
 *   <li>{@link eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException} — zero-alloc Black-Box exception</li>
 *   <li>{@link eu.exeris.kernel.spi.exceptions.memory.MemoryBootstrapException} — thrown when provider init fails</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This package is <strong>implementation-blind</strong>: no references to
 * {@code sun.misc.Unsafe}, {@code ByteBuffer}, {@code Arena.ofConfined()}, {@code io_uring},
 * {@code Netty}, or any community/enterprise driver class. All allocations MUST go through
 * {@link eu.exeris.kernel.spi.memory.MemoryAllocator}.
 *
 * <h2>Context Propagation</h2>
 * <p>The active {@link eu.exeris.kernel.spi.memory.MemoryAllocator} is propagated via
 * {@link eu.exeris.kernel.spi.context.KernelProviders#MEMORY_ALLOCATOR} ({@code ScopedValue},
 * JEP 506). Never pass the allocator as a method parameter for cross-cutting concerns.
 *
 * @see <a href="../../../../../../docs/subsystems/memory.md">memory.md</a>
 */
package eu.exeris.kernel.spi.memory;

