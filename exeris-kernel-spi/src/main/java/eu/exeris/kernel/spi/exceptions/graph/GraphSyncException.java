/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when dual-write graph synchronization fails.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  edgeType       (e.g. "FOLLOWS", "SIMILAR_TO")
 * index 1 → String  detail         (static failure description)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_GRPH_5003}
 *
 * <p><b>Allocation:</b> allocates (one {@code rawArgs} array per instance); no constructor
 * formats a string, and the message text is a shared constant.
 *
 * @since 0.5
 */
public final class GraphSyncException extends ExerisKernelException {

    private static final String MESSAGE = "Graph dual-write sync failure";

    /**
     * Primary constructor.
     *
     * @param edgeType the edge type that failed to sync
     * @param detail   static failure description
     */
    public GraphSyncException(String edgeType, String detail) {
        super(KernelErrorCodes.EX_GRPH_5003, MESSAGE, null, edgeType, detail);
    }

    /**
     * Chained constructor.
     *
     * @param edgeType the edge type that failed to sync
     * @param detail   static failure description
     * @param cause    the upstream throwable
     */
    public GraphSyncException(String edgeType, String detail, Throwable cause) {
        super(KernelErrorCodes.EX_GRPH_5003, MESSAGE, cause, edgeType, detail);
    }
}

