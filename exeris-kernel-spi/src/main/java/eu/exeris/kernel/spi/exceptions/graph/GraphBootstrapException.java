/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the {@link eu.exeris.kernel.spi.graph.GraphProvider} fails to create
 * a {@link eu.exeris.kernel.spi.graph.GraphEngine} during bootstrap.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  providerName   (e.g. "ExerisCommunity/JdbcGraph")
 * index 1 → String  reason         (static failure description)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_GRPH_5001}
 *
 * <p><b>Allocation:</b> allocates (one {@code rawArgs} array per instance); no constructor
 * formats a string, and the message text is a shared constant.
 *
 * @since 0.5
 */
public final class GraphBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Graph engine bootstrap failure";

    /**
     * Primary constructor.
     *
     * @param providerName which provider failed to initialise
     * @param reason       static failure description — no runtime formatting permitted
     */
    public GraphBootstrapException(String providerName, String reason) {
        super(KernelErrorCodes.EX_GRPH_5001, MESSAGE, null, providerName, reason);
    }

    /**
     * Chained constructor — wraps the upstream throwable.
     *
     * @param providerName which provider failed to initialise
     * @param reason       static failure description
     * @param cause        the upstream throwable
     */
    public GraphBootstrapException(String providerName, String reason, Throwable cause) {
        super(KernelErrorCodes.EX_GRPH_5001, MESSAGE, cause, providerName, reason);
    }
}

