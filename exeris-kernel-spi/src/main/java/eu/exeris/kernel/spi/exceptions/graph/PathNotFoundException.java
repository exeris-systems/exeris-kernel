/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a shortest-path algorithm fails to find a path between two nodes.
 *
 * <h2>rawArgs Binary Layout (Enterprise Black-Box)</h2>
 * <pre>
 * index 0 → String  sourceNodeId   (source node identifier)
 * index 1 → String  targetNodeId   (target node identifier)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_GRPH_5004}
 *
 * @since 0.5.0
 */
public final class PathNotFoundException extends ExerisKernelException {

    private static final String MESSAGE = "Path not found between graph nodes";

    /**
     * Primary constructor.
     *
     * @param sourceNodeId source node identifier
     * @param targetNodeId target node identifier
     */
    public PathNotFoundException(String sourceNodeId, String targetNodeId) {
        super(KernelErrorCodes.EX_GRPH_5004, MESSAGE, null, sourceNodeId, targetNodeId);
    }
}

