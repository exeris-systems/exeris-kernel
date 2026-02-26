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
 * Thrown when a graph driver exceeds its pre-defined off-heap churn threshold.
 *
 * <p>This exception enforces the <em>No Waste Compute</em> mandate: a driver
 * that allocates more memory than it transfers is a performance hazard and must
 * be evicted from the hot-path immediately.
 *
 * <h2>rawArgs Binary Layout (Enterprise Black-Box)</h2>
 * <pre>
 * index 0 → String  driverName       (the offending graph driver identifier)
 * index 1 → long    bytesAllocated   (total off-heap bytes allocated by the driver)
 * index 2 → long    bytesTransferred (actual bytes transferred / used)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_GRPH_5005}
 *
 * @since 0.5.0
 */
public final class ExcessiveAllocationException extends ExerisKernelException {

    private static final String MESSAGE = "Driver exceeded churn threshold";

    /**
     * Creates the exception for a driver that exceeded its allocation threshold.
     *
     * @param driverName       identifier of the offending graph driver
     * @param bytesAllocated   total off-heap bytes allocated
     * @param bytesTransferred actual bytes transferred (useful work)
     */
    public ExcessiveAllocationException(String driverName, long bytesAllocated, long bytesTransferred) {
        super(KernelErrorCodes.EX_GRPH_5005, MESSAGE, null, driverName, bytesAllocated, bytesTransferred);
    }
}

