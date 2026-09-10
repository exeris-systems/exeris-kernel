/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when a shortest-path algorithm fails to find a path between two nodes.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → long  sourceMost   (UUID.getMostSignificantBits())
 * index 1 → long  sourceLeast  (UUID.getLeastSignificantBits())
 * index 2 → long  targetMost   (UUID.getMostSignificantBits())
 * index 3 → long  targetLeast  (UUID.getLeastSignificantBits())
 * </pre>
 *
 * <p>UUID is decomposed into two {@code long} primitives per node, keeping {@code rawArgs}
 * allocation-free and binary-serialisable by the Enterprise Glass-Box tier without a codec.
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_GRPH_5004}
 *
 * <p><b>Allocation:</b> allocates (one {@code rawArgs} array per instance, boxing its four
 * {@code long} components); no constructor formats a string, and the message text is a
 * shared constant.
 *
 * @since 0.5
 */
public final class PathNotFoundException extends ExerisKernelException {

    private static final String MESSAGE = "Path not found between graph nodes";

    /**
     * Primary constructor.
     *
     * <p>Each UUID is decomposed into {@code getMostSignificantBits()} and
     * {@code getLeastSignificantBits()}, storing four {@code long} primitives in
     * {@code rawArgs}. This eliminates the {@code UUID} object header from the
     * telemetry payload and keeps the binary layout fixed-width.
     *
     * @param sourceNodeId source node identifier; must not be {@code null}
     * @param targetNodeId target node identifier; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public PathNotFoundException(UUID sourceNodeId, UUID targetNodeId) {
        super(KernelErrorCodes.EX_GRPH_5004, MESSAGE, null,
                Objects.requireNonNull(sourceNodeId, "sourceNodeId").getMostSignificantBits(),
                sourceNodeId.getLeastSignificantBits(),
                Objects.requireNonNull(targetNodeId, "targetNodeId").getMostSignificantBits(),
                targetNodeId.getLeastSignificantBits());
    }
}

