/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a graph query (traversal, MATCH, CRUD) fails during execution.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  queryType      (e.g. "BFS", "MATCH", "SHORTEST_PATH")
 * index 1 → String  detail         (static failure description)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_GRPH_5002}
 *
 * @since 0.5.0
 */
public final class GraphQueryException extends ExerisKernelException {

    private static final String MESSAGE = "Graph query execution failure";

    /**
     * Primary constructor.
     *
     * @param queryType type of query that failed (e.g. "BFS", "MATCH")
     * @param detail    static failure description
     */
    public GraphQueryException(String queryType, String detail) {
        super(KernelErrorCodes.EX_GRPH_5002, MESSAGE, null, queryType, detail);
    }

    /**
     * Chained constructor.
     *
     * @param queryType type of query that failed
     * @param detail    static failure description
     * @param cause     the upstream throwable
     */
    public GraphQueryException(String queryType, String detail, Throwable cause) {
        super(KernelErrorCodes.EX_GRPH_5002, MESSAGE, cause, queryType, detail);
    }
}

