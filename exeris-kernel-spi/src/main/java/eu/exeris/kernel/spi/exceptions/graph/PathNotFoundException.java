/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when a shortest-path algorithm fails to find a path between two nodes.
 *
 * <h2>rawArgs Binary Layout (Enterprise Black-Box)</h2>
 * <pre>
 * index 0 → UUID  sourceNodeId   (source node identifier, stored as-is for binary telemetry)
 * index 1 → UUID  targetNodeId   (target node identifier, stored as-is for binary telemetry)
 * </pre>
 *
 * <p>UUID objects are stored without eager {@code toString()} conversion, consistent with
 * the {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException} contract that rawArgs
 * are captured as-is. The Enterprise Black-Box tier serialises them via its own codec.
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
     * <p>UUIDs are stored directly in {@code rawArgs} without eager {@code toString()}
     * conversion, avoiding unnecessary heap allocation and preserving the binary
     * telemetry contract of {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException}.
     *
     * @param sourceNodeId source node identifier; must not be {@code null}
     * @param targetNodeId target node identifier; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public PathNotFoundException(UUID sourceNodeId, UUID targetNodeId) {
        super(KernelErrorCodes.EX_GRPH_5004, MESSAGE, null,
                Objects.requireNonNull(sourceNodeId, "sourceNodeId"),
                Objects.requireNonNull(targetNodeId, "targetNodeId"));
    }
}

