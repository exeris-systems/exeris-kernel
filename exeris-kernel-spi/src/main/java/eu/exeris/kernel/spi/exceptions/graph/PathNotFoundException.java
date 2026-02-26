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

import java.util.UUID;

/**
 * Thrown when a shortest-path algorithm fails to find a path between two nodes.
 *
 * <h2>rawArgs Binary Layout (Enterprise Black-Box)</h2>
 * <pre>
 * index 0 → String  sourceNodeId   (source node identifier, UUID string form)
 * index 1 → String  targetNodeId   (target node identifier, UUID string form)
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
     * <p>UUID-to-String conversion is performed internally so callers
     * are not burdened with formatting — consistent with the Graph SPI
     * convention that all node identifiers are {@link UUID}.
     *
     * @param sourceNodeId source node identifier
     * @param targetNodeId target node identifier
     */
    public PathNotFoundException(UUID sourceNodeId, UUID targetNodeId) {
        super(KernelErrorCodes.EX_GRPH_5004, MESSAGE, null,
                sourceNodeId.toString(), targetNodeId.toString());
    }
}

