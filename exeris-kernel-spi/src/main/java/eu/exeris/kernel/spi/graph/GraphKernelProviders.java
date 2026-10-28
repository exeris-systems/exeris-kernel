/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph;

/**
 * {@link ScopedValue} slots for the graph SPI providers resolved during bootstrap.
 *
 * <h2>Separation from {@code KernelProviders}</h2>
 * <p>The slots live in the package that owns their types, so the generic
 * {@link eu.exeris.kernel.spi.context.KernelProviders} registry names no type from a package whose
 * maturity differs from its own (ADR-100). {@code KernelProviders} keeps deprecated members that are
 * the very same {@link ScopedValue} instances declared here, not second slots: a binding made through
 * either name is visible through both.
 *
 * <h2>Binding (bootstrap side)</h2>
 * {@snippet lang="java" :
 * ScopedValue
 *     .where(GraphKernelProviders.GRAPH_PROVIDER, provider)
 *     .where(GraphKernelProviders.GRAPH_ENGINE,   engine)
 *     .run(kernel::startSubsystems);
 * }
 *
 * <h2>Reading (subsystem side)</h2>
 * {@snippet lang="java" :
 * try (GraphSession session = GraphKernelProviders.graphEngine().openSession()) {
 *     List<UUID> nodes = session.traverseBreadthFirst(traversal);
 * }
 * }
 *
 * <p><b>Allocation:</b> zero-alloc — reading a slot allocates nothing.
 * <p><b>Thread confinement:</b> any thread inside the binding scope; the class documentation of
 * {@link eu.exeris.kernel.spi.context.KernelProviders} states which threads see a binding.
 * <p><b>Ownership:</b> the kernel bootstrapper owns each bound provider and engine; a reader borrows
 * the reference for the duration of the binding scope and neither closes nor restarts it.
 *
 * @since 0.13
 */
public final class GraphKernelProviders {

    /**
     * The active {@link GraphProvider} factory (bound once during bootstrap).
     *
     * @apiNote Read this slot only from bootstrap code that has to introspect the provider; a
     *          graph call site reads {@link #GRAPH_ENGINE} instead.
     * @since 0.13
     */
    public static final ScopedValue<GraphProvider> GRAPH_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link GraphEngine} (created from {@link #GRAPH_PROVIDER}).
     *
     * <p>This is the primary slot for all graph operations. Bound once during bootstrap for the
     * kernel's lifetime; the class documentation says which threads see it.
     *
     * @since 0.13
     */
    public static final ScopedValue<GraphEngine> GRAPH_ENGINE = ScopedValue.newInstance();

    private GraphKernelProviders() {
        // Static ScopedValue slots only — never instantiated.
    }

    /**
     * Returns the active {@link GraphEngine} from the current scope.
     *
     * @return graph engine bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     *         or if graph was not bootstrapped
     * @since 0.13
     */
    public static GraphEngine graphEngine() {
        return GRAPH_ENGINE.get();
    }
}
